package learnat.identity

import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Hashtable
import javax.naming.directory.InitialDirContext
import learnat.json.Json
import learnat.syntax.AtIdentifier
import learnat.syntax.Did
import learnat.syntax.Handle

enum IdentityError:
  case InvalidDocument(message: String)
  case UnsupportedDidMethod(method: String)
  case ResolutionDisallowed(identifier: String, reason: String)
  case Network(message: String, cause: Option[Throwable])
  case HttpStatus(uri: URI, status: Int)
  case ResponseTooLarge(uri: URI, limit: Int, actual: Int)
  case HandleNotFound(handle: Handle)
  case AmbiguousHandle(handle: Handle, dids: Vector[Did])
  case HandleMismatch(handle: Handle, did: Did, claimed: Option[Handle])
  case MissingPds(did: Did)
  case MissingSigningKey(did: Did)

  def description: String = this match
    case InvalidDocument(detail) => s"invalid DID document: $detail"
    case UnsupportedDidMethod(method) => s"unsupported DID method: $method"
    case ResolutionDisallowed(identifier, reason) => s"resolution of $identifier is disallowed: $reason"
    case Network(detail, _) => detail
    case HttpStatus(uri, status) => s"GET $uri returned HTTP $status"
    case ResponseTooLarge(uri, limit, actual) => s"GET $uri returned $actual bytes; limit is $limit"
    case HandleNotFound(handle) => s"handle did not resolve: ${handle.value}"
    case AmbiguousHandle(handle, dids) => s"handle ${handle.value} resolved to multiple DIDs: ${dids.mkString(", ")}"
    case HandleMismatch(handle, did, claimed) =>
      s"handle ${handle.value} resolved to ${did.value}, but the DID document claims ${claimed.fold("no handle")(_.value)}"
    case MissingPds(did) => s"DID document has no valid atproto PDS service: ${did.value}"
    case MissingSigningKey(did) => s"DID document has no valid atproto signing key: ${did.value}"

final case class IdentityHttpResponse(status: Int, body: Array[Byte])

trait IdentityNetwork:
  def dnsTxt(name: String): Either[IdentityError, Vector[String]]
  def get(uri: URI, maxBytes: Int): Either[IdentityError, IdentityHttpResponse]

final class JdkIdentityNetwork private (client: HttpClient) extends IdentityNetwork:
  override def dnsTxt(name: String): Either[IdentityError, Vector[String]] =
    val environment = Hashtable[String, String]()
    environment.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory")
    var context: Option[InitialDirContext] = None
    try
      val created = InitialDirContext(environment)
      context = Some(created)
      val attribute = created.getAttributes(name, Array("TXT")).get("TXT")
      if attribute == null then Right(Vector.empty)
      else
        val values = Vector.newBuilder[String]
        var index = 0
        while index < attribute.size() do
          values += attribute.get(index).toString.replace("\"", "")
          index += 1
        Right(values.result())
    catch
      case _: javax.naming.NameNotFoundException => Right(Vector.empty)
      case error: Exception => Left(IdentityError.Network(s"DNS TXT lookup failed for $name", Some(error)))
    finally context.foreach(_.close())

  override def get(uri: URI, maxBytes: Int): Either[IdentityError, IdentityHttpResponse] =
    try
      val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build()
      val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
      val body = response.body()
      if body.length > maxBytes then Left(IdentityError.ResponseTooLarge(uri, maxBytes, body.length))
      else Right(IdentityHttpResponse(response.statusCode(), body))
    catch
      case interrupted: InterruptedException =>
        Thread.currentThread().interrupt()
        Left(IdentityError.Network(s"GET $uri was interrupted", Some(interrupted)))
      case error: Exception => Left(IdentityError.Network(s"GET $uri failed", Some(error)))

object JdkIdentityNetwork:
  def default: JdkIdentityNetwork =
    val client = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(10))
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build()
    new JdkIdentityNetwork(client)

final case class VerificationMethod(id: String, controller: String, methodType: String, publicKeyMultibase: String)
final case class DidService(id: String, serviceType: String, endpoint: String)

final case class DidDocument(
    id: Did,
    alsoKnownAs: Vector[String],
    verificationMethods: Vector[VerificationMethod],
    services: Vector[DidService]
):
  def claimedHandle: Option[Handle] =
    alsoKnownAs.iterator.flatMap { value =>
      if value.startsWith("at://") && !value.drop(5).contains('/') && !value.contains('#') && !value.contains('?') then
        Handle.parse(value.drop(5)).toOption
      else None
    }.nextOption()

  def atprotoSigningKey: Option[String] =
    verificationMethods.find { method =>
      matchesFragment(method.id, "#atproto") &&
      method.controller == id.value &&
      method.methodType == "Multikey" &&
      method.publicKeyMultibase.startsWith("z")
    }.map(_.publicKeyMultibase)

  def pdsEndpoint(allowHttpLocal: Boolean): Option[URI] =
    services.iterator.filter { service =>
      matchesFragment(service.id, "#atproto_pds") && service.serviceType == "AtprotoPersonalDataServer"
    }.flatMap(service => parsePdsUri(service.endpoint, allowHttpLocal)).nextOption()

  private def matchesFragment(candidate: String, fragment: String): Boolean =
    candidate == fragment || candidate == s"${id.value}$fragment"

  private def parsePdsUri(value: String, allowHttpLocal: Boolean): Option[URI] =
    try
      val uri = URI.create(value)
      val path = Option(uri.getPath).getOrElse("")
      val secure = uri.getScheme == "https"
      val local = allowHttpLocal && uri.getScheme == "http" && (uri.getHost == "localhost" || uri.getHost == "127.0.0.1")
      if
        (secure || local) && uri.getHost != null && uri.getUserInfo == null &&
          (path.isEmpty || path == "/") && uri.getQuery == null && uri.getFragment == null
      then Some(URI.create(uri.toString.stripSuffix("/")))
      else None
    catch case _: IllegalArgumentException => None

object DidDocument:
  def decode(expected: Did, json: Json): Either[IdentityError, DidDocument] =
    for
      idText <- json.field("id").flatMap(_.asString).left.map(error => invalid(error.message))
      id <- Did.parse(idText).left.map(error => invalid(error.message))
      _ <- Either.cond(id == expected, (), invalid(s"id ${id.value} does not match expected ${expected.value}"))
      alsoKnownAs <- stringArray(json, "alsoKnownAs")
      verificationJson <- jsonArray(json, "verificationMethod")
      serviceJson <- jsonArray(json, "service")
    yield DidDocument(
      id,
      alsoKnownAs,
      verificationJson.flatMap(decodeVerificationMethod),
      serviceJson.flatMap(decodeService)
    )

  private def stringArray(json: Json, field: String): Either[IdentityError, Vector[String]] =
    json.optionalField(field).left.map(error => invalid(error.message)).flatMap {
      case None => Right(Vector.empty)
      case Some(value) =>
        value.asArray.left.map(error => invalid(s"$field: ${error.message}")).flatMap { values =>
          values.foldLeft[Either[IdentityError, Vector[String]]](Right(Vector.empty)) { (result, item) =>
            for
              accumulated <- result
              string <- item.asString.left.map(error => invalid(s"$field: ${error.message}"))
            yield accumulated :+ string
          }
        }
    }

  private def jsonArray(json: Json, field: String): Either[IdentityError, Vector[Json]] =
    json.optionalField(field).left.map(error => invalid(error.message)).flatMap {
      case None => Right(Vector.empty)
      case Some(value) => value.asArray.left.map(error => invalid(s"$field: ${error.message}"))
    }

  private def decodeVerificationMethod(json: Json): Option[VerificationMethod] =
    (for
      id <- json.field("id").flatMap(_.asString)
      controller <- json.field("controller").flatMap(_.asString)
      methodType <- json.field("type").flatMap(_.asString)
      key <- json.field("publicKeyMultibase").flatMap(_.asString)
    yield VerificationMethod(id, controller, methodType, key)).toOption

  private def decodeService(json: Json): Option[DidService] =
    (for
      id <- json.field("id").flatMap(_.asString)
      serviceType <- json.field("type").flatMap(_.asString)
      endpoint <- json.field("serviceEndpoint").flatMap(_.asString)
    yield DidService(id, serviceType, endpoint)).toOption

  private def invalid(message: String): IdentityError = IdentityError.InvalidDocument(message)

final case class ResolvedIdentity(
    did: Did,
    handle: Option[Handle],
    pds: URI,
    signingKeyMultibase: String,
    document: DidDocument
)

final case class IdentityResolverConfig(
    plcDirectory: URI = URI.create("https://plc.directory"),
    allowHttpLocal: Boolean = false,
    allowTestTld: Boolean = false,
    maxDocumentBytes: Int = 1024 * 1024
)

final class IdentityResolver(network: IdentityNetwork, config: IdentityResolverConfig = IdentityResolverConfig()):
  private val disallowedTlds = Vector(".alt", ".arpa", ".example", ".internal", ".invalid", ".local", ".localhost", ".onion")

  def resolve(input: AtIdentifier): Either[IdentityError, ResolvedIdentity] = input match
    case AtIdentifier.HandleIdentifier(handle) => resolveFromHandle(handle)
    case AtIdentifier.DidIdentifier(did) => resolveFromDid(did)

  def resolveHandle(handle: Handle): Either[IdentityError, Did] =
    Handle.parse(handle.normalized)
      .left.map(error => IdentityError.ResolutionDisallowed(handle.value, error.message))
      .flatMap { normalized =>
        disallowedReason(normalized) match
          case Some(reason) => Left(IdentityError.ResolutionDisallowed(normalized.value, reason))
          case None =>
            dnsDid(normalized) match
              case Right(Some(did)) => Right(did)
              case Left(ambiguous: IdentityError.AmbiguousHandle) => Left(ambiguous)
              case _ => httpsDid(normalized)
      }

  def resolveDid(did: Did): Either[IdentityError, DidDocument] =
    did.method match
      case "plc" => fetchDidDocument(did, URI.create(s"${config.plcDirectory.toString.stripSuffix("/")}/${did.value}"))
      case "web" => didWebUri(did).flatMap(uri => fetchDidDocument(did, uri))
      case other => Left(IdentityError.UnsupportedDidMethod(other))

  private def resolveFromHandle(handle: Handle): Either[IdentityError, ResolvedIdentity] =
    for
      did <- resolveHandle(handle)
      document <- resolveDid(did)
      claimed = document.claimedHandle
      _ <- Either.cond(
        claimed.exists(_.normalized == handle.normalized),
        (),
        IdentityError.HandleMismatch(handle, did, claimed)
      )
      identity <- assemble(did, Some(handle), document)
    yield identity

  private def resolveFromDid(did: Did): Either[IdentityError, ResolvedIdentity] =
    resolveDid(did).flatMap { document =>
      val verifiedHandle = document.claimedHandle.filter(handle => resolveHandle(handle).contains(did))
      assemble(did, verifiedHandle, document)
    }

  private def assemble(did: Did, handle: Option[Handle], document: DidDocument): Either[IdentityError, ResolvedIdentity] =
    for
      pds <- document.pdsEndpoint(config.allowHttpLocal).toRight(IdentityError.MissingPds(did))
      signingKey <- document.atprotoSigningKey.toRight(IdentityError.MissingSigningKey(did))
    yield ResolvedIdentity(did, handle, pds, signingKey, document)

  private def dnsDid(handle: Handle): Either[IdentityError, Option[Did]] =
    network.dnsTxt(s"_atproto.${handle.normalized}").flatMap { records =>
      val dids = records.iterator.filter(_.startsWith("did=")).flatMap(value => Did.parse(value.drop(4)).toOption).toVector.distinct
      dids match
        case Vector() => Right(None)
        case Vector(did) => Right(Some(did))
        case many => Left(IdentityError.AmbiguousHandle(handle, many))
    }

  private def httpsDid(handle: Handle): Either[IdentityError, Did] =
    val developmentHandle = handle.normalized.endsWith(".test") || handle.normalized.endsWith(".localhost")
    val scheme = if config.allowHttpLocal && developmentHandle then "http" else "https"
    val uri = URI.create(s"$scheme://${handle.normalized}/.well-known/atproto-did")
    fetch(uri).flatMap { response =>
      val value = String(response.body, StandardCharsets.UTF_8).trim
      Did.parse(value).left.map(_ => IdentityError.HandleNotFound(handle))
    }

  private def fetchDidDocument(did: Did, uri: URI): Either[IdentityError, DidDocument] =
    fetch(uri).flatMap { response =>
      val text = String(response.body, StandardCharsets.UTF_8)
      Json.parse(text)
        .left.map(error => IdentityError.InvalidDocument(error.toString))
        .flatMap(document => DidDocument.decode(did, document))
    }

  private def fetch(uri: URI): Either[IdentityError, IdentityHttpResponse] =
    network.get(uri, config.maxDocumentBytes).flatMap { response =>
      if response.status >= 200 && response.status < 300 then Right(response)
      else Left(IdentityError.HttpStatus(uri, response.status))
    }

  private def didWebUri(did: Did): Either[IdentityError, URI] =
    val encoded = did.value.stripPrefix("did:web:")
    val decoded =
      try Right(URLDecoder.decode(encoded, StandardCharsets.UTF_8))
      catch case _: IllegalArgumentException => Left(IdentityError.ResolutionDisallowed(did.value, "invalid percent encoding"))
    decoded.flatMap { domain =>
      val localhost = domain == "localhost" || domain.startsWith("localhost:")
      if domain.contains('/') || (domain.contains(':') && !localhost) then
        Left(IdentityError.ResolutionDisallowed(did.value, "atproto supports only hostname-level did:web identifiers"))
      else if localhost && !config.allowHttpLocal then
        Left(IdentityError.ResolutionDisallowed(did.value, "localhost did:web is disabled"))
      else if localhost then Right(URI.create(s"http://$domain/.well-known/did.json"))
      else
        Handle.parse(domain).left.map(error => IdentityError.ResolutionDisallowed(did.value, error.message)).flatMap { handle =>
          disallowedReason(handle) match
            case Some(reason) => Left(IdentityError.ResolutionDisallowed(did.value, reason))
            case None => Right(URI.create(s"https://${handle.normalized}/.well-known/did.json"))
        }
    }

  private def disallowedReason(handle: Handle): Option[String] =
    val normalized = handle.normalized
    disallowedTlds.find(normalized.endsWith).map(tld => s"$tld is not allowed for resolution")
      .orElse(if normalized.endsWith(".test") && !config.allowTestTld then Some(".test is disabled outside development") else None)

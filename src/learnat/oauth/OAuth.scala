package learnat.oauth

import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import learnat.json.Json
import learnat.syntax.Did

/** A malformed OAuth value, metadata document, or security binding. */
final case class OAuthError(message: String):
  override def toString: String = message

private[oauth] object Base64Url:
  def encode(bytes: Array[Byte]): String = Base64.getUrlEncoder.withoutPadding()
    .encodeToString(bytes)

  def decode(value: String, label: String): Either[OAuthError, Array[Byte]] =
    if value.contains('=') then Left(OAuthError(s"$label must use unpadded base64url"))
    else
      try Right(Base64.getUrlDecoder.decode(value))
      catch case _: IllegalArgumentException => Left(OAuthError(s"$label is not valid base64url"))

/** One per-authorization RFC 7636 verifier and its S256 challenge. */
final case class PkcePair private (verifier: String, challenge: String)

object PkcePair:
  private val Verifier = "^[A-Za-z0-9._~-]{43,128}$".r

  /** Generates 256 random bits, producing the recommended 43-character verifier. */
  def generate(random: SecureRandom = SecureRandom()): PkcePair =
    val bytes = Array.ofDim[Byte](32)
    random.nextBytes(bytes)
    fromVerifier(Base64Url.encode(bytes))
      .fold(error => throw IllegalStateException(error.message), identity)

  /** Validates a verifier and derives its mandatory S256 challenge. */
  def fromVerifier(verifier: String): Either[OAuthError, PkcePair] =
    if !Verifier.matches(verifier) then
      Left(OAuthError("PKCE verifier must contain 43-128 RFC 7636 unreserved characters"))
    else
      val digest = MessageDigest.getInstance("SHA-256")
        .digest(verifier.getBytes(StandardCharsets.US_ASCII))
      Right(PkcePair(verifier, Base64Url.encode(digest)))

/** Unpredictable callback correlation value; it contains no serialized session. */
final case class OAuthState private (value: String)

object OAuthState:
  /** Generates a fresh 256-bit state value. */
  def generate(random: SecureRandom = SecureRandom()): OAuthState =
    val bytes = Array.ofDim[Byte](32)
    random.nextBytes(bytes)
    OAuthState(Base64Url.encode(bytes))

/** OAuth protected-resource metadata published by a PDS. */
final case class ProtectedResourceMetadata(resource: URI, authorizationServer: URI)

/** OAuth authorization-server capabilities required by the atproto profile. */
final case class AuthorizationServerMetadata(
    issuer: URI,
    authorizationEndpoint: URI,
    tokenEndpoint: URI,
    pushedAuthorizationRequestEndpoint: URI,
    scopesSupported: Set[String]
)

/** Strict parsers for the two discovery documents used before authorization. */
object OAuthMetadata:
  /**
   * Parses PDS metadata and binds its `resource` field to the origin fetched. HTTP is permitted
   * only for explicit localhost development.
   */
  def parseProtectedResource(
      json: Json,
      fetchedResourceOrigin: URI,
      allowLoopbackDevelopment: Boolean = false
  ): Either[OAuthError, ProtectedResourceMetadata] =
    for
      expected <- origin(fetchedResourceOrigin, allowLoopbackDevelopment, "fetched resource origin")
      resourceText <- stringField(json, "resource")
      resource <- parseUri(resourceText, "resource")
      normalizedResource <- origin(resource, allowLoopbackDevelopment, "resource")
      _ <- require(
        normalizedResource == expected,
        "protected-resource metadata does not match the fetched PDS origin"
      )
      servers <- stringArrayField(json, "authorization_servers")
      _ <- require(servers.length == 1, "authorization_servers must contain exactly one origin")
      server <- parseUri(servers.head, "authorization server")
      authorizationServer <- origin(server, allowLoopbackDevelopment, "authorization server")
    yield ProtectedResourceMetadata(normalizedResource, authorizationServer)

  /** Parses and profile-checks RFC 8414 authorization-server metadata. */
  def parseAuthorizationServer(
      json: Json,
      fetchedIssuerOrigin: URI,
      allowLoopbackDevelopment: Boolean = false
  ): Either[OAuthError, AuthorizationServerMetadata] =
    for
      expected <- origin(fetchedIssuerOrigin, allowLoopbackDevelopment, "fetched issuer origin")
      issuerText <- stringField(json, "issuer")
      issuerUri <- parseUri(issuerText, "issuer")
      issuer <- origin(issuerUri, allowLoopbackDevelopment, "issuer")
      _ <-
        require(issuer == expected, "authorization-server issuer does not match the fetched origin")
      authorization <- endpointField(json, "authorization_endpoint", allowLoopbackDevelopment)
      token <- endpointField(json, "token_endpoint", allowLoopbackDevelopment)
      par <- endpointField(json, "pushed_authorization_request_endpoint", allowLoopbackDevelopment)
      responseTypes <- stringArrayField(json, "response_types_supported")
      _ <- require(
        responseTypes.contains("code"),
        "authorization server must support response type code"
      )
      grantTypes <- stringArrayField(json, "grant_types_supported")
      _ <- require(
        grantTypes.contains("authorization_code") && grantTypes.contains("refresh_token"),
        "authorization server must support authorization_code and refresh_token"
      )
      challenges <- stringArrayField(json, "code_challenge_methods_supported")
      _ <- require(challenges.contains("S256"), "authorization server must support PKCE S256")
      authMethods <- stringArrayField(json, "token_endpoint_auth_methods_supported")
      _ <- require(
        authMethods.contains("none") && authMethods.contains("private_key_jwt"),
        "authorization server must support public and confidential clients"
      )
      signing <- stringArrayField(json, "token_endpoint_auth_signing_alg_values_supported")
      _ <- require(
        signing.contains("ES256") && !signing.contains("none"),
        "authorization server must support ES256 client assertions and reject none"
      )
      scopes <- stringArrayField(json, "scopes_supported")
      _ <-
        require(scopes.contains("atproto"), "authorization server must support the atproto scope")
      responseIssuer <- booleanField(json, "authorization_response_iss_parameter_supported")
      _ <- require(responseIssuer, "authorization response issuer parameter must be supported")
      requiresPar <- booleanField(json, "require_pushed_authorization_requests")
      _ <- require(requiresPar, "PAR must be required")
      dpopAlgorithms <- stringArrayField(json, "dpop_signing_alg_values_supported")
      _ <- require(dpopAlgorithms.contains("ES256"), "authorization server must support ES256 DPoP")
      metadataClient <- booleanField(json, "client_id_metadata_document_supported")
      _ <- require(metadataClient, "client ID metadata documents must be supported")
      requestUriRegistration <- optionalBooleanField(json, "require_request_uri_registration")
      _ <- require(
        !requestUriRegistration.contains(false),
        "require_request_uri_registration may not be false"
      )
    yield AuthorizationServerMetadata(issuer, authorization, token, par, scopes.toSet)

  private def endpointField(
      json: Json,
      name: String,
      allowLoopback: Boolean
  ): Either[OAuthError, URI] = stringField(json, name).flatMap(parseUri(_, name))
    .flatMap(endpoint(_, allowLoopback, name))

  private def origin(uri: URI, allowLoopback: Boolean, label: String): Either[OAuthError, URI] =
    val loopback = allowLoopback && uri.getScheme == "http" && uri.getHost == "localhost"
    val path = Option(uri.getRawPath).getOrElse("")
    val defaultPort = (uri.getScheme == "https" && uri.getPort == 443) ||
      (uri.getScheme == "http" && uri.getPort == 80)
    val valid =
      (uri.getScheme == "https" || loopback) && uri.getHost != null && uri.getRawUserInfo == null &&
        (path.isEmpty || path == "/") && uri.getRawQuery == null && uri.getRawFragment == null &&
        !defaultPort
    if !valid then Left(OAuthError(s"$label must be a simple HTTPS origin"))
    else
      val port = if uri.getPort < 0 then "" else s":${uri.getPort}"
      val host = uri.getHost.toLowerCase(java.util.Locale.ROOT)
      val renderedHost = if host.contains(':') then s"[$host]" else host
      Right(URI.create(s"${uri.getScheme.toLowerCase(java.util.Locale.ROOT)}://$renderedHost$port"))

  private def endpoint(uri: URI, allowLoopback: Boolean, label: String): Either[OAuthError, URI] =
    val loopback = allowLoopback && uri.getScheme == "http" && uri.getHost == "localhost"
    val valid =
      (uri.getScheme == "https" || loopback) && uri.getHost != null && uri.getRawUserInfo == null &&
        uri.getRawFragment == null && uri.getRawQuery == null &&
        Option(uri.getRawPath).exists(_.startsWith("/"))
    if valid then Right(uri)
    else
      Left(OAuthError(s"$label must be an absolute HTTPS endpoint without credentials or fragment"))

  private def parseUri(value: String, label: String): Either[OAuthError, URI] =
    try Right(URI(value))
    catch case _: IllegalArgumentException => Left(OAuthError(s"$label is not a valid URI"))

  private def stringField(json: Json, name: String): Either[OAuthError, String] = json.field(name)
    .flatMap(_.asString).left.map(error => OAuthError(s"$name: ${error.message}"))

  private def stringArrayField(json: Json, name: String): Either[OAuthError, Vector[String]] = json
    .field(name).flatMap(_.asArray).left.map(error => OAuthError(s"$name: ${error.message}"))
    .flatMap { values =>
      values.zipWithIndex.foldLeft[Either[OAuthError, Vector[String]]](Right(Vector.empty)) {
        case (result, (value, index)) =>
          for
            strings <- result
            string <- value.asString.left
              .map(error => OAuthError(s"$name[$index]: ${error.message}"))
          yield strings :+ string
      }
    }

  private def booleanField(json: Json, name: String): Either[OAuthError, Boolean] = json.field(name)
    .flatMap(_.asBoolean).left.map(error => OAuthError(s"$name: ${error.message}"))

  private def optionalBooleanField(json: Json, name: String): Either[OAuthError, Option[Boolean]] =
    json.optionalField(name).left.map(error => OAuthError(error.message)).flatMap {
      case None        => Right(None)
      case Some(value) => value.asBoolean.left.map(error => OAuthError(s"$name: ${error.message}"))
          .map(Some.apply)
    }

  private def require(condition: Boolean, message: => String): Either[OAuthError, Unit] = Either
    .cond(condition, (), OAuthError(message))

/** Token response after a code or refresh exchange. Tokens remain opaque. */
final case class OAuthTokenResponse(
    subject: Did,
    accessToken: String,
    refreshToken: Option[String],
    expiresInSeconds: Long,
    scopes: Set[String]
)

object OAuthTokenResponse:
  /** Parses required atproto fields and optionally binds `sub` to an expected DID. */
  def parse(json: Json, expectedSubject: Option[Did]): Either[OAuthError, OAuthTokenResponse] =
    for
      subjectText <- stringField(json, "sub")
      subject <- Did.parse(subjectText).left.map(error => OAuthError(error.toString))
      _ <- Either.cond(
        expectedSubject.forall(_ == subject),
        (),
        OAuthError("token subject does not match the account bound to the authorization flow")
      )
      access <- stringField(json, "access_token")
      tokenType <- stringField(json, "token_type")
      _ <- Either.cond(
        tokenType.equalsIgnoreCase("DPoP"),
        (),
        OAuthError("atproto access token must use token_type DPoP")
      )
      refresh <- optionalStringField(json, "refresh_token")
      expires <- longField(json, "expires_in")
      _ <- Either.cond(expires > 0, (), OAuthError("expires_in must be positive"))
      scopeText <- stringField(json, "scope")
      scopes = scopeText.split(" +").filter(_.nonEmpty).toSet
      _ <- Either
        .cond(scopes.contains("atproto"), (), OAuthError("token scope must include atproto"))
    yield OAuthTokenResponse(subject, access, refresh, expires, scopes)

  private def stringField(json: Json, name: String): Either[OAuthError, String] = json.field(name)
    .flatMap(_.asString).left.map(error => OAuthError(s"$name: ${error.message}"))

  private def optionalStringField(json: Json, name: String): Either[OAuthError, Option[String]] =
    json.optionalField(name).left.map(error => OAuthError(error.message)).flatMap {
      case None        => Right(None)
      case Some(value) => value.asString.left.map(error => OAuthError(s"$name: ${error.message}"))
          .map(Some.apply)
    }

  private def longField(json: Json, name: String): Either[OAuthError, Long] = json.field(name)
    .flatMap(_.asLong).left.map(error => OAuthError(s"$name: ${error.message}"))

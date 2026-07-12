package learnat.client

import java.net.URI
import java.util.Arrays
import learnat.identity.IdentityResolver
import learnat.identity.ResolvedIdentity
import learnat.ipld.Cid
import learnat.ipld.DagJson
import learnat.ipld.Ipld
import learnat.json.Json
import learnat.syntax.AtIdentifier
import learnat.syntax.AtUri
import learnat.syntax.Did
import learnat.syntax.Handle
import learnat.syntax.Nsid
import learnat.syntax.RecordKey
import learnat.syntax.Tid
import learnat.xrpc.HttpTransport
import learnat.xrpc.JdkHttpTransport
import learnat.xrpc.XrpcClient
import learnat.xrpc.XrpcError

/** A typed client-side failure above the raw XRPC transport. */
final case class ClientError(message: String):
  override def toString: String = message

/** Legacy session data returned by a PDS. OAuth sessions are modeled separately. */
final case class LegacySession(did: Did, handle: Handle, accessJwt: String, refreshJwt: String)

/** A repository record with both stable AT URI and content-version CID. */
final case class RecordView(uri: AtUri, cid: Cid, value: Ipld)

/** Result of a successful record write. */
final case class RecordWriteResult(
    uri: AtUri,
    cid: Cid,
    commitCid: Option[Cid],
    revision: Option[String]
)

/** Paginated records returned by `com.atproto.repo.listRecords`. */
final case class RecordPage(records: Vector[RecordView], cursor: Option[String])

/** Current repository commit checkpoint returned by the sync API. */
final case class LatestCommit(cid: Cid, revision: Tid)

/**
 * Dependency-light AT Protocol client for identity-independent PDS calls.
 *
 * Construct with a known PDS origin, or use `discover` to resolve an account and bind the client to
 * the PDS declared in its DID document.
 */
final class AtpClient private (val service: URI, private val xrpc: XrpcClient):
  /** Authenticates using the legacy session endpoint for local/app-password exercises. */
  def login(
      identifier: AtIdentifier,
      password: Array[Char]
  ): Either[ClientError, AuthenticatedAtpClient] =
    val passwordString = String(password)
    try callProcedure(
        "com.atproto.server.createSession",
        Json.obj("identifier" -> Json.Str(identifier.text), "password" -> Json.Str(passwordString)),
        None
      ).flatMap(decodeSession).map(session => AuthenticatedAtpClient(this, session))
    finally Arrays.fill(password, '\u0000')

  /** Reads a public record directly from the PDS repository API. */
  def getRecord(
      repo: AtIdentifier,
      collection: Nsid,
      recordKey: RecordKey
  ): Either[ClientError, RecordView] = callQuery(
    "com.atproto.repo.getRecord",
    Vector("repo" -> repo.text, "collection" -> collection.value, "rkey" -> recordKey.value),
    None
  ).flatMap(decodeRecordView)

  /** Lists records from one collection in repository order. */
  def listRecords(
      repo: AtIdentifier,
      collection: Nsid,
      limit: Int = 50,
      cursor: Option[String] = None,
      reverse: Boolean = false
  ): Either[ClientError, RecordPage] =
    val parameters = Vector(
      "repo" -> repo.text,
      "collection" -> collection.value,
      "limit" -> limit.toString,
      "reverse" -> reverse.toString
    ) ++ cursor.map("cursor" -> _)
    callQuery("com.atproto.repo.listRecords", parameters, None).flatMap(decodeRecordPage)

  /** Downloads an unparsed complete repository CAR for independent verification. */
  def getRepo(did: Did): Either[ClientError, Array[Byte]] = nsid("com.atproto.sync.getRepo")
    .flatMap { method =>
      xrpc.queryBytes(method, Vector("did" -> did.value), accept = "application/vnd.ipld.car").left
        .map(fromXrpc).map(_.body)
    }

  /** Reads the PDS checkpoint used to avoid unnecessary full repository downloads. */
  def getLatestCommit(did: Did): Either[ClientError, LatestCommit] =
    callQuery("com.atproto.sync.getLatestCommit", Vector("did" -> did.value), None).flatMap { json =>
      for
        cidText <- stringField(json, "cid")
        cid <- Cid.parse(cidText).left.map(error => ClientError(error.toString))
        revText <- stringField(json, "rev")
        revision <- Tid.parse(revText).left.map(error => ClientError(error.toString))
      yield LatestCommit(cid, revision)
    }

  private[client] def procedure(
      method: String,
      input: Json,
      token: String
  ): Either[ClientError, Json] = callProcedure(method, input, Some(token))

  private[client] def query(
      method: String,
      parameters: Vector[(String, String)],
      token: String
  ): Either[ClientError, Json] = callQuery(method, parameters, Some(token))

  private def callQuery(
      method: String,
      parameters: Vector[(String, String)],
      token: Option[String]
  ): Either[ClientError, Json] = nsid(method)
    .flatMap(value => xrpc.query(value, parameters, token).left.map(fromXrpc))

  private def callProcedure(
      method: String,
      input: Json,
      token: Option[String]
  ): Either[ClientError, Json] = nsid(method)
    .flatMap(value => xrpc.procedure(value, input, token).left.map(fromXrpc))

  private def nsid(value: String): Either[ClientError, Nsid] = Nsid.parse(value).left
    .map(error => ClientError(error.toString))

  private def fromXrpc(error: XrpcError): ClientError = ClientError(error.description)

  private[client] def decodeSession(json: Json): Either[ClientError, LegacySession] =
    for
      didText <- stringField(json, "did")
      did <- Did.parse(didText).left.map(error => ClientError(error.toString))
      handleText <- stringField(json, "handle")
      handle <- Handle.parse(handleText).left.map(error => ClientError(error.toString))
      access <- stringField(json, "accessJwt")
      refresh <- stringField(json, "refreshJwt")
    yield LegacySession(did, handle, access, refresh)

  private def decodeRecordPage(json: Json): Either[ClientError, RecordPage] =
    for
      recordJson <- json.field("records").flatMap(_.asArray).left
        .map(error => ClientError(error.message))
      records <- recordJson
        .foldLeft[Either[ClientError, Vector[RecordView]]](Right(Vector.empty)) { (result, item) =>
          for
            values <- result
            record <- decodeRecordView(item)
          yield values :+ record
        }
      cursor <- json.optionalField("cursor").left.map(error => ClientError(error.message)).flatMap {
        case None | Some(Json.Null) => Right(None)
        case Some(value)            => value.asString.left.map(error => ClientError(error.message))
            .map(Some.apply)
      }
    yield RecordPage(records, cursor)

  private[client] def decodeRecordView(json: Json): Either[ClientError, RecordView] =
    for
      uriText <- stringField(json, "uri")
      uri <- AtUri.parse(uriText).left.map(error => ClientError(error.toString))
      cidText <- stringField(json, "cid")
      cid <- Cid.parse(cidText).left.map(error => ClientError(error.toString))
      valueJson <- json.field("value").left.map(error => ClientError(error.message))
      value <- DagJson.decode(valueJson).left.map(error => ClientError(error.toString))
    yield RecordView(uri, cid, value)

  private[client] def decodeWrite(json: Json): Either[ClientError, RecordWriteResult] =
    for
      uriText <- stringField(json, "uri")
      uri <- AtUri.parse(uriText).left.map(error => ClientError(error.toString))
      cidText <- stringField(json, "cid")
      cid <- Cid.parse(cidText).left.map(error => ClientError(error.toString))
      commit <- json.optionalField("commit").left.map(error => ClientError(error.message))
      commitCid <- commit match
        case Some(value) => optionalCid(value, "cid")
        case None        => Right(None)
      revision <- commit match
        case Some(value) => optionalString(value, "rev")
        case None        => Right(None)
    yield RecordWriteResult(uri, cid, commitCid, revision)

  private def stringField(json: Json, name: String): Either[ClientError, String] = json.field(name)
    .flatMap(_.asString).left.map(error => ClientError(error.message))

  private def optionalString(json: Json, name: String): Either[ClientError, Option[String]] = json
    .optionalField(name).left.map(error => ClientError(error.message)).flatMap {
      case None | Some(Json.Null) => Right(None)
      case Some(value)            => value.asString.left.map(error => ClientError(error.message))
          .map(Some.apply)
    }

  private def optionalCid(json: Json, name: String): Either[ClientError, Option[Cid]] =
    optionalString(json, name).flatMap {
      case None        => Right(None)
      case Some(value) => Cid.parse(value).left.map(error => ClientError(error.toString))
          .map(Some.apply)
    }

object AtpClient:
  /** Creates a client for an already-known XRPC service origin. */
  def create(
      service: URI,
      transport: HttpTransport = JdkHttpTransport.default
  ): Either[ClientError, AtpClient] = XrpcClient.create(service, transport).left
    .map(error => ClientError(error.description)).map(new AtpClient(service, _))

  /** Resolves an account and creates a client for the PDS in its DID document. */
  def discover(
      identifier: AtIdentifier,
      resolver: IdentityResolver,
      transport: HttpTransport = JdkHttpTransport.default
  ): Either[ClientError, (AtpClient, ResolvedIdentity)] = resolver.resolve(identifier).left
    .map(error => ClientError(error.description))
    .flatMap(identity => create(identity.pds, transport).map(_ -> identity))

/** Legacy-authenticated record mutation client. */
final case class AuthenticatedAtpClient(client: AtpClient, session: LegacySession):
  /**
   * Atomically rotates the legacy refresh token and returns a client carrying the replacement
   * access/refresh pair. The current instance is intentionally immutable; callers must discard it
   * after success because its refresh token has been revoked by the server.
   */
  def refreshSession: Either[ClientError, AuthenticatedAtpClient] = client
    .procedure("com.atproto.server.refreshSession", Json.obj(), session.refreshJwt)
    .flatMap(client.decodeSession).map(replacement => AuthenticatedAtpClient(client, replacement))

  /**
   * Revokes the current access session. A successful response means later use of this access token
   * must fail; it does not delete repository data or the account.
   */
  def revokeSession: Either[ClientError, Unit] = client
    .procedure("com.atproto.server.deleteSession", Json.obj(), session.accessJwt).map(_ => ())

  /** Creates a record; omitting rkey lets the PDS generate a TID. */
  def createRecord(
      collection: Nsid,
      value: Ipld,
      recordKey: Option[RecordKey] = None
  ): Either[ClientError, RecordWriteResult] =
    val input = Json.obj(
      "repo" -> Json.Str(session.did.value),
      "collection" -> Json.Str(collection.value),
      "record" -> DagJson.encode(value)
    ) match
      case Json.Obj(fields) => Json
          .Obj(fields ++ recordKey.map(key => "rkey" -> Json.Str(key.value)))
      case other => other
    client.procedure("com.atproto.repo.createRecord", input, session.accessJwt)
      .flatMap(client.decodeWrite)

  /** Creates or replaces a record at a caller-selected key. */
  def putRecord(
      collection: Nsid,
      recordKey: RecordKey,
      value: Ipld
  ): Either[ClientError, RecordWriteResult] =
    val input = Json.obj(
      "repo" -> Json.Str(session.did.value),
      "collection" -> Json.Str(collection.value),
      "rkey" -> Json.Str(recordKey.value),
      "record" -> DagJson.encode(value)
    )
    client.procedure("com.atproto.repo.putRecord", input, session.accessJwt)
      .flatMap(client.decodeWrite)

  /** Deletes a record from the authenticated account repository. */
  def deleteRecord(collection: Nsid, recordKey: RecordKey): Either[ClientError, Unit] =
    val input = Json.obj(
      "repo" -> Json.Str(session.did.value),
      "collection" -> Json.Str(collection.value),
      "rkey" -> Json.Str(recordKey.value)
    )
    client.procedure("com.atproto.repo.deleteRecord", input, session.accessJwt).map(_ => ())

  /** Reads the current authenticated session state. */
  def getSession: Either[ClientError, LegacySession] = client
    .query("com.atproto.server.getSession", Vector.empty, session.accessJwt).flatMap { json =>
      for
        didText <- json.field("did").flatMap(_.asString).left
          .map(error => ClientError(error.message))
        did <- Did.parse(didText).left.map(error => ClientError(error.toString))
        handleText <- json.field("handle").flatMap(_.asString).left
          .map(error => ClientError(error.message))
        handle <- Handle.parse(handleText).left.map(error => ClientError(error.toString))
      yield session.copy(did = did, handle = handle)
    }

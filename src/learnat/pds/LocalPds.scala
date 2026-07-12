package learnat.pds

import io.undertow.Undertow
import io.undertow.server.HttpHandler
import io.undertow.server.HttpServerExchange
import io.undertow.util.Headers
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.Arrays
import java.util.concurrent.CountDownLatch
import learnat.crypto.P256KeyPair
import learnat.crypto.P256PublicKey
import learnat.ipld.DagJson
import learnat.ipld.Cid
import learnat.ipld.ByteString
import learnat.ipld.Ipld
import learnat.json.Json
import learnat.repo.Repository
import learnat.repo.RepositoryRecord
import learnat.repo.RepositoryWrite
import learnat.syntax.Did
import learnat.syntax.Handle
import learnat.syntax.Nsid
import learnat.syntax.RecordKey
import learnat.syntax.TidGenerator
import learnat.sync.EventBatch
import learnat.sync.RetainedEventLog

/** A startup/configuration failure before the local PDS begins accepting requests. */
final case class LocalPdsError(message: String):
  override def toString: String = message

/** Explicit configuration for the single-account learning PDS. */
final case class LocalPdsConfig(
    handle: Handle,
    password: Array[Char],
    port: Int = 2583,
    bindAddress: String = "127.0.0.1",
    workerThreads: Int = 4,
    maxJsonBodyBytes: Int = 1024 * 1024,
    maxBlobBytes: Int = 5 * 1024 * 1024,
    eventRetention: Int = 1000,
    dataDirectory: Option[Path] = None
)

/**
 * A running single-account PDS bound to a loopback HTTP server.
 *
 * This handle owns the server lifecycle and exposes only public identity material. The repository
 * signing private key stays inside the PDS state.
 */
final class RunningLocalPds private[pds] (
    private val server: Undertow,
    private val state: LocalPdsState,
    val service: URI,
    val did: Did,
    val handle: Handle,
    val signingPublicKey: P256PublicKey
) extends AutoCloseable:
  /** Returns the immutable current repository snapshot for local inspection. */
  def repository: Repository = state.currentRepository

  /** Reads retained canonical events for a local subscriber transport. */
  def eventsAfter(
      cursor: Option[Long],
      limit: Int = 100
  ): Either[learnat.sync.SyncError, EventBatch] = state.eventLog.readAfter(cursor, limit)

  /** Stops accepting requests and interrupts the bounded worker pool. */
  override def close(): Unit =
    server.stop()
    ()

/** Starts the dependency-free local PDS used by the client/server hands-on. */
object LocalPds:
  def start(config: LocalPdsConfig): Either[LocalPdsError, RunningLocalPds] =
    if config.port < 0 || config.port > 65535 then
      Left(LocalPdsError("port must be between 0 and 65535"))
    else if config.workerThreads < 1 then Left(LocalPdsError("workerThreads must be positive"))
    else if config.maxJsonBodyBytes < 1 then
      Left(LocalPdsError("maxJsonBodyBytes must be positive"))
    else if config.maxBlobBytes < 1 then Left(LocalPdsError("maxBlobBytes must be positive"))
    else if config.eventRetention < 1 then Left(LocalPdsError("eventRetention must be positive"))
    else
      var server: Option[Undertow] = None
      try
        val routes = new LocalPdsRoutes(config.maxJsonBodyBytes, config.maxBlobBytes)
        val createdServer = Undertow.builder().addHttpListener(config.port, config.bindAddress)
          .setWorkerThreads(config.workerThreads).setHandler(new UndertowPdsHandler(routes)).build()
        createdServer.start()
        server = Some(createdServer)
        val actualPort = createdServer.getListenerInfo.get(0).getAddress
          .asInstanceOf[InetSocketAddress].getPort
        val service = URI.create(s"http://localhost:$actualPort")
        val passwordCopy = config.password.clone()
        val passwordHash =
          try PasswordHash.create(passwordCopy)
          finally Arrays.fill(passwordCopy, '\u0000')
        val store = config.dataDirectory.map(LocalPdsStore(_))
        val blobStore: BlobStore = config.dataDirectory
          .map(directory => FileBlobStore(directory.resolve("blobs"), config.maxBlobBytes))
          .getOrElse(MemoryBlobStore(config.maxBlobBytes))
        val initialized =
          for
            did <- Did.parse(s"did:web:localhost%3A$actualPort").left
              .map(error => LocalPdsError(error.toString))
            restored <- store
              .fold[Either[LocalPdsError, Option[StoredPdsState]]](Right(None))(_.load(did))
            signingKey <- restored match
              case Some(value) => Right(value.signingKey)
              case None => P256KeyPair.generate().left.map(error => LocalPdsError(error.toString))
            verifiedPassword <- passwordHash.left.map(error => LocalPdsError(error.toString))
            initialRecords = restored.fold(Vector.empty[RepositoryRecord])(_.records)
            previousRevision = restored.map(_.lastRevision)
            repository <- Repository
              .create(did, signingKey, TidGenerator.system(), initialRecords, previousRevision).left
              .map(error => LocalPdsError(error.toString))
            eventLog <- RetainedEventLog.create(config.eventRetention).left
              .map(error => LocalPdsError(error.message))
            _ <- store
              .fold[Either[LocalPdsError, Unit]](Right(()))(_.save(did, signingKey, repository))
          yield (did, signingKey, verifiedPassword, repository, store, eventLog)
        initialized.map { (did, signingKey, verifiedPassword, repository, store, eventLog) =>
          val state = LocalPdsState(
            did,
            config.handle,
            service,
            signingKey,
            verifiedPassword,
            SessionStore.secure(),
            TidGenerator.system(),
            repository,
            store,
            blobStore,
            eventLog
          )
          routes.attach(state)
          new RunningLocalPds(
            createdServer,
            state,
            service,
            did,
            config.handle,
            signingKey.publicKey
          )
        }.left.map { error =>
          createdServer.stop()
          error
        }
      catch
        case error: Exception =>
          server.foreach(_.stop())
          Left(LocalPdsError(s"failed to start local PDS: ${error.getMessage}"))

final private class LocalPdsState(
    val did: Did,
    val handle: Handle,
    val service: URI,
    val signingKey: P256KeyPair,
    val passwordHash: PasswordHash,
    val sessions: SessionStore,
    val recordKeyGenerator: TidGenerator,
    private var repositoryState: Repository,
    val store: Option[LocalPdsStore],
    val blobStore: BlobStore,
    val eventLog: RetainedEventLog
):
  def currentRepository: Repository = synchronized(repositoryState)

  def applyWrite(write: RepositoryWrite): Either[PdsFailure, Repository] = synchronized {
    for
      updated <- repositoryState.applyWrite(write).left
        .map(error => PdsFailure(400, "InvalidRequest", error.message))
      body <- commitEvent(repositoryState, updated, write)
      event <- eventLog.append("#commit", body).left
        .map(error => PdsFailure(500, "InternalServerError", error.message))
      _ <- persistOrRollback(updated, event.sequence)
    yield
      repositoryState = updated
      updated
  }

  private def persistOrRollback(updated: Repository, sequence: Long): Either[PdsFailure, Unit] =
    store.fold[Either[PdsFailure, Unit]](Right(()))(_.save(did, signingKey, updated).left.map(
      error => PdsFailure(500, "InternalServerError", error.message)
    )) match
      case success @ Right(_) => success
      case Left(failure)      => eventLog.rollbackLast(sequence) match
          case Right(_)       => Left(failure)
          case Left(rollback) => Left(PdsFailure(
              500,
              "InternalServerError",
              s"${failure.message}; event rollback failed: ${rollback.message}"
            ))

  private def commitEvent(
      previous: Repository,
      updated: Repository,
      write: RepositoryWrite
  ): Either[PdsFailure, Ipld] =
    val (action, collection, recordKey) = write match
      case RepositoryWrite.Create(record)     => ("create", record.collection, record.recordKey)
      case RepositoryWrite.Put(record)        => ("update", record.collection, record.recordKey)
      case RepositoryWrite.Delete(value, key) => ("delete", value, key)
    val cid = updated.reference(collection, recordKey).map(_._2)
    updated.exportCar.left.map(error => PdsFailure(500, "InternalServerError", error.message))
      .map { car =>
        Ipld.obj(
          "repo" -> Ipld.Text(did.value),
          "commit" -> Ipld.Link(updated.commitCid),
          "rev" -> Ipld.Text(updated.commit.rev.value),
          "since" -> Ipld.Text(previous.commit.rev.value),
          "rebase" -> Ipld.Bool(false),
          "tooBig" -> Ipld.Bool(false),
          "blocks" -> Ipld.Bytes(ByteString(car)),
          "ops" -> Ipld.list(Ipld.obj(
            "action" -> Ipld.Text(action),
            "path" -> Ipld.Text(s"${collection.value}/${recordKey.value}"),
            "cid" -> cid.fold[Ipld](Ipld.Null)(Ipld.Link.apply)
          ))
        )
      }

private object LocalPdsState:
  def apply(
      did: Did,
      handle: Handle,
      service: URI,
      signingKey: P256KeyPair,
      passwordHash: PasswordHash,
      sessions: SessionStore,
      recordKeyGenerator: TidGenerator,
      repository: Repository,
      store: Option[LocalPdsStore],
      blobStore: BlobStore,
      eventLog: RetainedEventLog
  ): LocalPdsState = new LocalPdsState(
    did,
    handle,
    service,
    signingKey,
    passwordHash,
    sessions,
    recordKeyGenerator,
    repository,
    store,
    blobStore,
    eventLog
  )

final private case class PdsFailure(status: Int, code: String, message: String)
final private case class PdsResponse(status: Int, contentType: String, bytes: Array[Byte])

private object PdsResponse:
  def json(value: Json, status: Int = 200): PdsResponse = PdsResponse(
    status,
    "application/json; charset=utf-8",
    value.render.getBytes(StandardCharsets.UTF_8)
  )

  def text(value: String, contentType: String = "text/plain; charset=utf-8"): PdsResponse =
    PdsResponse(200, contentType, value.getBytes(StandardCharsets.UTF_8))

  def binary(bytes: Array[Byte], contentType: String): PdsResponse =
    PdsResponse(200, contentType, bytes)

private trait PdsHttpExchange:
  def method: String
  def uri: URI
  def requestHeader(name: String): Option[String]
  def readBody(maximumBytes: Int): Array[Byte]
  def respond(status: Int, contentType: String, bytes: Array[Byte]): Unit
  def close(): Unit

final private class UndertowPdsHttpExchange(exchange: HttpServerExchange) extends PdsHttpExchange:
  def method: String = exchange.getRequestMethod.toString
  def uri: URI =
    val query = Option(exchange.getQueryString).filter(_.nonEmpty).fold("")(value => s"?$value")
    URI.create(s"${exchange.getRequestURI}$query")
  def requestHeader(name: String): Option[String] =
    Option(exchange.getRequestHeaders.getFirst(name))
  def readBody(maximumBytes: Int): Array[Byte] =
    exchange.startBlocking()
    exchange.getInputStream.readNBytes(maximumBytes)
  def respond(status: Int, contentType: String, bytes: Array[Byte]): Unit =
    exchange.setStatusCode(status)
    exchange.getResponseHeaders.put(Headers.CONTENT_TYPE, contentType)
    exchange.getResponseHeaders.put(Headers.CACHE_CONTROL, "no-store")
    exchange.startBlocking()
    exchange.getOutputStream.write(bytes)
  def close(): Unit = exchange.endExchange()

final private class UndertowPdsHandler(routes: LocalPdsRoutes) extends HttpHandler:
  override def handleRequest(exchange: HttpServerExchange): Unit =
    if exchange.isInIoThread then exchange.dispatch(this)
    else routes.handle(new UndertowPdsHttpExchange(exchange))

final private class LocalPdsRoutes(maxJsonBodyBytes: Int, maxBlobBytes: Int):
  @volatile
  private var stateValue: Option[LocalPdsState] = None

  def attach(state: LocalPdsState): Unit = stateValue = Some(state)
  private def state: LocalPdsState = stateValue
    .getOrElse(throw IllegalStateException("local PDS routes are not initialized"))

  def handle(exchange: PdsHttpExchange): Unit =
    try
      val response = route(exchange) match
        case Right(value)  => value
        case Left(failure) => PdsResponse.json(
            Json.obj("error" -> Json.Str(failure.code), "message" -> Json.Str(failure.message)),
            failure.status
          )
      send(exchange, response)
    catch
      case error: Exception =>
        error.printStackTrace(Console.err)
        send(
          exchange,
          PdsResponse.json(
            Json.obj(
              "error" -> Json.Str("InternalServerError"),
              "message" -> Json.Str("unexpected local PDS error")
            ),
            500
          )
        )
    finally exchange.close()

  private def route(exchange: PdsHttpExchange): Either[PdsFailure, PdsResponse] =
    exchange.uri.getPath match
      case "/.well-known/did.json" =>
        withMethod(exchange, "GET")(Right(PdsResponse.json(didDocument)))
      case "/.well-known/atproto-did" =>
        withMethod(exchange, "GET")(Right(PdsResponse.text(state.did.value)))
      case "/xrpc/com.atproto.server.describeServer"  => withMethod(exchange, "GET")(describeServer)
      case "/xrpc/com.atproto.identity.resolveHandle" =>
        withMethod(exchange, "GET")(resolveHandle(exchange))
      case "/xrpc/com.atproto.server.createSession" =>
        withMethod(exchange, "POST")(createSession(exchange))
      case "/xrpc/com.atproto.server.getSession" =>
        withMethod(exchange, "GET")(getSession(exchange))
      case "/xrpc/com.atproto.server.refreshSession" =>
        withMethod(exchange, "POST")(refreshSession(exchange))
      case "/xrpc/com.atproto.server.deleteSession" =>
        withMethod(exchange, "POST")(deleteSession(exchange))
      case "/xrpc/com.atproto.repo.getRecord"   => withMethod(exchange, "GET")(getRecord(exchange))
      case "/xrpc/com.atproto.repo.listRecords" =>
        withMethod(exchange, "GET")(listRecords(exchange))
      case "/xrpc/com.atproto.repo.createRecord" =>
        withMethod(exchange, "POST")(createRecord(exchange))
      case "/xrpc/com.atproto.repo.putRecord" => withMethod(exchange, "POST")(putRecord(exchange))
      case "/xrpc/com.atproto.repo.deleteRecord" =>
        withMethod(exchange, "POST")(deleteRecord(exchange))
      case "/xrpc/com.atproto.repo.uploadBlob" => withMethod(exchange, "POST")(uploadBlob(exchange))
      case "/xrpc/com.atproto.repo.describeRepo" =>
        withMethod(exchange, "GET")(describeRepo(exchange))
      case "/xrpc/com.atproto.sync.getRepo" => withMethod(exchange, "GET")(getRepo(exchange))
      case "/xrpc/com.atproto.sync.getLatestCommit" =>
        withMethod(exchange, "GET")(getLatestCommit(exchange))
      case "/xrpc/com.atproto.sync.getBlob" => withMethod(exchange, "GET")(getBlob(exchange))
      case _ => Left(PdsFailure(404, "MethodNotFound", "unknown local PDS endpoint"))

  private def describeServer: Either[PdsFailure, PdsResponse] = Right(PdsResponse.json(Json.obj(
    "did" -> Json.Str(state.did.value),
    "availableUserDomains" -> Json.Arr(Vector.empty),
    "inviteCodeRequired" -> Json.Bool(false)
  )))

  private def resolveHandle(exchange: PdsHttpExchange): Either[PdsFailure, PdsResponse] =
    for
      handle <- queryRequired(exchange, "handle")
      parsed <- Handle.parse(handle).left
        .map(error => PdsFailure(400, "InvalidRequest", error.toString))
      _ <- Either.cond(
        parsed.normalized == state.handle.normalized,
        (),
        PdsFailure(400, "HandleNotFound", "handle is not hosted here")
      )
    yield PdsResponse.json(Json.obj("did" -> Json.Str(state.did.value)))

  private def createSession(exchange: PdsHttpExchange): Either[PdsFailure, PdsResponse] =
    for
      body <- jsonBody(exchange)
      identifier <- stringField(body, "identifier")
      _ <- Either.cond(
        matchesAccount(identifier),
        (),
        PdsFailure(401, "AuthenticationRequired", "invalid credentials")
      )
      password <- stringField(body, "password")
      passwordChars = password.toCharArray
      valid =
        try state.passwordHash.verify(passwordChars)
        finally Arrays.fill(passwordChars, '\u0000')
      _ <- Either.cond(valid, (), PdsFailure(401, "AuthenticationRequired", "invalid credentials"))
      tokens = state.sessions.issue(state.did)
    yield PdsResponse.json(sessionJson(tokens))

  private def getSession(exchange: PdsHttpExchange): Either[PdsFailure, PdsResponse] =
    authorizeAccess(exchange).map(_ =>
      PdsResponse.json(Json.obj(
        "did" -> Json.Str(state.did.value),
        "handle" -> Json.Str(state.handle.normalized),
        "active" -> Json.Bool(true)
      ))
    )

  private def refreshSession(exchange: PdsHttpExchange): Either[PdsFailure, PdsResponse] =
    for
      token <- bearer(exchange)
      tokens <- state.sessions.refresh(token).left
        .map(error => PdsFailure(401, "ExpiredToken", error.message))
    yield PdsResponse.json(sessionJson(tokens))

  private def deleteSession(exchange: PdsHttpExchange): Either[PdsFailure, PdsResponse] =
    for
      token <- bearer(exchange)
      _ <- state.sessions.revoke(token).left
        .map(error => PdsFailure(401, "InvalidToken", error.message))
    yield PdsResponse.json(Json.obj())

  private def uploadBlob(exchange: PdsHttpExchange): Either[PdsFailure, PdsResponse] =
    for
      _ <- authorizeAccess(exchange)
      mime <- exchange.requestHeader("Content-Type")
        .toRight(PdsFailure(415, "InvalidRequest", "Content-Type is required"))
      bytes = exchange.readBody(maxBlobBytes + 1)
      _ <- Either.cond(
        bytes.length <= maxBlobBytes,
        (),
        PdsFailure(413, "PayloadTooLarge", s"blob exceeds $maxBlobBytes bytes")
      )
      stored <- state.blobStore.put(mime, bytes).left
        .map(error => PdsFailure(400, "InvalidRequest", error.message))
    yield PdsResponse.json(Json.obj(
      "blob" -> Json.obj(
        "$type" -> Json.Str("blob"),
        "ref" -> Json.obj("$link" -> Json.Str(stored.cid.toString)),
        "mimeType" -> Json.Str(stored.mimeType),
        "size" -> Json.Num(stored.size)
      )
    ))

  private def getBlob(exchange: PdsHttpExchange): Either[PdsFailure, PdsResponse] =
    for
      _ <- validateRepoQuery(exchange, "did")
      cidText <- queryRequired(exchange, "cid")
      cid <- Cid.parse(cidText).left.map(error => PdsFailure(400, "InvalidRequest", error.toString))
      content <- state.blobStore.get(cid).left
        .map(error => PdsFailure(500, "InternalServerError", error.message))
      blob <- content.toRight(PdsFailure(404, "BlobNotFound", "blob does not exist"))
    yield PdsResponse.binary(blob.bytes, blob.metadata.mimeType)

  private def getRecord(exchange: PdsHttpExchange): Either[PdsFailure, PdsResponse] =
    for
      parameters <- repositoryQuery(exchange, requireRecordKey = true)
      (collection, recordKey) = parameters
      repository = state.currentRepository
      record <- repository.get(collection, recordKey)
        .toRight(PdsFailure(400, "RecordNotFound", "record does not exist"))
      view <- recordJson(repository, record)
    yield PdsResponse.json(view)

  private def listRecords(exchange: PdsHttpExchange): Either[PdsFailure, PdsResponse] =
    for
      _ <- validateRepoQuery(exchange, "repo")
      collectionText <- queryRequired(exchange, "collection")
      collection <- Nsid.parse(collectionText).left
        .map(error => PdsFailure(400, "InvalidRequest", error.toString))
      limit <- query(exchange).get("limit").flatMap(_.headOption) match
        case None        => Right(50)
        case Some(value) => value.toIntOption.filter(number => number >= 1 && number <= 100)
            .toRight(PdsFailure(400, "InvalidRequest", "limit must be between 1 and 100"))
      reverse <- query(exchange).get("reverse").flatMap(_.headOption) match
        case None | Some("false") => Right(false)
        case Some("true")         => Right(true)
        case Some(_) => Left(PdsFailure(400, "InvalidRequest", "reverse must be true or false"))
      cursor = query(exchange).get("cursor").flatMap(_.headOption)
      repository = state.currentRepository
      matching = repository.records.filter(_.collection == collection).sortBy(_.recordKey.value)
      ordered = if reverse then matching.reverse else matching
      afterCursor = cursor.fold(ordered)(value =>
        ordered.filter(record =>
          if reverse then record.recordKey.value < value else record.recordKey.value > value
        )
      )
      selected = afterCursor.take(limit)
      encoded <- selected
        .foldLeft[Either[PdsFailure, Vector[Json]]](Right(Vector.empty)) { (result, record) =>
          for
            records <- result
            value <- recordJson(repository, record)
          yield records :+ value
        }
      nextCursor =
        if afterCursor.length > limit then selected.lastOption.map(_.recordKey.value) else None
      fields = Vector("records" -> Json.Arr(encoded)) ++
        nextCursor.map(value => "cursor" -> Json.Str(value))
    yield PdsResponse.json(Json.Obj(fields))

  private def createRecord(exchange: PdsHttpExchange): Either[PdsFailure, PdsResponse] =
    for
      _ <- authorizeAccess(exchange)
      body <- jsonBody(exchange)
      record <- decodeRecordInput(body, requireRecordKey = false)
      key = record._2.getOrElse(RecordKey.parse(state.recordKeyGenerator.next().value).toOption.get)
      repositoryRecord = RepositoryRecord(record._1, key, record._3)
      repository <- state.applyWrite(RepositoryWrite.Create(repositoryRecord))
      response <- writeResponse(repository, repositoryRecord)
    yield PdsResponse.json(response)

  private def putRecord(exchange: PdsHttpExchange): Either[PdsFailure, PdsResponse] =
    for
      _ <- authorizeAccess(exchange)
      body <- jsonBody(exchange)
      record <- decodeRecordInput(body, requireRecordKey = true)
      key <- record._2.toRight(PdsFailure(400, "InvalidRequest", "rkey is required"))
      repositoryRecord = RepositoryRecord(record._1, key, record._3)
      repository <- state.applyWrite(RepositoryWrite.Put(repositoryRecord))
      response <- writeResponse(repository, repositoryRecord)
    yield PdsResponse.json(response)

  private def deleteRecord(exchange: PdsHttpExchange): Either[PdsFailure, PdsResponse] =
    for
      _ <- authorizeAccess(exchange)
      body <- jsonBody(exchange)
      _ <- validateRepoField(body, "repo")
      collection <- parseCollection(body)
      recordKey <- parseRecordKey(body, required = true)
        .flatMap(_.toRight(PdsFailure(400, "InvalidRequest", "rkey is required")))
      _ <- state.applyWrite(RepositoryWrite.Delete(collection, recordKey))
    yield PdsResponse.json(Json.obj())

  private def describeRepo(exchange: PdsHttpExchange): Either[PdsFailure, PdsResponse] =
    for
      _ <- validateRepoQuery(exchange, "repo")
      repository = state.currentRepository
      collections = repository.records.map(_.collection.value).distinct.sorted.map(Json.Str.apply)
    yield PdsResponse.json(Json.obj(
      "handle" -> Json.Str(state.handle.normalized),
      "did" -> Json.Str(state.did.value),
      "didDoc" -> didDocument,
      "collections" -> Json.Arr(collections),
      "handleIsCorrect" -> Json.Bool(true)
    ))

  private def getRepo(exchange: PdsHttpExchange): Either[PdsFailure, PdsResponse] =
    for
      _ <- validateRepoQuery(exchange, "did")
      bytes <- state.currentRepository.exportCar.left
        .map(error => PdsFailure(500, "InternalServerError", error.message))
    yield PdsResponse(200, "application/vnd.ipld.car", bytes)

  private def getLatestCommit(exchange: PdsHttpExchange): Either[PdsFailure, PdsResponse] =
    for
      _ <- validateRepoQuery(exchange, "did")
      repository = state.currentRepository
    yield PdsResponse.json(Json.obj(
      "cid" -> Json.Str(repository.commitCid.toString),
      "rev" -> Json.Str(repository.commit.rev.value)
    ))

  private def repositoryQuery(
      exchange: PdsHttpExchange,
      requireRecordKey: Boolean
  ): Either[PdsFailure, (Nsid, RecordKey)] =
    for
      _ <- validateRepoQuery(exchange, "repo")
      collectionText <- queryRequired(exchange, "collection")
      collection <- Nsid.parse(collectionText).left
        .map(error => PdsFailure(400, "InvalidRequest", error.toString))
      recordKeyText <- if requireRecordKey then queryRequired(exchange, "rkey") else Right("")
      recordKey <- RecordKey.parse(recordKeyText).left
        .map(error => PdsFailure(400, "InvalidRequest", error.toString))
    yield collection -> recordKey

  private def decodeRecordInput(
      body: Json,
      requireRecordKey: Boolean
  ): Either[PdsFailure, (Nsid, Option[RecordKey], learnat.ipld.Ipld)] =
    for
      _ <- validateRepoField(body, "repo")
      collection <- parseCollection(body)
      recordKey <- parseRecordKey(body, requireRecordKey)
      recordJson <- body.field("record").left
        .map(error => PdsFailure(400, "InvalidRequest", error.message))
      record <- DagJson.decode(recordJson).left
        .map(error => PdsFailure(400, "InvalidRequest", error.toString))
    yield (collection, recordKey, record)

  private def parseCollection(body: Json): Either[PdsFailure, Nsid] =
    stringField(body, "collection").flatMap(value =>
      Nsid.parse(value).left.map(error => PdsFailure(400, "InvalidRequest", error.toString))
    )

  private def parseRecordKey(body: Json, required: Boolean): Either[PdsFailure, Option[RecordKey]] =
    body.optionalField("rkey").left.map(error => PdsFailure(400, "InvalidRequest", error.message))
      .flatMap {
        case None if !required => Right(None)
        case None              => Left(PdsFailure(400, "InvalidRequest", "rkey is required"))
        case Some(value)       => value.asString.left
            .map(error => PdsFailure(400, "InvalidRequest", error.message)).flatMap(text =>
              RecordKey.parse(text).left
                .map(error => PdsFailure(400, "InvalidRequest", error.toString))
            ).map(Some.apply)
      }

  private def writeResponse(
      repository: Repository,
      record: RepositoryRecord
  ): Either[PdsFailure, Json] = repository.reference(record.collection, record.recordKey)
    .toRight(PdsFailure(500, "InternalServerError", "committed record is missing"))
    .map { (uri, cid) =>
      Json.obj(
        "uri" -> Json.Str(uri.toString),
        "cid" -> Json.Str(cid.toString),
        "commit" -> Json.obj(
          "cid" -> Json.Str(repository.commitCid.toString),
          "rev" -> Json.Str(repository.commit.rev.value)
        )
      )
    }

  private def recordJson(
      repository: Repository,
      record: RepositoryRecord
  ): Either[PdsFailure, Json] = repository.reference(record.collection, record.recordKey)
    .toRight(PdsFailure(500, "InternalServerError", "record CID is missing")).map { (uri, cid) =>
      Json.obj(
        "uri" -> Json.Str(uri.toString),
        "cid" -> Json.Str(cid.toString),
        "value" -> DagJson.encode(record.value)
      )
    }

  private def didDocument: Json = Json.obj(
    "@context" -> Json.Arr(Vector(Json.Str("https://www.w3.org/ns/did/v1"))),
    "id" -> Json.Str(state.did.value),
    "alsoKnownAs" -> Json.Arr(Vector(Json.Str(s"at://${state.handle.normalized}"))),
    "verificationMethod" -> Json.Arr(Vector(Json.obj(
      "id" -> Json.Str(s"${state.did.value}#atproto"),
      "type" -> Json.Str("Multikey"),
      "controller" -> Json.Str(state.did.value),
      "publicKeyMultibase" -> Json.Str(state.signingKey.publicKey.multikey)
    ))),
    "service" -> Json.Arr(Vector(Json.obj(
      "id" -> Json.Str("#atproto_pds"),
      "type" -> Json.Str("AtprotoPersonalDataServer"),
      "serviceEndpoint" -> Json.Str(state.service.toString)
    )))
  )

  private def sessionJson(tokens: SessionTokens): Json = Json.obj(
    "accessJwt" -> Json.Str(tokens.accessJwt),
    "refreshJwt" -> Json.Str(tokens.refreshJwt),
    "handle" -> Json.Str(state.handle.normalized),
    "did" -> Json.Str(state.did.value),
    "didDoc" -> didDocument,
    "active" -> Json.Bool(true)
  )

  private def authorizeAccess(exchange: PdsHttpExchange): Either[PdsFailure, Did] = bearer(exchange)
    .flatMap(token =>
      state.sessions.verifyAccess(token).left
        .map(error => PdsFailure(401, "AuthenticationRequired", error.message))
    )

  private def bearer(exchange: PdsHttpExchange): Either[PdsFailure, String] = exchange
    .requestHeader("Authorization") match
    case Some(value) if value.startsWith("Bearer ") && value.length > 7 => Right(value.drop(7))
    case _ => Left(PdsFailure(401, "AuthenticationRequired", "missing bearer token"))

  private def validateRepoField(json: Json, name: String): Either[PdsFailure, Unit] = stringField(
    json,
    name
  ).flatMap(value =>
    Either.cond(
      matchesAccount(value),
      (),
      PdsFailure(400, "RepoNotFound", "repository is not hosted here")
    )
  )

  private def validateRepoQuery(exchange: PdsHttpExchange, name: String): Either[PdsFailure, Unit] =
    queryRequired(exchange, name).flatMap(value =>
      Either.cond(
        matchesAccount(value),
        (),
        PdsFailure(400, "RepoNotFound", "repository is not hosted here")
      )
    )

  private def matchesAccount(value: String): Boolean = value == state.did.value ||
    value.equalsIgnoreCase(state.handle.normalized)

  private def stringField(json: Json, name: String): Either[PdsFailure, String] = json.field(name)
    .flatMap(_.asString).left.map(error => PdsFailure(400, "InvalidRequest", error.message))

  private def jsonBody(exchange: PdsHttpExchange): Either[PdsFailure, Json] =
    val contentType = exchange.requestHeader("Content-Type").getOrElse("")
    if !contentType.toLowerCase(java.util.Locale.ROOT).startsWith("application/json") then
      Left(PdsFailure(415, "InvalidRequest", "Content-Type must be application/json"))
    else
      val bytes = exchange.readBody(maxJsonBodyBytes + 1)
      if bytes.length > maxJsonBodyBytes then
        Left(PdsFailure(413, "PayloadTooLarge", s"JSON body exceeds $maxJsonBodyBytes bytes"))
      else
        Json.parse(String(bytes, StandardCharsets.UTF_8)).left
          .map(error => PdsFailure(400, "InvalidRequest", error.toString))

  private def queryRequired(exchange: PdsHttpExchange, name: String): Either[PdsFailure, String] =
    query(exchange).get(name).flatMap(_.headOption).filter(_.nonEmpty)
      .toRight(PdsFailure(400, "InvalidRequest", s"missing query parameter: $name"))

  private def query(exchange: PdsHttpExchange): Map[String, Vector[String]] =
    Option(exchange.uri.getRawQuery).toVector.flatMap(_.split("&", -1)).filter(_.nonEmpty)
      .foldLeft(Map.empty[String, Vector[String]]) { (parameters, pair) =>
        val parts = pair.split("=", 2)
        val name = decodeQuery(parts.head)
        val value = decodeQuery(parts.lift(1).getOrElse(""))
        parameters.updated(name, parameters.getOrElse(name, Vector.empty) :+ value)
      }

  private def decodeQuery(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8)

  private def withMethod(exchange: PdsHttpExchange, expected: String)(
      result: => Either[PdsFailure, PdsResponse]
  ): Either[PdsFailure, PdsResponse] =
    if exchange.method == expected then result
    else Left(PdsFailure(405, "MethodNotAllowed", s"expected $expected"))

  private def send(exchange: PdsHttpExchange, response: PdsResponse): Unit = exchange
    .respond(response.status, response.contentType, response.bytes)

object LocalPdsMain:
  /** Starts the learning PDS and waits until the process receives a shutdown signal. */
  def main(args: Array[String]): Unit =
    val port = args.headOption.flatMap(_.toIntOption).getOrElse(2583)
    val password = sys.env.getOrElse("LEARN_AT_PASSWORD", "change-me-in-the-environment")
      .toCharArray
    val dataDirectory = Path.of(sys.env.getOrElse("LEARN_AT_DATA", "data/local-pds"))
    val handle = Handle.parse(sys.env.getOrElse("LEARN_AT_HANDLE", "alice.test"))
      .fold(error => throw IllegalArgumentException(error.toString), identity)
    val running = LocalPds
      .start(LocalPdsConfig(handle, password, port, dataDirectory = Some(dataDirectory)))
      .fold(error => throw IllegalStateException(error.toString), identity)
    Arrays.fill(password, '\u0000')
    println(s"Local PDS listening at ${running.service}")
    println(s"DID: ${running.did.value}")
    Runtime.getRuntime.addShutdownHook(Thread(() => running.close()))
    CountDownLatch(1).await()

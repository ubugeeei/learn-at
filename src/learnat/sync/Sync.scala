package learnat.sync

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import learnat.client.AtpClient
import learnat.client.ClientError
import learnat.crypto.P256PublicKey
import learnat.ipld.DagCbor
import learnat.ipld.Ipld
import learnat.repo.RepositoryRecord
import learnat.repo.RepositoryVerifier
import learnat.syntax.Did
import learnat.syntax.Tid

/** A failure while decoding, transporting, or authenticating synchronized data. */
final case class SyncError(message: String):
  override def toString: String = message

/** Materialized verified state for one mirrored account repository. */
final case class MirrorSnapshot(
    did: Did,
    commitCid: learnat.ipld.Cid,
    revision: Tid,
    records: Vector[RepositoryRecord]
)

/** Result of one idempotent repository mirror pass. */
enum SyncResult:
  case Unchanged(snapshot: MirrorSnapshot)
  case Updated(previous: Option[MirrorSnapshot], snapshot: MirrorSnapshot)

/**
 * Correctness-first account mirror using full verified repository exports.
 *
 * Polling is less efficient than applying firehose diffs, but it is the same recovery operation
 * required after a cursor gap. State changes only after a complete CAR, commit signature, MST, and
 * record verification succeeds.
 */
final class RepositoryMirror(
    client: AtpClient,
    did: Did,
    signingKey: P256PublicKey,
    nowMicros: () => Long = () => System.currentTimeMillis() * 1000L,
    allowedFutureDrift: Duration = Duration.ofMinutes(5)
):
  private var current: Option[MirrorSnapshot] = None

  /** Returns the last fully verified snapshot, never a partially applied one. */
  def snapshot: Option[MirrorSnapshot] = synchronized(current)

  /** Checks the PDS head and performs a full authenticated resync when changed. */
  def syncOnce(): Either[SyncError, SyncResult] = synchronized {
    for
      latest <- client.getLatestCommit(did).left.map(fromClient)
      _ <- validateTime(latest.revision)
      result <- current match
        case Some(snapshot)
            if snapshot.commitCid == latest.cid && snapshot.revision == latest.revision =>
          Right(SyncResult.Unchanged(snapshot))
        case previous =>
          for
            car <- client.getRepo(did).left.map(fromClient)
            verified <- RepositoryVerifier.verifyCar(car, did, signingKey).left
              .map(error => SyncError(error.message))
            _ <- Either.cond(
              !latest.revision.newerThan(verified.commit.rev),
              (),
              SyncError(s"repository export revision ${verified.commit.rev
                  .value} is older than advertised ${latest.revision.value}")
            )
            _ <- validateTime(verified.commit.rev)
            next = MirrorSnapshot(did, verified.commitCid, verified.commit.rev, verified.records)
          yield
            current = Some(next)
            SyncResult.Updated(previous, next)
    yield result
  }

  private def validateTime(revision: Tid): Either[SyncError, Unit] =
    val maximum = nowMicros() + allowedFutureDrift.toNanos / 1000L
    Either.cond(
      revision.timestampMicros <= maximum,
      (),
      SyncError(s"repository revision ${revision.value} is too far in the future")
    )

  private def fromClient(error: ClientError): SyncError = SyncError(error.message)

/** Header/body pair from the atproto event-stream CBOR framing. */
enum EventFrame:
  case Message(eventType: String, body: Ipld)
  case Error(name: String, message: Option[String])

/** Canonical event-stream frame decoder, independent from WebSocket transport. */
object EventStreamCodec:
  /** Decodes exactly two concatenated DAG-CBOR objects: header then body. */
  def decode(bytes: Array[Byte]): Either[SyncError, EventFrame] = DagCbor
    .decodeSequence(bytes, DagCbor.Limits(maxBytes = 5 * 1024 * 1024), maxItems = 2).left
    .map(error => SyncError(error.toString)).flatMap {
      case Vector(Ipld.Map(headerFields), body) =>
        val header = headerFields.toMap
        header.get("op") match
          case Some(Ipld.Integer(1)) => header.get("t") match
              case Some(Ipld.Text(eventType)) if eventType.startsWith("#") =>
                Right(EventFrame.Message(eventType, body))
              case _ => Left(SyncError("event message header requires a #type string"))
          case Some(Ipld.Integer(-1)) => body match
              case Ipld.Map(fields) =>
                val values = fields.toMap
                values.get("error") match
                  case Some(Ipld.Text(name)) =>
                    val message = values.get("message").collect { case Ipld.Text(value) => value }
                    Right(EventFrame.Error(name, message))
                  case _ => Left(SyncError("event error body requires an error string"))
              case _ => Left(SyncError("event error body must be a map"))
          case Some(Ipld.Integer(other)) => Left(SyncError(s"unsupported event op $other"))
          case _                         => Left(SyncError("event header requires an integer op"))
      case values =>
        Left(SyncError(s"event frame must contain exactly header and body, found ${values
            .length} values"))
    }

/**
 * JDK WebSocket consumer for a `com.atproto.sync.subscribeRepos` endpoint. Binary fragments are
 * assembled up to the protocol's 5 MiB frame limit and decoded before delivery to the callback.
 */
object FirehoseClient:
  def connect(service: URI, cursor: Option[Long] = None)(
      onFrame: Either[SyncError, EventFrame] => Unit
  ): Either[SyncError, CompletableFuture[WebSocket]] = service.getScheme match
    case "https" | "http" | "wss" | "ws" =>
      val scheme =
        if service.getScheme == "https" then "wss"
        else if service.getScheme == "http" then "ws"
        else service.getScheme
      val query = cursor.fold("")(value => s"?cursor=$value")
      val authority = service.getRawAuthority
      if authority == null then Left(SyncError("firehose service must include an authority"))
      else
        val uri = URI.create(s"$scheme://$authority/xrpc/com.atproto.sync.subscribeRepos$query")
        val listener = FirehoseListener(onFrame)
        val future = HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(uri, listener)
        Right(future)
    case other => Left(SyncError(s"unsupported firehose service scheme: $other"))

final private class FirehoseListener(onFrame: Either[SyncError, EventFrame] => Unit)
    extends WebSocket.Listener:
  private val buffer = ByteArrayOutputStream()
  private val MaxFrameBytes = 5 * 1024 * 1024

  override def onOpen(webSocket: WebSocket): Unit = webSocket.request(1)

  override def onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage[?] =
    synchronized {
      val bytes = Array.ofDim[Byte](data.remaining())
      data.get(bytes)
      if buffer.size() + bytes.length > MaxFrameBytes then
        onFrame(Left(SyncError(s"firehose frame exceeds $MaxFrameBytes bytes")))
        buffer.reset()
        webSocket.abort()
      else
        buffer.write(bytes)
        if last then
          onFrame(EventStreamCodec.decode(buffer.toByteArray))
          buffer.reset()
        webSocket.request(1)
      CompletableFuture.completedFuture(null)
    }

  override def onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage[?] =
    val _ = data
    val _ = last
    onFrame(Left(SyncError("firehose sent an unexpected text frame")))
    webSocket.abort()
    CompletableFuture.completedFuture(null)

  override def onError(webSocket: WebSocket, error: Throwable): Unit =
    val _ = webSocket
    onFrame(Left(SyncError(s"firehose WebSocket failed: ${error.getMessage}")))

  override def onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage[?] =
    val _ = webSocket
    if statusCode != WebSocket.NORMAL_CLOSURE then
      onFrame(Left(SyncError(s"firehose closed with $statusCode: $reason")))
    CompletableFuture.completedFuture(null)

private object FirehoseListener:
  def apply(onFrame: Either[SyncError, EventFrame] => Unit): FirehoseListener =
    new FirehoseListener(onFrame)

package learnat.sync

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import learnat.ipld.Ipld

/** Durable storage boundary for the last successfully applied firehose sequence number. */
trait CursorStore:
  /** Loads no cursor for a new consumer, or a non-negative sequence for a resumed consumer. */
  def load(): Either[SyncError, Option[Long]]

  /** Replaces the durable cursor only after the corresponding event has been applied. */
  def save(cursor: Long): Either[SyncError, Unit]

/**
 * One-file cursor store using write-then-rename replacement.
 *
 * The file contains one base-10 non-negative `Long` followed by a newline. A partial temporary file
 * is never accepted as the current cursor. `ATOMIC_MOVE` is requested when supported; filesystems
 * without it fall back to replacing the small cursor file after the temporary write completes.
 */
final class FileCursorStore(path: Path) extends CursorStore:
  def load(): Either[SyncError, Option[Long]] =
    try
      if !Files.exists(path) then Right(None)
      else
        val bytes = Files.readAllBytes(path)
        if bytes.length > FileCursorStore.MaxBytes then
          Left(SyncError(s"cursor file exceeds ${FileCursorStore.MaxBytes} bytes: $path"))
        else
          val text = String(bytes, StandardCharsets.US_ASCII).trim
          text.toLongOption.filter(_ >= 0) match
            case Some(cursor) => Right(Some(cursor))
            case None => Left(SyncError(s"cursor file is not a non-negative integer: $path"))
    catch
      case exception: Exception =>
        Left(SyncError(s"failed to read cursor $path: ${exception.getMessage}"))

  def save(cursor: Long): Either[SyncError, Unit] =
    if cursor < 0 then Left(SyncError(s"cursor must be non-negative, found $cursor"))
    else
      val parent = Option(path.getParent).getOrElse(Path.of("."))
      val temporary = parent.resolve(s".${path.getFileName}.${UUID.randomUUID()}.tmp")
      try
        Files.createDirectories(parent)
        Files.writeString(temporary, s"$cursor\n", StandardCharsets.US_ASCII)
        try
          Files.move(
            temporary,
            path,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
          )
        catch
          case _: AtomicMoveNotSupportedException => Files
              .move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
        Right(())
      catch
        case exception: Exception =>
          try Files.deleteIfExists(temporary)
          catch case _: Exception => ()
          Left(SyncError(s"failed to save cursor $path: ${exception.getMessage}"))

object FileCursorStore:
  private[sync] val MaxBytes = 32
  def apply(path: Path): FileCursorStore = new FileCursorStore(path)

/**
 * Applies decoded firehose messages with an at-least-once durable checkpoint.
 *
 * Ordering is deliberate: validate sequence, apply the event, then save the cursor. An apply
 * failure leaves the cursor unchanged. A cursor-write failure may replay an already-applied event
 * after restart, so `applyMessage` must be idempotent or transactional with its own materialized
 * state.
 */
final class CheckpointedFrameHandler(
    store: CursorStore,
    applyMessage: (Long, String, Ipld) => Either[SyncError, Unit]
):
  /** Cursor to pass to the next `subscribeRepos` connection. */
  def resumeCursor: Either[SyncError, Option[Long]] = store.load()

  /** Applies one message and advances its cursor, or rejects error frames without mutation. */
  def handle(frame: EventFrame): Either[SyncError, Long] = frame match
    case EventFrame.Error(name, message) => Left(
        SyncError(message.fold(s"firehose error: $name")(value => s"firehose error $name: $value"))
      )
    case EventFrame.Message(eventType, body) =>
      for
        sequence <- CheckpointedFrameHandler.sequence(body)
        previous <- store.load()
        _ <- Either.cond(
          previous.forall(_ < sequence),
          (),
          SyncError(s"event sequence $sequence is not newer than cursor ${previous.get}")
        )
        _ <- applyMessage(sequence, eventType, body)
        _ <- store.save(sequence)
      yield sequence

object CheckpointedFrameHandler:
  def apply(
      store: CursorStore,
      applyMessage: (Long, String, Ipld) => Either[SyncError, Unit]
  ): CheckpointedFrameHandler = new CheckpointedFrameHandler(store, applyMessage)

  private[sync] def sequence(body: Ipld): Either[SyncError, Long] = body match
    case Ipld.Map(fields) => fields.toMap.get("seq") match
        case Some(Ipld.Integer(value)) if value >= 0 => Right(value)
        case _ => Left(SyncError("firehose message body requires a non-negative seq integer"))
    case _ => Left(SyncError("firehose message body must be a map"))

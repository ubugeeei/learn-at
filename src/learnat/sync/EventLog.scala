package learnat.sync

/** One server-assigned event sequence and its canonical wire frame. */
final case class RetainedEvent(sequence: Long, bytes: Array[Byte])

/** Bounded batch returned to a producer transport for one subscriber cursor. */
final case class EventBatch(events: Vector[RetainedEvent], latestCursor: Option[Long])

/**
 * In-process bounded firehose event retention with explicit cursor failure semantics.
 *
 * Sequences start at one and increase without reuse. Retention drops only the oldest events. A
 * cursor older than the retained window fails instead of silently skipping updates; a cursor newer
 * than the producer head also fails. The WebSocket layer can translate those failures into
 * `ConsumerTooSlow` and `FutureCursor` event-error frames.
 */
final class RetainedEventLog private (capacity: Int):
  private var nextSequence = 1L
  private var retained = Vector.empty[RetainedEvent]

  /** Encodes and appends one message, returning its assigned immutable event. */
  def append(eventType: String, body: learnat.ipld.Ipld): Either[SyncError, RetainedEvent] =
    synchronized {
      if !eventType.startsWith("#") then Left(SyncError("event type must start with #"))
      else if nextSequence == Long.MaxValue then Left(SyncError("event sequence is exhausted"))
      else
        val sequence = nextSequence
        val sequencedBody = body match
          case learnat.ipld.Ipld.Map(fields) => learnat.ipld.Ipld.obj(
              (fields.filterNot(_._1 == "seq") :+ ("seq" -> learnat.ipld.Ipld.Integer(sequence)))*
            )
          case _ => body
        EventStreamCodec.encode(EventFrame.Message(eventType, sequencedBody)).map { bytes =>
          val event = RetainedEvent(sequence, bytes.clone())
          retained = (retained :+ event).takeRight(capacity)
          nextSequence += 1
          event.copy(bytes = event.bytes.clone())
        }
    }

  /** Reads events strictly newer than `cursor`, bounded by `limit`. */
  def readAfter(cursor: Option[Long], limit: Int = 100): Either[SyncError, EventBatch] =
    synchronized {
      if limit < 1 || limit > 1000 then Left(SyncError("event batch limit must be from 1 to 1000"))
      else
        val latest = retained.lastOption.map(_.sequence)
          .orElse(Some(nextSequence - 1).filter(_ > 0))
        val oldest = retained.headOption.map(_.sequence)
        cursor match
          case Some(value) if value < 0 => Left(SyncError("event cursor must be non-negative"))
          case Some(value) if latest.exists(value > _) =>
            Left(SyncError(s"FutureCursor: $value is newer than producer head ${latest.get}"))
          case Some(value) if oldest.exists(value < _ - 1) =>
            Left(SyncError(s"ConsumerTooSlow: cursor $value predates retained event ${oldest.get}"))
          case _ =>
            val after = cursor.getOrElse(0L)
            Right(EventBatch(
              retained.filter(_.sequence > after).take(limit)
                .map(event => event.copy(bytes = event.bytes.clone())),
              latest
            ))
    }

object RetainedEventLog:
  def create(capacity: Int): Either[SyncError, RetainedEventLog] = Either.cond(
    capacity > 0,
    new RetainedEventLog(capacity),
    SyncError("event retention capacity must be positive")
  )

package learnat.sync

import scala.util.control.NonFatal

/** One server-assigned event sequence and its canonical wire frame. */
final case class RetainedEvent(sequence: Long, bytes: Array[Byte])

/** Bounded batch returned to a producer transport for one subscriber cursor. */
final case class EventBatch(events: Vector[RetainedEvent], latestCursor: Option[Long])

/** A live event registration. Closing it is idempotent and stops future delivery. */
trait EventSubscription extends AutoCloseable:
  override def close(): Unit

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
  private var publishedThrough = 0L
  private var nextSubscriberId = 1L
  private var subscribers = Map.empty[Long, RetainedEvent => Unit]

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

  /**
   * Publishes the newest retained event after its repository transaction is durable.
   *
   * Subscriber callbacks run under the event-log lock. Transports must only enqueue work and
   * return; this is what preserves replay-before-live ordering for a concurrently connecting
   * subscriber.
   */
  private[learnat] def publishLast(sequence: Long): Either[SyncError, Unit] = synchronized {
    retained.lastOption match
      case Some(event) if event.sequence == sequence && publishedThrough == sequence - 1 =>
        publishedThrough = sequence
        subscribers.foreach { (subscriberId, callback) =>
          try callback(copyOf(event))
          catch case NonFatal(_) => subscribers = subscribers.removed(subscriberId)
        }
        Right(())
      case _ => Left(SyncError(s"cannot publish non-tail event sequence $sequence"))
  }

  /** Reads events strictly newer than `cursor`, bounded by `limit`. */
  def readAfter(cursor: Option[Long], limit: Int = 100): Either[SyncError, EventBatch] =
    synchronized {
      if limit < 1 || limit > 1000 then Left(SyncError("event batch limit must be from 1 to 1000"))
      else
        eventsAfterCursor(cursor).map(events =>
          EventBatch(
            events.take(limit).map(copyOf),
            Option.when(publishedThrough > 0)(publishedThrough)
          )
        )
    }

  /**
   * Replays every retained event after `cursor`, then registers for live events atomically.
   *
   * No append can interleave between replay and registration, so callers observe one strictly
   * increasing stream without a missing-event window.
   */
  def subscribe(
      cursor: Option[Long]
  )(onEvent: RetainedEvent => Unit): Either[SyncError, EventSubscription] = synchronized {
    eventsAfterCursor(cursor).flatMap { events =>
      try
        events.foreach(event => onEvent(copyOf(event)))
        val subscriberId = nextSubscriberId
        nextSubscriberId += 1
        subscribers = subscribers.updated(subscriberId, onEvent)
        Right(
          new EventSubscription:
            private var closed = false
            override def close(): Unit = RetainedEventLog.this.synchronized {
              if !closed then
                subscribers = subscribers.removed(subscriberId)
                closed = true
            }
        )
      catch
        case NonFatal(error) =>
          Left(SyncError(s"event subscriber rejected replay: ${error.getMessage}"))
    }
  }

  /** Rolls back only the newest unpublished event while the producer transaction is locked. */
  private[learnat] def rollbackLast(sequence: Long): Either[SyncError, Unit] = synchronized {
    retained.lastOption match
      case Some(event) if event.sequence == sequence && nextSequence == sequence + 1 =>
        retained = retained.dropRight(1)
        nextSequence = sequence
        Right(())
      case _ => Left(SyncError(s"cannot roll back non-tail event sequence $sequence"))
  }

  private def copyOf(event: RetainedEvent): RetainedEvent = event.copy(bytes = event.bytes.clone())

  private def eventsAfterCursor(cursor: Option[Long]): Either[SyncError, Vector[RetainedEvent]] =
    val published = retained.filter(_.sequence <= publishedThrough)
    val latest = Option.when(publishedThrough > 0)(publishedThrough)
    val oldest = published.headOption.map(_.sequence)
    cursor match
      case Some(value) if value < 0 => Left(SyncError("event cursor must be non-negative"))
      case Some(value) if latest.exists(value > _) =>
        Left(SyncError(s"FutureCursor: $value is newer than producer head ${latest.get}"))
      case Some(value) if oldest.exists(value < _ - 1) =>
        Left(SyncError(s"ConsumerTooSlow: cursor $value predates retained event ${oldest.get}"))
      case _ => Right(published.filter(_.sequence > cursor.getOrElse(0L)))

object RetainedEventLog:
  def create(capacity: Int): Either[SyncError, RetainedEventLog] = Either.cond(
    capacity > 0,
    new RetainedEventLog(capacity),
    SyncError("event retention capacity must be positive")
  )

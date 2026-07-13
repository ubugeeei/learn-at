package learnat.tests

import learnat.ipld.Ipld
import learnat.sync.EventFrame
import learnat.sync.EventStreamCodec
import learnat.sync.RetainedEventLog
import learnat.tests.TestKit.*

object EventLogTests:
  def run(): Unit =
    println("Server firehose event retention")

    test("encodes canonical event frames bidirectionally") {
      val frame = EventFrame.Message("#commit", Ipld.obj("seq" -> Ipld.Integer(7)))
      equal(EventStreamCodec.encode(frame).flatMap(EventStreamCodec.decode), Right(frame))
      val error = EventFrame.Error("FutureCursor", Some("retry from the head"))
      equal(EventStreamCodec.encode(error).flatMap(EventStreamCodec.decode), Right(error))
    }

    test("assigns monotonic sequences and resumes strictly after a cursor") {
      val log = RetainedEventLog.create(4).toOption.get
      val first = appendPublished(log, Ipld.obj("did" -> Ipld.Text("did:plc:first")))
      val second = appendPublished(log, Ipld.obj("did" -> Ipld.Text("did:plc:first")))
      equal(first.sequence, 1L)
      equal(second.sequence, 2L)
      val resumed = log.readAfter(Some(1)).toOption.get
      equal(resumed.events.map(_.sequence), Vector(2L))
      equal(
        EventStreamCodec.decode(resumed.events.head.bytes).map {
          case EventFrame.Message(_, Ipld.Map(fields)) => fields.toMap.get("seq")
          case _                                       => None
        },
        Right(Some(Ipld.Integer(2)))
      )
    }

    test("fails closed for expired and future cursors") {
      val log = RetainedEventLog.create(2).toOption.get
      (1 to 3).foreach(_ => appendPublished(log, Ipld.obj()))
      isLeft(log.readAfter(Some(0)))
      equal(log.readAfter(Some(1)).map(_.events.map(_.sequence)), Right(Vector(2L, 3L)))
      isLeft(log.readAfter(Some(4)))
    }

    test("bounds batches and returns defensive frame copies") {
      val log = RetainedEventLog.create(3).toOption.get
      (1 to 3).foreach(_ => appendPublished(log, Ipld.obj()))
      val one = log.readAfter(None, limit = 1).toOption.get
      equal(one.events.length, 1)
      one.events.head.bytes(0) = 0
      assert(log.readAfter(None, limit = 1).toOption.get.events.head.bytes(0) != 0)
      isLeft(log.readAfter(None, limit = 0))
      isLeft(RetainedEventLog.create(0))
    }

    test("rolls back only the unpublished tail event") {
      val log = RetainedEventLog.create(3).toOption.get
      val first = appendPublished(log, Ipld.obj())
      val second = log.append("#commit", Ipld.obj()).toOption.get
      isLeft(log.rollbackLast(first.sequence))
      equal(log.rollbackLast(second.sequence), Right(()))
      equal(log.readAfter(None).map(_.events.map(_.sequence)), Right(Vector(1L)))
      equal(log.append("#commit", Ipld.obj()).map(_.sequence), Right(2L))
    }

    test("publishes only durable events and replays before live delivery") {
      val log = RetainedEventLog.create(4).toOption.get
      val unpublished = log.append("#commit", Ipld.obj()).toOption.get
      equal(log.readAfter(None).map(_.events), Right(Vector.empty))

      assert(log.publishLast(unpublished.sequence).isRight)
      var delivered = Vector.empty[Long]
      val subscription = log.subscribe(None)(event => delivered :+= event.sequence).toOption.get
      appendPublished(log, Ipld.obj())
      equal(delivered, Vector(1L, 2L))

      subscription.close()
      subscription.close()
      appendPublished(log, Ipld.obj())
      equal(delivered, Vector(1L, 2L))
    }

    test("rejects invalid cursors without registering a subscriber") {
      val log = RetainedEventLog.create(2).toOption.get
      appendPublished(log, Ipld.obj())
      isLeft(log.subscribe(Some(2))(_ => ()))
      isLeft(log.subscribe(Some(-1))(_ => ()))
    }

    test("restores canonical frames and continues their sequence") {
      val original = RetainedEventLog.create(3).toOption.get
      appendPublished(original, Ipld.obj())
      appendPublished(original, Ipld.obj())
      val restored = RetainedEventLog.restore(3, original.framesForPersistence).toOption.get
      equal(restored.readAfter(Some(1)).map(_.events.map(_.sequence)), Right(Vector(2L)))
      equal(appendPublished(restored, Ipld.obj()).sequence, 3L)
    }

    test("rejects corrupt or non-contiguous persisted event frames") {
      val log = RetainedEventLog.create(3).toOption.get
      appendPublished(log, Ipld.obj())
      appendPublished(log, Ipld.obj())
      appendPublished(log, Ipld.obj())
      val frames = log.framesForPersistence
      isLeft(RetainedEventLog.restore(3, Vector(Array[Byte](1, 2, 3))))
      isLeft(RetainedEventLog.restore(3, Vector(frames.head, frames.last)))
    }

  private def appendPublished(log: RetainedEventLog, body: Ipld) =
    val event = log.append("#commit", body).toOption.get
    assert(log.publishLast(event.sequence).isRight)
    event

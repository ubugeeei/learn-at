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
      val first = log.append("#commit", Ipld.obj("did" -> Ipld.Text("did:plc:first"))).toOption.get
      val second = log.append("#commit", Ipld.obj("did" -> Ipld.Text("did:plc:first"))).toOption.get
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
      (1 to 3).foreach(_ => assert(log.append("#commit", Ipld.obj()).isRight))
      isLeft(log.readAfter(Some(0)))
      equal(log.readAfter(Some(1)).map(_.events.map(_.sequence)), Right(Vector(2L, 3L)))
      isLeft(log.readAfter(Some(4)))
    }

    test("bounds batches and returns defensive frame copies") {
      val log = RetainedEventLog.create(3).toOption.get
      (1 to 3).foreach(_ => assert(log.append("#commit", Ipld.obj()).isRight))
      val one = log.readAfter(None, limit = 1).toOption.get
      equal(one.events.length, 1)
      one.events.head.bytes(0) = 0
      assert(log.readAfter(None, limit = 1).toOption.get.events.head.bytes(0) != 0)
      isLeft(log.readAfter(None, limit = 0))
      isLeft(RetainedEventLog.create(0))
    }

    test("rolls back only the unpublished tail event") {
      val log = RetainedEventLog.create(3).toOption.get
      val first = log.append("#commit", Ipld.obj()).toOption.get
      val second = log.append("#commit", Ipld.obj()).toOption.get
      isLeft(log.rollbackLast(first.sequence))
      equal(log.rollbackLast(second.sequence), Right(()))
      equal(log.readAfter(None).map(_.events.map(_.sequence)), Right(Vector(1L)))
      equal(log.append("#commit", Ipld.obj()).map(_.sequence), Right(2L))
    }

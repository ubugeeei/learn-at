package learnat.tests

import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*
import learnat.client.AtpClient
import learnat.ipld.DagCbor
import learnat.ipld.Ipld
import learnat.pds.LocalPds
import learnat.pds.LocalPdsConfig
import learnat.sync.EventFrame
import learnat.sync.EventStreamCodec
import learnat.sync.FirehoseClient
import learnat.sync.RepositoryMirror
import learnat.sync.SyncResult
import learnat.syntax.AtIdentifier
import learnat.syntax.Handle
import learnat.syntax.Nsid
import learnat.syntax.RecordKey
import learnat.tests.TestKit.*

object SyncTests:
  private val handle = Handle.parse("sync.test").toOption.get
  private val collection = Nsid.parse("com.example.note").toOption.get
  private val key = RecordKey.parse("sync-record").toOption.get

  def run(): Unit =
    println("Repository synchronization")

    test("decodes message and error event-stream frames") {
      val messageHeader = DagCbor
        .encode(Ipld.obj("op" -> Ipld.Integer(1), "t" -> Ipld.Text("#commit"))).toOption.get
      val messageBody = DagCbor.encode(Ipld.obj("seq" -> Ipld.Integer(1))).toOption.get
      equal(
        EventStreamCodec.decode(messageHeader ++ messageBody),
        Right(EventFrame.Message("#commit", Ipld.obj("seq" -> Ipld.Integer(1))))
      )

      val errorHeader = DagCbor.encode(Ipld.obj("op" -> Ipld.Integer(-1))).toOption.get
      val errorBody = DagCbor
        .encode(Ipld.obj("error" -> Ipld.Text("FutureCursor"), "message" -> Ipld.Text("retry")))
        .toOption.get
      equal(
        EventStreamCodec.decode(errorHeader ++ errorBody),
        Right(EventFrame.Error("FutureCursor", Some("retry")))
      )
    }

    test("rejects malformed event-stream framing") {
      val oneValue = DagCbor.encode(Ipld.obj("op" -> Ipld.Integer(1))).toOption.get
      isLeft(EventStreamCodec.decode(oneValue))
      val badHeader = DagCbor.encode(Ipld.obj("op" -> Ipld.Integer(99))).toOption.get
      val body = DagCbor.encode(Ipld.obj()).toOption.get
      isLeft(EventStreamCodec.decode(badHeader ++ body))
    }

    withPds { pds =>
      val client = AtpClient.create(pds.service).toOption.get
      val mirror = RepositoryMirror(
        client,
        pds.did,
        pds.signingPublicKey,
        nowMicros = () => System.currentTimeMillis() * 1000L,
        allowedFutureDrift = Duration.ofMinutes(5)
      )

      test("bootstraps, detects unchanged state, and resyncs after a write") {
        val initial = mirror.syncOnce()
        assert(initial.exists(_.isInstanceOf[SyncResult.Updated]))
        assert(mirror.syncOnce().exists(_.isInstanceOf[SyncResult.Unchanged]))

        val authenticated = client
          .login(AtIdentifier.HandleIdentifier(handle), "sync-password".toCharArray).toOption.get
        val record = Ipld.obj("$type" -> Ipld.Text(collection.value), "text" -> Ipld.Text("new"))
        assert(authenticated.putRecord(collection, key, record).isRight)
        val updated = mirror.syncOnce()
        assert(updated.exists(_.isInstanceOf[SyncResult.Updated]))
        equal(mirror.snapshot.map(_.records.length), Some(1))
      }

      test("does not mutate a mirror when verification uses the wrong key") {
        val wrongKey = learnat.crypto.P256KeyPair.generate().toOption.get.publicKey
        val invalid = RepositoryMirror(client, pds.did, wrongKey)
        isLeft(invalid.syncOnce())
        equal(invalid.snapshot, None)
      }

      test("replays retained commits and then streams live commits after the cursor") {
        val replayed = ConcurrentLinkedQueue[EventFrame]()
        val replayLatch = CountDownLatch(1)
        val firstSocket = FirehoseClient.connect(pds.service) { frame =>
          frame.foreach { value =>
            replayed.add(value)
            replayLatch.countDown()
          }
        }.toOption.get.join()
        try
          assert(replayLatch.await(5, TimeUnit.SECONDS), "timed out waiting for retained event")
          equal(replayed.iterator().asScala.map(sequence).toVector, Vector(1L))
        finally firstSocket.sendClose(WebSocket.NORMAL_CLOSURE, "test complete").join()

        val live = ConcurrentLinkedQueue[EventFrame]()
        val liveLatch = CountDownLatch(1)
        val resumedSocket = FirehoseClient.connect(pds.service, Some(1L)) { frame =>
          frame.foreach { value =>
            live.add(value)
            liveLatch.countDown()
          }
        }.toOption.get.join()
        try
          val authenticated = client
            .login(AtIdentifier.HandleIdentifier(handle), "sync-password".toCharArray).toOption.get
          val liveKey = RecordKey.parse("live-record").toOption.get
          val record = Ipld.obj("$type" -> Ipld.Text(collection.value), "text" -> Ipld.Text("live"))
          assert(authenticated.putRecord(collection, liveKey, record).isRight)
          assert(liveLatch.await(5, TimeUnit.SECONDS), "timed out waiting for live event")
          equal(live.iterator().asScala.map(sequence).toVector, Vector(2L))
        finally resumedSocket.sendClose(WebSocket.NORMAL_CLOSURE, "test complete").join()
      }

      test("returns a canonical firehose error frame for a future cursor") {
        val received = ConcurrentLinkedQueue[EventFrame]()
        val latch = CountDownLatch(1)
        val socket = FirehoseClient.connect(pds.service, Some(999L)) { frame =>
          frame.foreach { value =>
            received.add(value)
            latch.countDown()
          }
        }.toOption.get.join()
        assert(latch.await(5, TimeUnit.SECONDS), "timed out waiting for cursor error")
        equal(
          received.peek(),
          EventFrame.Error("FutureCursor", Some("FutureCursor: 999 is newer than producer head 2"))
        )
        if !socket.isOutputClosed then
          socket.sendClose(WebSocket.NORMAL_CLOSURE, "test complete").join()
      }
    }

  private def sequence(frame: EventFrame): Long = frame match
    case EventFrame.Message("#commit", Ipld.Map(fields)) => fields.toMap.get("seq")
        .collect { case Ipld.Integer(value) => value }.get
    case other => throw AssertionError(s"expected #commit event, found $other")

  private def withPds(body: learnat.pds.RunningLocalPds => Unit): Unit =
    val password = "sync-password".toCharArray
    val pds = LocalPds.start(LocalPdsConfig(handle, password, port = 0)).toOption.get
    java.util.Arrays.fill(password, '\u0000')
    try body(pds)
    finally pds.close()

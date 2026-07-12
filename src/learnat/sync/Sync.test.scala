package learnat.tests

import java.time.Duration
import learnat.client.AtpClient
import learnat.ipld.DagCbor
import learnat.ipld.Ipld
import learnat.pds.LocalPds
import learnat.pds.LocalPdsConfig
import learnat.sync.EventFrame
import learnat.sync.EventStreamCodec
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
      val messageHeader = DagCbor.encode(Ipld.obj("op" -> Ipld.Integer(1), "t" -> Ipld.Text("#commit"))).toOption.get
      val messageBody = DagCbor.encode(Ipld.obj("seq" -> Ipld.Integer(1))).toOption.get
      equal(
        EventStreamCodec.decode(messageHeader ++ messageBody),
        Right(EventFrame.Message("#commit", Ipld.obj("seq" -> Ipld.Integer(1))))
      )

      val errorHeader = DagCbor.encode(Ipld.obj("op" -> Ipld.Integer(-1))).toOption.get
      val errorBody = DagCbor.encode(Ipld.obj("error" -> Ipld.Text("FutureCursor"), "message" -> Ipld.Text("retry"))).toOption.get
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

        val authenticated = client.login(AtIdentifier.HandleIdentifier(handle), "sync-password".toCharArray).toOption.get
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
    }

  private def withPds(body: learnat.pds.RunningLocalPds => Unit): Unit =
    val password = "sync-password".toCharArray
    val pds = LocalPds.start(LocalPdsConfig(handle, password, port = 0)).toOption.get
    java.util.Arrays.fill(password, '\u0000')
    try body(pds)
    finally pds.close()


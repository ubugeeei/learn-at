package learnat.tests

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.net.http.WebSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import learnat.client.AtpClient
import learnat.ipld.Ipld
import learnat.pds.LocalPds
import learnat.pds.LocalPdsConfig
import learnat.repo.RepositoryVerifier
import learnat.sync.EventFrame
import learnat.sync.FirehoseClient
import learnat.syntax.AtIdentifier
import learnat.syntax.Handle
import learnat.syntax.Nsid
import learnat.syntax.RecordKey
import learnat.tests.TestKit.*

object LocalPdsPersistenceTests:
  private val handle = Handle.parse("persistent.test").toOption.get
  private val collection = Nsid.parse("com.example.note").toOption.get
  private val key = RecordKey.parse("persisted").toOption.get

  def run(): Unit =
    println("Local PDS persistence")

    test("restores signing identity and records across a restart") {
      withDirectory { directory =>
        val password = "persistent-password".toCharArray
        val first = LocalPds.start(
          LocalPdsConfig(handle, password.clone(), port = 0, dataDirectory = Some(directory))
        ).toOption.get
        val port = first.service.getPort
        val did = first.did
        val publicKey = first.signingPublicKey
        val firstRevision = first.repository.commit.rev
        val client = AtpClient.create(first.service).toOption.get
        val authenticated = client.login(AtIdentifier.HandleIdentifier(handle), password.clone())
          .toOption.get
        assert(authenticated.putRecord(collection, key, note("survives restart")).isRight)
        val blobBytes = "persistent blob".getBytes(StandardCharsets.UTF_8)
        val blob = authenticated.uploadBlob("application/octet-stream", blobBytes).toOption.get
        first.close()

        val second = LocalPds.start(
          LocalPdsConfig(handle, password.clone(), port = port, dataDirectory = Some(directory))
        ).toOption.get
        try
          equal(second.did, did)
          equal(second.signingPublicKey.multikey, publicKey.multikey)
          assert(second.repository.commit.rev.newerThan(firstRevision))
          val restoredClient = AtpClient.create(second.service).toOption.get
          equal(
            restoredClient.getRecord(AtIdentifier.DidIdentifier(did), collection, key).map(_.value),
            Right(note("survives restart"))
          )
          val car = restoredClient.getRepo(did).toOption.get
          assert(RepositoryVerifier.verifyCar(car, did, publicKey).isRight)
          equal(
            restoredClient.getBlob(did, blob.cid).map(_.bytes.toVector),
            Right(blobBytes.toVector)
          )
          equal(second.eventsAfter(None).map(_.events.map(_.sequence)), Right(Vector(1L)))

          val latch = CountDownLatch(1)
          var liveSequence: Option[Long] = None
          val socket = FirehoseClient.connect(second.service, Some(1L)) { frame =>
            frame.foreach {
              case EventFrame.Message("#commit", Ipld.Map(fields)) =>
                liveSequence = fields.toMap.get("seq").collect { case Ipld.Integer(value) => value }
                latch.countDown()
              case _ => ()
            }
          }.toOption.get.join()
          try
            val restoredAuth = restoredClient
              .login(AtIdentifier.HandleIdentifier(handle), password.clone()).toOption.get
            val nextKey = RecordKey.parse("after-restart").toOption.get
            assert(restoredAuth.putRecord(collection, nextKey, note("second event")).isRight)
            assert(latch.await(5, TimeUnit.SECONDS), "timed out waiting for post-restart event")
            equal(liveSequence, Some(2L))
          finally socket.sendClose(WebSocket.NORMAL_CLOSURE, "test complete").join()
        finally second.close()
      }
    }

    test("fails closed when persisted state is corrupted") {
      withDirectory { directory =>
        Files.writeString(directory.resolve("state.json"), "not json", StandardCharsets.UTF_8)
        val result = LocalPds.start(
          LocalPdsConfig(handle, "password".toCharArray, port = 0, dataDirectory = Some(directory))
        )
        isLeft(result)
      }
    }

    test("reads version 1 state as an empty firehose history") {
      withDirectory { directory =>
        val password = "migration-password".toCharArray
        val first = LocalPds.start(
          LocalPdsConfig(handle, password.clone(), port = 0, dataDirectory = Some(directory))
        ).toOption.get
        val port = first.service.getPort
        first.close()
        val statePath = directory.resolve("state.json")
        val version2 = Files.readString(statePath, StandardCharsets.UTF_8)
        val version1 = version2.replaceFirst("\"version\":2", "\"version\":1")
          .replaceFirst(",\"events\":\\[\\]", "")
        Files.writeString(statePath, version1, StandardCharsets.UTF_8)

        val restored = LocalPds.start(
          LocalPdsConfig(handle, password.clone(), port = port, dataDirectory = Some(directory))
        ).toOption.get
        try equal(restored.eventsAfter(None).map(_.events), Right(Vector.empty))
        finally restored.close()
      }
    }

    test("fails closed when a persisted firehose frame is corrupt") {
      withDirectory { directory =>
        val password = "event-corruption-password".toCharArray
        val first = LocalPds.start(
          LocalPdsConfig(handle, password.clone(), port = 0, dataDirectory = Some(directory))
        ).toOption.get
        val port = first.service.getPort
        val client = AtpClient.create(first.service).toOption.get
        val authenticated = client.login(AtIdentifier.HandleIdentifier(handle), password.clone())
          .toOption.get
        assert(authenticated.putRecord(collection, key, note("event to corrupt")).isRight)
        first.close()

        val statePath = directory.resolve("state.json")
        val valid = Files.readString(statePath, StandardCharsets.UTF_8)
        val corrupt = valid.replaceFirst("\"events\":\\[\"[^\"]+\"", "\"events\":[\"AQID\"")
        Files.writeString(statePath, corrupt, StandardCharsets.UTF_8)
        isLeft(LocalPds.start(
          LocalPdsConfig(handle, password.clone(), port = port, dataDirectory = Some(directory))
        ))
      }
    }

    test("refuses to reuse stored key material for a different did:web port") {
      withDirectory { directory =>
        val first = LocalPds.start(
          LocalPdsConfig(handle, "password".toCharArray, port = 0, dataDirectory = Some(directory))
        ).toOption.get
        val differentPort =
          if first.service.getPort == 65535 then 65534 else first.service.getPort + 1
        first.close()
        isLeft(LocalPds.start(LocalPdsConfig(
          handle,
          "password".toCharArray,
          port = differentPort,
          dataDirectory = Some(directory)
        )))
      }
    }

  private def note(text: String): Ipld = Ipld
    .obj("$type" -> Ipld.Text(collection.value), "text" -> Ipld.Text(text))

  private def withDirectory(body: Path => Unit): Unit =
    val directory = Files.createTempDirectory("learn-at-pds-test")
    try body(directory)
    finally deleteRecursively(directory)

  private def deleteRecursively(path: Path): Unit = if Files.exists(path) then
    val stream = Files.walk(path)
    try stream.sorted(java.util.Comparator.reverseOrder())
        .forEach(value => Files.deleteIfExists(value))
    finally stream.close()

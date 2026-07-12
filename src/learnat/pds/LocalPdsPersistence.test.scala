package learnat.tests

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import learnat.client.AtpClient
import learnat.ipld.Ipld
import learnat.pds.LocalPds
import learnat.pds.LocalPdsConfig
import learnat.repo.RepositoryVerifier
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

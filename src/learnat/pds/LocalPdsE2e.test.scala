package learnat.tests

import java.net.URI
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import learnat.client.AtpClient
import learnat.client.ClientMain
import learnat.identity.IdentityResolver
import learnat.identity.IdentityResolverConfig
import learnat.identity.JdkIdentityNetwork
import learnat.ipld.Ipld
import learnat.json.Json
import learnat.pds.LocalPds
import learnat.pds.LocalPdsConfig
import learnat.repo.RepositoryVerifier
import learnat.syntax.AtIdentifier
import learnat.syntax.Handle
import learnat.syntax.Nsid
import learnat.syntax.RecordKey
import learnat.tests.TestKit.*

object LocalPdsE2eTests:
  private val handle = Handle.parse("alice.test").toOption.get
  private val collection = Nsid.parse("com.example.note").toOption.get

  def run(): Unit =
    println("Client <-> local PDS end-to-end")

    withPds { pds =>
      val client = AtpClient.create(pds.service).toOption.get

      test("rejects invalid credentials and accepts the configured account") {
        isLeft(client.login(AtIdentifier.HandleIdentifier(handle), "wrong".toCharArray))
        val authenticated = client
          .login(AtIdentifier.HandleIdentifier(handle), "test-password".toCharArray)
        assert(authenticated.isRight, authenticated)
        equal(authenticated.flatMap(_.getSession).map(_.did), Right(pds.did))
      }

      val authenticated = client
        .login(AtIdentifier.HandleIdentifier(handle), "test-password".toCharArray).toOption.get
      var generatedKey: Option[RecordKey] = None

      test("creates a record with a server-generated TID key") {
        val created = authenticated.createRecord(collection, note("first"))
        assert(created.isRight, created)
        val key = created.toOption.get.uri.recordKey.get
        generatedKey = Some(key)
        assert(key.value.length == 13)
        val read = client.getRecord(AtIdentifier.DidIdentifier(pds.did), collection, key)
        equal(read.map(_.cid), created.map(_.cid))
        equal(read.map(_.value), Right(note("first")))
      }

      test("paginates, replaces, and deletes repository records") {
        val first = generatedKey.get
        val second = RecordKey.parse("second").toOption.get
        val third = RecordKey.parse("third").toOption.get
        assert(authenticated.putRecord(collection, second, note("second")).isRight)
        assert(authenticated.putRecord(collection, third, note("third")).isRight)
        val firstPage = client
          .listRecords(AtIdentifier.DidIdentifier(pds.did), collection, limit = 2)
        equal(firstPage.map(_.records.length), Right(2))
        assert(firstPage.toOption.get.cursor.nonEmpty)
        val secondPage = client.listRecords(
          AtIdentifier.DidIdentifier(pds.did),
          collection,
          limit = 2,
          cursor = firstPage.toOption.get.cursor
        )
        equal(secondPage.map(_.records.length), Right(1))

        val replaced = authenticated.putRecord(collection, first, note("replaced"))
        assert(replaced.isRight)
        equal(
          client.getRecord(AtIdentifier.DidIdentifier(pds.did), collection, first).map(_.value),
          Right(note("replaced"))
        )
        assert(authenticated.deleteRecord(collection, first).isRight)
        isLeft(client.getRecord(AtIdentifier.DidIdentifier(pds.did), collection, first))
      }

      test("exports a CAR that verifies independently of the HTTP server") {
        val bytes = client.getRepo(pds.did)
        assert(bytes.isRight, bytes)
        val verified = bytes.flatMap(value =>
          RepositoryVerifier.verifyCar(value, pds.did, pds.signingPublicKey).left
            .map(error => learnat.client.ClientError(error.message))
        )
        assert(verified.isRight, verified)
        equal(verified.map(_.records.length), Right(2))
      }

      test("verifies an exported CAR against the currently resolved DID key") {
        val carFile = Files.createTempFile("learn-at-repository", ".car")
        try
          Files.write(carFile, client.getRepo(pds.did).toOption.get)
          val (status, output, error) = runCli(
            Array("verify", pds.did.value, carFile.toString, "--allow-http-local"),
            Map.empty
          )
          equal(status, 0)
          equal(error, "")
          val result = Json.parse(output).toOption.get
          equal(result.field("verified").flatMap(_.asBoolean), Right(true))
          equal(result.field("did").flatMap(_.asString), Right(pds.did.value))
          assert(result.field("records").flatMap(_.asLong).exists(_ >= 1))
        finally Files.deleteIfExists(carFile)
      }

      test("serves a resolvable localhost did:web document") {
        val resolver = IdentityResolver(
          JdkIdentityNetwork.default,
          IdentityResolverConfig(allowHttpLocal = true, allowTestTld = true)
        )
        val resolved = resolver.resolve(AtIdentifier.DidIdentifier(pds.did))
        assert(resolved.isRight, resolved)
        equal(resolved.map(_.pds), Right(pds.service))
        equal(resolved.map(_.signingKeyMultibase), Right(pds.signingPublicKey.multikey))
      }

      test("uses an origin-level loopback service") {
        equal(pds.service.getHost, "localhost")
        assert(pds.service.getPort > 0)
        assert(pds.service != URI.create("http://localhost:0"))
      }

      test("runs the post and list CLI commands without putting passwords in argv") {
        val output = ByteArrayOutputStream()
        val errors = ByteArrayOutputStream()
        val postStatus = ClientMain.run(
          Array("post", pds.service.toString, handle.value, collection.value, "from the CLI"),
          Map("LEARN_AT_PASSWORD" -> "test-password"),
          PrintStream(output),
          PrintStream(errors)
        )
        equal(postStatus, 0)
        assert(Json.parse(output.toString(StandardCharsets.UTF_8)).isRight)

        output.reset()
        val listStatus = ClientMain.run(
          Array("list", pds.service.toString, pds.did.value, collection.value),
          Map.empty,
          PrintStream(output),
          PrintStream(errors)
        )
        equal(listStatus, 0)
        val listed = Json.parse(output.toString(StandardCharsets.UTF_8)).toOption.get
        assert(listed.field("records").flatMap(_.asArray).exists(_.nonEmpty))
      }

      test("runs file-based create, put, paginated list, and delete CLI commands") {
        val recordFile = Files.createTempFile("learn-at-record", ".json")
        try
          Files.writeString(recordFile, noteJson("created from a file").render)
          val (createStatus, created, createError) = runCli(
            Array(
              "create",
              pds.service.toString,
              handle.value,
              collection.value,
              recordFile.toString,
              "cli-record"
            ),
            Map("LEARN_AT_PASSWORD" -> "test-password")
          )
          equal(createStatus, 0)
          equal(createError, "")
          assert(Json.parse(created).flatMap(_.field("cid")).isRight)

          Files.writeString(recordFile, noteJson("updated from a file").render)
          val (putStatus, _, putError) = runCli(
            Array(
              "put",
              pds.service.toString,
              handle.value,
              collection.value,
              "cli-record",
              recordFile.toString
            ),
            Map("LEARN_AT_PASSWORD" -> "test-password")
          )
          equal(putStatus, 0)
          equal(putError, "")

          val (listStatus, listedText, listError) = runCli(
            Array(
              "list",
              pds.service.toString,
              pds.did.value,
              collection.value,
              "--limit",
              "1",
              "--reverse"
            ),
            Map.empty
          )
          equal(listStatus, 0)
          equal(listError, "")
          val listed = Json.parse(listedText).toOption.get
          equal(listed.field("records").flatMap(_.asArray).map(_.length), Right(1))

          val (deleteStatus, deletedText, deleteError) = runCli(
            Array("delete", pds.service.toString, handle.value, collection.value, "cli-record"),
            Map("LEARN_AT_PASSWORD" -> "test-password")
          )
          equal(deleteStatus, 0)
          equal(deleteError, "")
          equal(
            Json.parse(deletedText).flatMap(_.field("deleted")).flatMap(_.asBoolean),
            Right(true)
          )
          isLeft(client.getRecord(
            AtIdentifier.DidIdentifier(pds.did),
            collection,
            RecordKey.parse("cli-record").toOption.get
          ))
        finally Files.deleteIfExists(recordFile)
      }

      test("rejects unsafe CLI record files and list options") {
        val recordFile = Files.createTempFile("learn-at-invalid-record", ".json")
        try
          Files.writeString(recordFile, "[1,2,3]")
          val (status, _, error) = runCli(
            Array(
              "create",
              pds.service.toString,
              handle.value,
              collection.value,
              recordFile.toString
            ),
            Map("LEARN_AT_PASSWORD" -> "test-password")
          )
          equal(status, 2)
          assert(error.contains("must be a JSON object"), error)

          val (listStatus, _, listError) = runCli(
            Array("list", pds.service.toString, pds.did.value, collection.value, "--limit", "1000"),
            Map.empty
          )
          equal(listStatus, 2)
          assert(listError.contains("1 to 100"), listError)
        finally Files.deleteIfExists(recordFile)
      }
    }

  private def withPds(body: learnat.pds.RunningLocalPds => Unit): Unit =
    val password = "test-password".toCharArray
    val pds = LocalPds.start(LocalPdsConfig(handle, password, port = 0, workerThreads = 2)).toOption
      .get
    java.util.Arrays.fill(password, '\u0000')
    try body(pds)
    finally pds.close()

  private def note(text: String): Ipld = Ipld.obj(
    "$type" -> Ipld.Text(collection.value),
    "text" -> Ipld.Text(text),
    "createdAt" -> Ipld.Text("2026-07-10T00:00:00.000Z")
  )

  private def noteJson(text: String): Json = Json.obj(
    "$type" -> Json.Str(collection.value),
    "text" -> Json.Str(text),
    "createdAt" -> Json.Str("2026-07-10T00:00:00.000Z")
  )

  private def runCli(args: Array[String], environment: Map[String, String]): (Int, String, String) =
    val output = ByteArrayOutputStream()
    val errors = ByteArrayOutputStream()
    val status = ClientMain.run(args, environment, PrintStream(output), PrintStream(errors))
    (
      status,
      output.toString(StandardCharsets.UTF_8).trim,
      errors.toString(StandardCharsets.UTF_8).trim
    )

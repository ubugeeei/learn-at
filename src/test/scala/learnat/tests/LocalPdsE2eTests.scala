package learnat.tests

import java.net.URI
import learnat.client.AtpClient
import learnat.identity.IdentityResolver
import learnat.identity.IdentityResolverConfig
import learnat.identity.JdkIdentityNetwork
import learnat.ipld.Ipld
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
        val authenticated = client.login(AtIdentifier.HandleIdentifier(handle), "test-password".toCharArray)
        assert(authenticated.isRight, authenticated)
        equal(authenticated.flatMap(_.getSession).map(_.did), Right(pds.did))
      }

      val authenticated = client.login(AtIdentifier.HandleIdentifier(handle), "test-password".toCharArray).toOption.get
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
        val firstPage = client.listRecords(AtIdentifier.DidIdentifier(pds.did), collection, limit = 2)
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
        equal(client.getRecord(AtIdentifier.DidIdentifier(pds.did), collection, first).map(_.value), Right(note("replaced")))
        assert(authenticated.deleteRecord(collection, first).isRight)
        isLeft(client.getRecord(AtIdentifier.DidIdentifier(pds.did), collection, first))
      }

      test("exports a CAR that verifies independently of the HTTP server") {
        val bytes = client.getRepo(pds.did)
        assert(bytes.isRight, bytes)
        val verified = bytes.flatMap(value =>
          RepositoryVerifier.verifyCar(value, pds.did, pds.signingPublicKey).left.map(error => learnat.client.ClientError(error.message))
        )
        assert(verified.isRight, verified)
        equal(verified.map(_.records.length), Right(2))
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
    }

  private def withPds(body: learnat.pds.RunningLocalPds => Unit): Unit =
    val password = "test-password".toCharArray
    val pds = LocalPds.start(LocalPdsConfig(handle, password, port = 0, workerThreads = 2)).toOption.get
    java.util.Arrays.fill(password, '\u0000')
    try body(pds)
    finally pds.close()

  private def note(text: String): Ipld = Ipld.obj(
    "$type" -> Ipld.Text(collection.value),
    "text" -> Ipld.Text(text),
    "createdAt" -> Ipld.Text("2026-07-10T00:00:00.000Z")
  )


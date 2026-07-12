package learnat.tests

import java.net.URI
import java.nio.charset.StandardCharsets
import learnat.identity.*
import learnat.json.Json
import learnat.syntax.AtIdentifier
import learnat.syntax.Did
import learnat.syntax.Handle
import learnat.tests.TestKit.*

object IdentityTests:
  private val did = Did.parse("did:plc:alice123").toOption.get
  private val handle = Handle.parse("alice.test").toOption.get
  private val document =
    """{
      |  "id": "did:plc:alice123",
      |  "alsoKnownAs": ["at://alice.test"],
      |  "verificationMethod": [{
      |    "id": "did:plc:alice123#atproto",
      |    "type": "Multikey",
      |    "controller": "did:plc:alice123",
      |    "publicKeyMultibase": "zDnaExampleKey"
      |  }],
      |  "service": [{
      |    "id": "#atproto_pds",
      |    "type": "AtprotoPersonalDataServer",
      |    "serviceEndpoint": "https://pds.test"
      |  }]
      |}""".stripMargin

  def run(): Unit =
    println("Identity")

    test("resolves and verifies a handle bidirectionally") {
      val network = FakeIdentityNetwork(
        Map("_atproto.alice.test" -> Vector("did=did:plc:alice123")),
        Map(URI.create("https://plc.directory/did:plc:alice123") -> ok(document))
      )
      val resolver = IdentityResolver(network, IdentityResolverConfig(allowTestTld = true))
      val result = resolver.resolve(AtIdentifier.HandleIdentifier(handle))
      assert(result.isRight, result)
      equal(result.map(_.did), Right(did))
      equal(result.map(_.pds), Right(URI.create("https://pds.test")))
      equal(result.map(_.signingKeyMultibase), Right("zDnaExampleKey"))
    }

    test("rejects conflicting DNS handle claims") {
      val network = FakeIdentityNetwork(
        Map("_atproto.alice.test" -> Vector("did=did:plc:first", "did=did:plc:second")),
        Map.empty
      )
      val resolver = IdentityResolver(network, IdentityResolverConfig(allowTestTld = true))
      assert(resolver.resolveHandle(handle).left.exists(_.isInstanceOf[IdentityError.AmbiguousHandle]))
    }

    test("falls back to the HTTPS well-known handle endpoint") {
      val uri = URI.create("https://alice.test/.well-known/atproto-did")
      val network = FakeIdentityNetwork(Map.empty, Map(uri -> ok("  did:plc:alice123\n")))
      val resolver = IdentityResolver(network, IdentityResolverConfig(allowTestTld = true))
      equal(resolver.resolveHandle(handle), Right(did))
    }

    test("requires a DID document to link back to the handle") {
      val withoutAlias = document.replace("\"at://alice.test\"", "\"at://mallory.test\"")
      val network = FakeIdentityNetwork(
        Map("_atproto.alice.test" -> Vector("did=did:plc:alice123")),
        Map(URI.create("https://plc.directory/did:plc:alice123") -> ok(withoutAlias))
      )
      val resolver = IdentityResolver(network, IdentityResolverConfig(allowTestTld = true))
      assert(resolver.resolve(AtIdentifier.HandleIdentifier(handle)).left.exists(_.isInstanceOf[IdentityError.HandleMismatch]))
    }

    test("checks the expected DID and ignores unrelated service entries") {
      val parsed = Json.parse(document).toOption.get
      assert(DidDocument.decode(Did.parse("did:plc:other").toOption.get, parsed).isLeft)
      val decoded = DidDocument.decode(did, parsed).toOption.get
      equal(decoded.claimedHandle.map(_.normalized), Some("alice.test"))
      equal(decoded.pdsEndpoint(allowHttpLocal = false), Some(URI.create("https://pds.test")))
    }

    test("allows localhost did:web only in explicit development mode") {
      val localDid = Did.parse("did:web:localhost%3A2583").toOption.get
      val localDocument = document.replace("did:plc:alice123", "did:web:localhost%3A2583")
      val uri = URI.create("http://localhost:2583/.well-known/did.json")
      val network = FakeIdentityNetwork(Map.empty, Map(uri -> ok(localDocument)))
      assert(IdentityResolver(network).resolveDid(localDid).isLeft)
      assert(IdentityResolver(network, IdentityResolverConfig(allowHttpLocal = true)).resolveDid(localDid).isRight)
    }

    test("rejects invalid did:web percent encoding without throwing") {
      val malformed = Did.parse("did:web:localhost%GG").toOption.get
      val resolver = IdentityResolver(FakeIdentityNetwork(Map.empty, Map.empty), IdentityResolverConfig(allowHttpLocal = true))
      assert(resolver.resolveDid(malformed).isLeft)
    }

  private def ok(body: String): IdentityHttpResponse =
    IdentityHttpResponse(200, body.getBytes(StandardCharsets.UTF_8))

  private final class FakeIdentityNetwork(
      records: Map[String, Vector[String]],
      responses: Map[URI, IdentityHttpResponse]
  ) extends IdentityNetwork:
    override def dnsTxt(name: String): Either[IdentityError, Vector[String]] = Right(records.getOrElse(name, Vector.empty))

    override def get(uri: URI, maxBytes: Int): Either[IdentityError, IdentityHttpResponse] =
      responses.get(uri) match
        case Some(response) if response.body.length <= maxBytes => Right(response)
        case Some(response) => Left(IdentityError.ResponseTooLarge(uri, maxBytes, response.body.length))
        case None => Left(IdentityError.Network(s"no fake response for $uri", None))

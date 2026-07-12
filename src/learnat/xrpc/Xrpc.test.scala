package learnat.tests

import java.net.URI
import java.nio.charset.StandardCharsets
import learnat.json.Json.*
import learnat.syntax.Nsid
import learnat.tests.TestKit.*
import learnat.xrpc.*

object XrpcTests:
  def run(): Unit =
    println("XRPC")

    test("encodes a query as GET with repeatable parameters") {
      val transport = RecordingTransport(jsonResponse(200, """{"did":"did:plc:123"}"""))
      val client = XrpcClient.create(URI.create("https://pds.example/"), transport).toOption.get
      val method = Nsid.parse("com.atproto.identity.resolveHandle").toOption.get
      val result = client.query(method, Vector("handle" -> "alice+test.example", "tag" -> "café"))
      assert(result.isRight, result)
      val request = transport.lastRequest.get
      equal(request.method, HttpMethod.Get)
      equal(
        request.uri.toString,
        "https://pds.example/xrpc/com.atproto.identity.resolveHandle?handle=alice%2Btest.example&tag=caf%C3%A9"
      )
      assert(request.body.isEmpty)
    }

    test("encodes a JSON procedure and bearer token") {
      val transport = RecordingTransport(jsonResponse(200, """{"uri":"at://did:plc:123/com.example.record/1"}"""))
      val client = XrpcClient.create(URI.create("http://localhost:2583"), transport).toOption.get
      val method = Nsid.parse("com.atproto.repo.createRecord").toOption.get
      val result = client.procedure(method, obj("repo" -> Str("did:plc:123")), Some("secret"))
      assert(result.isRight, result)
      val request = transport.lastRequest.get
      equal(request.method, HttpMethod.Post)
      equal(request.body.map(bytes => String(bytes, StandardCharsets.UTF_8)), Some("""{"repo":"did:plc:123"}"""))
      assert(request.headers.contains("Authorization" -> "Bearer secret"))
      assert(request.headers.contains("Content-Type" -> "application/json"))
    }

    test("decodes structured XRPC errors") {
      val transport = RecordingTransport(jsonResponse(400, """{"error":"InvalidRequest","message":"repo is required"}"""))
      val client = XrpcClient.create(URI.create("https://pds.example"), transport).toOption.get
      val method = Nsid.parse("com.atproto.repo.getRecord").toOption.get
      val result = client.query(method)
      equal(result, Left(XrpcError.Remote(400, "InvalidRequest", Some("repo is required"))))
    }

    test("rejects malformed success bodies and oversized responses") {
      val malformed = RecordingTransport(jsonResponse(200, "not json"))
      val smallLimit = RecordingTransport(jsonResponse(200, """{"value":"too long"}"""))
      val method = Nsid.parse("com.atproto.server.describeServer").toOption.get
      val malformedResult = XrpcClient.create(URI.create("https://pds.example"), malformed).toOption.get.query(method)
      val largeResult = XrpcClient.create(URI.create("https://pds.example"), smallLimit, maxResponseBytes = 5).toOption.get.query(method)
      assert(malformedResult.left.exists(_.isInstanceOf[XrpcError.InvalidResponse]))
      equal(largeResult, Left(XrpcError.ResponseTooLarge(5, 20)))
    }

    test("requires an origin-level HTTP service") {
      isLeft(XrpcClient.create(URI.create("ftp://pds.example")))
      isLeft(XrpcClient.create(URI.create("https://pds.example/prefix")))
    }

  private def jsonResponse(status: Int, body: String): HttpResponseData =
    HttpResponseData(status, Map("content-type" -> Vector("application/json")), body.getBytes(StandardCharsets.UTF_8))

  private final class RecordingTransport(response: HttpResponseData) extends HttpTransport:
    var lastRequest: Option[HttpRequestData] = None

    override def send(request: HttpRequestData): Either[XrpcError, HttpResponseData] =
      lastRequest = Some(request)
      Right(response)

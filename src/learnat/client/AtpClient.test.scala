package learnat.tests

import java.net.URI
import java.nio.charset.StandardCharsets
import learnat.client.AtpClient
import learnat.client.ClientErrorKind
import learnat.json.Json
import learnat.syntax.AtIdentifier
import learnat.syntax.Did
import learnat.syntax.Nsid
import learnat.syntax.RecordKey
import learnat.tests.TestKit.*
import learnat.xrpc.HttpRequestData
import learnat.xrpc.HttpResponseData
import learnat.xrpc.HttpTransport
import learnat.xrpc.XrpcError

object AtpClientTests:
  private val did = Did.parse("did:plc:clienttest").toOption.get
  private val repo = AtIdentifier.DidIdentifier(did)
  private val collection = Nsid.parse("com.example.note").toOption.get
  private val key = RecordKey.parse("entry").toOption.get

  def run(): Unit =
    println("Typed AT Protocol client errors")

    cases("preserves remote status and retry policy")(
      "bad request" -> (400, "InvalidRequest", false),
      "authentication" -> (401, "AuthenticationRequired", false),
      "rate limit" -> (429, "RateLimitExceeded", true),
      "server unavailable" -> (503, "UpstreamFailure", true)
    ) { case (status, code, retryable) =>
      val transport = ResponseTransport(HttpResponseData(
        status,
        Map.empty,
        Json.obj("error" -> Json.Str(code), "message" -> Json.Str("test failure")).render
          .getBytes(StandardCharsets.UTF_8)
      ))
      val error = AtpClient.create(URI.create("https://pds.example"), transport).toOption.get
        .getRecord(repo, collection, key).left.toOption.get
      equal(error.kind, ClientErrorKind.Remote)
      equal(error.status, Some(status))
      equal(error.code, Some(code))
      equal(error.retryable, retryable)
    }

    test("marks transport failure retryable without inventing HTTP fields") {
      val transport = new HttpTransport:
        def send(request: HttpRequestData): Either[XrpcError, HttpResponseData] =
          val _ = request
          Left(XrpcError.Transport("connection reset", None))
      val error = AtpClient.create(URI.create("https://pds.example"), transport).toOption.get
        .getRecord(repo, collection, key).left.toOption.get
      equal(error.kind, ClientErrorKind.Transport)
      equal(error.status, None)
      equal(error.code, None)
      equal(error.retryable, true)
    }

    test("classifies malformed success as a non-retryable protocol failure") {
      val transport = ResponseTransport(
        HttpResponseData(200, Map.empty, "not json".getBytes(StandardCharsets.UTF_8))
      )
      val error = AtpClient.create(URI.create("https://pds.example"), transport).toOption.get
        .getRecord(repo, collection, key).left.toOption.get
      equal(error.kind, ClientErrorKind.Protocol)
      equal(error.status, Some(200))
      equal(error.retryable, false)
    }

  final private class ResponseTransport(response: HttpResponseData) extends HttpTransport:
    def send(request: HttpRequestData): Either[XrpcError, HttpResponseData] =
      val _ = request
      Right(response)

  private object ResponseTransport:
    def apply(response: HttpResponseData): ResponseTransport = new ResponseTransport(response)

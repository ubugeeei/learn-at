package learnat.xrpc

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import learnat.json.Json
import learnat.syntax.Nsid

/** HTTP verbs used by Lexicon query and procedure transports. */
enum HttpMethod:
  case Get, Post

/** Transport-neutral request with exact URI, headers, bytes, and deadline. */
final case class HttpRequestData(
    method: HttpMethod,
    uri: URI,
    headers: Vector[(String, String)],
    body: Option[Array[Byte]],
    timeout: Duration
)

/** Transport-neutral response retaining repeated header values. */
final case class HttpResponseData(
    status: Int,
    headers: Map[String, Vector[String]],
    body: Array[Byte]
):
  /** Reads the first value of a case-insensitive HTTP header. */
  def header(name: String): Option[String] = headers
    .collectFirst { case (key, values) if key.equalsIgnoreCase(name) => values.headOption }.flatten

/** Injectable HTTP boundary used by XRPC and deterministic wire tests. */
trait HttpTransport:
  /** Sends one already-encoded request. */
  def send(request: HttpRequestData): Either[XrpcError, HttpResponseData]

/** Blocking JDK HTTP client adapter with interruption preservation. */
final class JdkHttpTransport private (client: HttpClient) extends HttpTransport:
  override def send(request: HttpRequestData): Either[XrpcError, HttpResponseData] =
    try
      val builder = HttpRequest.newBuilder(request.uri).timeout(request.timeout)
      request.headers.foreach((name, value) => builder.header(name, value))
      request.method match
        case HttpMethod.Get  => builder.GET()
        case HttpMethod.Post =>
          val body = request.body.getOrElse(Array.emptyByteArray)
          builder.POST(HttpRequest.BodyPublishers.ofByteArray(body))
      val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
      val headers = response.headers().map().entrySet().toArray.toVector.map { rawEntry =>
        val entry = rawEntry.asInstanceOf[java.util.Map.Entry[String, java.util.List[String]]]
        entry.getKey -> entry.getValue.toArray.toVector.map(_.toString)
      }.toMap
      Right(HttpResponseData(response.statusCode(), headers, response.body()))
    catch
      case interrupted: InterruptedException =>
        Thread.currentThread().interrupt()
        Left(XrpcError.Transport("HTTP request was interrupted", Some(interrupted)))
      case error: Exception => Left(XrpcError.Transport(error.getMessage, Some(error)))

object JdkHttpTransport:
  /** Creates the default redirecting client with a bounded connect timeout. */
  def default: JdkHttpTransport =
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
      .followRedirects(HttpClient.Redirect.NORMAL).build()
    new JdkHttpTransport(client)

/** Failures separated by service configuration, transport, and remote protocol. */
enum XrpcError:
  case InvalidService(message: String)
  case Transport(message: String, cause: Option[Throwable])
  case ResponseTooLarge(limit: Int, actual: Int)
  case InvalidResponse(status: Int, message: String)
  case Remote(status: Int, error: String, message: Option[String])

  /** Stable human-facing explanation without losing the typed case. */
  def description: String = this match
    case InvalidService(detail)          => detail
    case Transport(detail, _)            => detail
    case ResponseTooLarge(limit, actual) => s"response contains $actual bytes; limit is $limit"
    case InvalidResponse(status, detail) => s"invalid HTTP $status response: $detail"
    case Remote(status, error, detail)   =>
      s"XRPC $status $error${detail.fold("")(value => s": $value")}"

/** Successful raw XRPC response before endpoint-specific body decoding. */
final case class XrpcResponse(status: Int, headers: Map[String, Vector[String]], body: Array[Byte]):
  /** Decodes body bytes as UTF-8 for JSON-facing methods. */
  def utf8: String = String(body, StandardCharsets.UTF_8)

  /** Strictly parses the UTF-8 body as one JSON document. */
  def json: Either[XrpcError, Json] = Json.parse(utf8).left
    .map(error => XrpcError.InvalidResponse(status, error.toString))

/** Dependency-light XRPC encoder/decoder bound to one service origin. */
final class XrpcClient private (
    service: URI,
    transport: HttpTransport,
    timeout: Duration,
    maxResponseBytes: Int
):
  /** Executes a Lexicon query as GET and decodes a JSON success body. */
  def query(
      method: Nsid,
      parameters: Vector[(String, String)] = Vector.empty,
      bearerToken: Option[String] = None,
      extraHeaders: Vector[(String, String)] = Vector.empty
  ): Either[XrpcError, Json] =
    val uri = URI.create(s"${service.toString}/xrpc/${method.value}${encodeParameters(parameters)}")
    send(HttpMethod.Get, uri, "application/json", None, bearerToken, extraHeaders).flatMap(_.json)

  /** Executes a Lexicon procedure as POST with a JSON request and response. */
  def procedure(
      method: Nsid,
      input: Json,
      bearerToken: Option[String] = None,
      extraHeaders: Vector[(String, String)] = Vector.empty
  ): Either[XrpcError, Json] =
    val uri = URI.create(s"${service.toString}/xrpc/${method.value}")
    val bytes = input.render.getBytes(StandardCharsets.UTF_8)
    send(
      HttpMethod.Post,
      uri,
      "application/json",
      Some("application/json" -> bytes),
      bearerToken,
      extraHeaders
    ).flatMap(_.json)

  /** Executes a query while retaining an arbitrary binary success body. */
  def queryBytes(
      method: Nsid,
      parameters: Vector[(String, String)] = Vector.empty,
      bearerToken: Option[String] = None,
      accept: String = "*/*",
      extraHeaders: Vector[(String, String)] = Vector.empty
  ): Either[XrpcError, XrpcResponse] =
    val uri = URI.create(s"${service.toString}/xrpc/${method.value}${encodeParameters(parameters)}")
    send(HttpMethod.Get, uri, accept, None, bearerToken, extraHeaders)

  /** Executes a procedure with caller-selected binary content type. */
  def procedureBytes(
      method: Nsid,
      contentType: String,
      input: Array[Byte],
      bearerToken: Option[String] = None,
      accept: String = "application/json",
      extraHeaders: Vector[(String, String)] = Vector.empty
  ): Either[XrpcError, XrpcResponse] =
    val uri = URI.create(s"${service.toString}/xrpc/${method.value}")
    send(HttpMethod.Post, uri, accept, Some(contentType -> input), bearerToken, extraHeaders)

  private def send(
      httpMethod: HttpMethod,
      uri: URI,
      accept: String,
      content: Option[(String, Array[Byte])],
      bearerToken: Option[String],
      extraHeaders: Vector[(String, String)]
  ): Either[XrpcError, XrpcResponse] =
    val baseHeaders = Vector("Accept" -> accept) ++
      content.map((contentType, _) => "Content-Type" -> contentType) ++
      bearerToken.map(token => "Authorization" -> s"Bearer $token")
    val request =
      HttpRequestData(httpMethod, uri, baseHeaders ++ extraHeaders, content.map(_._2), timeout)
    transport.send(request).flatMap { response =>
      if response.body.length > maxResponseBytes then
        Left(XrpcError.ResponseTooLarge(maxResponseBytes, response.body.length))
      else if response.status >= 200 && response.status < 300 then
        Right(XrpcResponse(response.status, response.headers, response.body))
      else Left(decodeRemoteError(response))
    }

  private def decodeRemoteError(response: HttpResponseData): XrpcError =
    val text = String(response.body, StandardCharsets.UTF_8)
    Json.parse(text) match
      case Right(document) =>
        val error = document.field("error").flatMap(_.asString).getOrElse("HTTPError")
        val message = document.optionalField("message").toOption.flatten
          .flatMap(_.asString.toOption)
        XrpcError.Remote(response.status, error, message)
      case Left(parseError) => XrpcError.InvalidResponse(response.status, parseError.toString)

  private def encodeParameters(parameters: Vector[(String, String)]): String =
    if parameters.isEmpty then ""
    else
      parameters
        .map((name, value) => s"${PercentEncoding.query(name)}=${PercentEncoding.query(value)}")
        .mkString("?", "&", "")

object XrpcClient:
  /** Validates and normalizes an origin-level HTTP(S) XRPC service. */
  def create(
      service: URI,
      transport: HttpTransport = JdkHttpTransport.default,
      timeout: Duration = Duration.ofSeconds(20),
      maxResponseBytes: Int = 8 * 1024 * 1024
  ): Either[XrpcError, XrpcClient] =
    val validScheme = service.getScheme == "https" || service.getScheme == "http"
    val path = Option(service.getPath).getOrElse("")
    if !validScheme then Left(XrpcError.InvalidService("service must use http or https"))
    else if service.getHost == null then
      Left(XrpcError.InvalidService("service must include a host"))
    else if service.getQuery != null || service.getFragment != null then
      Left(XrpcError.InvalidService("service must not include query or fragment components"))
    else if path.nonEmpty && path != "/" then
      Left(XrpcError.InvalidService("XRPC service endpoints must be at the origin root"))
    else if maxResponseBytes < 1 then
      Left(XrpcError.InvalidService("maxResponseBytes must be positive"))
    else
      val normalized = URI.create(service.toString.stripSuffix("/"))
      Right(new XrpcClient(normalized, transport, timeout, maxResponseBytes))

private object PercentEncoding:
  private val Hex = "0123456789ABCDEF"

  def query(input: String): String =
    val bytes = input.getBytes(StandardCharsets.UTF_8)
    val out = new java.lang.StringBuilder()
    bytes.foreach { byte =>
      val unsigned = byte & 0xff
      if isUnreserved(unsigned) then out.append(unsigned.toChar)
      else
        out.append('%')
        out.append(Hex.charAt(unsigned >>> 4))
        out.append(Hex.charAt(unsigned & 0x0f))
    }
    out.toString

  private def isUnreserved(value: Int): Boolean =
    (value >= 'a' && value <= 'z') ||
      (value >= 'A' && value <= 'Z') ||
      (value >= '0' && value <= '9') || value == '-' || value == '.' || value == '_' || value == '~'

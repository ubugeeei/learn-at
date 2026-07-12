package learnat.oauth

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import learnat.syntax.AtIdentifier

/** Parameters persisted by a client before it submits a PAR request. */
final case class AuthorizationRequest private (
    clientId: URI,
    redirectUri: URI,
    scopes: Vector[String],
    state: OAuthState,
    pkce: PkcePair,
    loginHint: Option[AtIdentifier]
):
  /** Form fields sent to the pushed authorization request endpoint. */
  def parFields: Vector[(String, String)] = Vector(
    "client_id" -> clientId.toString,
    "response_type" -> "code",
    "redirect_uri" -> redirectUri.toString,
    "scope" -> scopes.mkString(" "),
    "state" -> state.value,
    "code_challenge" -> pkce.challenge,
    "code_challenge_method" -> "S256"
  ) ++ loginHint.map(value => "login_hint" -> value.text)

  /** Encodes `parFields` as `application/x-www-form-urlencoded`. */
  def parBody: Array[Byte] = OAuthForm.encode(parFields)

object AuthorizationRequest:
  /** Creates a public-client request and enforces the profile's core fields. */
  def create(
      clientId: URI,
      redirectUri: URI,
      scopes: Vector[String],
      state: OAuthState = OAuthState.generate(),
      pkce: PkcePair = PkcePair.generate(),
      loginHint: Option[AtIdentifier] = None
  ): Either[OAuthError, AuthorizationRequest] =
    for
      _ <- validateClientId(clientId)
      _ <- validateRedirect(redirectUri)
      _ <- Either.cond(
        scopes.nonEmpty && scopes.contains("atproto"),
        (),
        OAuthError("OAuth scopes must include atproto")
      )
      _ <- Either.cond(
        scopes.distinct.length == scopes.length,
        (),
        OAuthError("OAuth scopes must not contain duplicates")
      )
      _ <- Either.cond(
        scopes.forall(validScope),
        (),
        OAuthError("OAuth scope contains whitespace or a control character")
      )
    yield AuthorizationRequest(clientId, redirectUri, scopes, state, pkce, loginHint)

  private def validateClientId(value: URI): Either[OAuthError, Unit] =
    val secure = value.getScheme == "https" && value.getHost != null && value.getPort == -1 &&
      value.getRawUserInfo == null && value.getRawFragment == null
    val localhost = value.getScheme == "http" && value.getHost == "localhost" &&
      value.getPort == -1 && value.getRawUserInfo == null && value.getRawFragment == null &&
      (Option(value.getRawPath).forall(_.isEmpty) || value.getRawPath == "/")
    Either.cond(
      secure || localhost,
      (),
      OAuthError(
        "client_id must be an HTTPS metadata URL without a port, or the http://localhost development client"
      )
    )

  private def validateRedirect(value: URI): Either[OAuthError, Unit] =
    val absolute = value.isAbsolute && value.getRawFragment == null && value.getRawUserInfo == null
    val secureWeb = value.getScheme == "https" && value.getHost != null
    val loopback = value.getScheme == "http" && Set("127.0.0.1", "[::1]", "::1")
      .contains(value.getHost)
    val native = value.getScheme != null && value.getScheme != "http" &&
      value.getScheme != "https" && Option(value.getRawSchemeSpecificPart).exists(_.startsWith("/"))
    Either.cond(
      absolute && (secureWeb || loopback || native),
      (),
      OAuthError("redirect_uri is not a permitted web, loopback, or native callback URI")
    )

  private def validScope(value: String): Boolean = value.nonEmpty &&
    value.forall(character => character > ' ' && character != 0x7f.toChar)

/** Successful response from the PAR endpoint. */
final case class PushedAuthorizationResponse(requestUri: String, expiresInSeconds: Long):
  /** Builds the browser destination containing only client ID and request URI. */
  def browserUri(metadata: AuthorizationServerMetadata, clientId: URI): URI =
    val query = OAuthForm
      .query(Vector("client_id" -> clientId.toString, "request_uri" -> requestUri))
    URI.create(s"${metadata.authorizationEndpoint}?$query")

object PushedAuthorizationResponse:
  /** Parses the JSON response body; the caller must separately require DPoP-Nonce. */
  def parse(json: learnat.json.Json): Either[OAuthError, PushedAuthorizationResponse] =
    for
      requestUri <- json.field("request_uri").flatMap(_.asString).left
        .map(error => OAuthError(s"request_uri: ${error.message}"))
      _ <- Either.cond(requestUri.nonEmpty, (), OAuthError("request_uri must not be empty"))
      expires <- json.field("expires_in").flatMap(_.asLong).left
        .map(error => OAuthError(s"expires_in: ${error.message}"))
      _ <- Either.cond(expires > 0, (), OAuthError("PAR expires_in must be positive"))
    yield PushedAuthorizationResponse(requestUri, expires)

/** State- and issuer-verified authorization callback outcome. */
enum AuthorizationCallback:
  case Authorized(code: String)
  case Denied(error: String, description: Option[String])

object AuthorizationCallback:
  /** Parses a callback URI, rejecting duplicate parameters and mix-up attacks. */
  def parse(
      uri: URI,
      expectedState: OAuthState,
      expectedIssuer: URI
  ): Either[OAuthError, AuthorizationCallback] =
    for
      parameters <- parseQuery(uri)
      state <- required(parameters, "state")
      _ <- constant(state, expectedState.value, "OAuth callback state does not match")
      issuer <- required(parameters, "iss")
      _ <- Either.cond(
        issuer == expectedIssuer.toString,
        (),
        OAuthError("OAuth callback issuer does not match")
      )
      result <- (parameters.get("code"), parameters.get("error")) match
        case (Some(code), None) if code.nonEmpty   => Right(AuthorizationCallback.Authorized(code))
        case (None, Some(error)) if error.nonEmpty =>
          Right(AuthorizationCallback.Denied(error, parameters.get("error_description")))
        case _ =>
          Left(OAuthError("OAuth callback must contain exactly one non-empty code or error"))
    yield result

  private def parseQuery(uri: URI): Either[OAuthError, Map[String, String]] = Option(
    uri.getRawQuery
  ).toRight(OAuthError("OAuth callback has no query string")).flatMap { query =>
    query.split("&", -1).toVector
      .foldLeft[Either[OAuthError, Map[String, String]]](Right(Map.empty)) { (result, part) =>
        result.flatMap { parameters =>
          val pieces = part.split("=", 2)
          for
            name <- decode(pieces.head)
            value <- decode(if pieces.length == 2 then pieces(1) else "")
            _ <- Either.cond(
              name.nonEmpty,
              (),
              OAuthError("OAuth callback contains an empty parameter name")
            )
            _ <- Either.cond(
              !parameters.contains(name),
              (),
              OAuthError(s"OAuth callback repeats parameter $name")
            )
          yield parameters.updated(name, value)
        }
      }
  }

  private def decode(value: String): Either[OAuthError, String] =
    try Right(URLDecoder.decode(value, StandardCharsets.UTF_8))
    catch
      case _: IllegalArgumentException =>
        Left(OAuthError("OAuth callback contains invalid percent encoding"))

  private def required(parameters: Map[String, String], name: String): Either[OAuthError, String] =
    parameters.get(name).toRight(OAuthError(s"OAuth callback is missing $name"))

  private def constant(
      actual: String,
      expected: String,
      message: String
  ): Either[OAuthError, Unit] =
    val equal = MessageDigest
      .isEqual(actual.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))
    Either.cond(equal, (), OAuthError(message))

/** Form fields for an authorization-code or single-use refresh exchange. */
final case class TokenRequest private (fields: Vector[(String, String)]):
  def body: Array[Byte] = OAuthForm.encode(fields)

object TokenRequest:
  /** Creates the initial code exchange and completes PKCE. */
  def authorizationCode(
      code: String,
      request: AuthorizationRequest
  ): Either[OAuthError, TokenRequest] =
    if code.isEmpty then Left(OAuthError("authorization code must not be empty"))
    else
      Right(TokenRequest(Vector(
        "grant_type" -> "authorization_code",
        "client_id" -> request.clientId.toString,
        "redirect_uri" -> request.redirectUri.toString,
        "code" -> code,
        "code_verifier" -> request.pkce.verifier
      )))

  /** Creates a refresh exchange; callers must serialize refresh-token rotation. */
  def refresh(refreshToken: String, clientId: URI): Either[OAuthError, TokenRequest] =
    if refreshToken.isEmpty then Left(OAuthError("refresh token must not be empty"))
    else
      Right(TokenRequest(Vector(
        "grant_type" -> "refresh_token",
        "client_id" -> clientId.toString,
        "refresh_token" -> refreshToken
      )))

private object OAuthForm:
  def encode(fields: Vector[(String, String)]): Array[Byte] = fields
    .map((name, value) => s"${form(name)}=${form(value)}").mkString("&")
    .getBytes(StandardCharsets.UTF_8)

  def query(fields: Vector[(String, String)]): String = fields
    .map((name, value) => s"${queryComponent(name)}=${queryComponent(value)}").mkString("&")

  private def form(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

  private def queryComponent(value: String): String =
    val encoded = URLEncoder.encode(value, StandardCharsets.UTF_8)
    encoded.replace("+", "%20")

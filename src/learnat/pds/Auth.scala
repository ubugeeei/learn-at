package learnat.pds

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import learnat.json.Json
import learnat.syntax.Did

/** Authentication input or token validation failure. */
final case class AuthError(message: String):
  override def toString: String = message

/**
 * Salted PBKDF2-HMAC-SHA-256 password verifier.
 *
 * The password is accepted as `Array[Char]` so callers can clear their copy.
 * This does not make JVM memory a hardware-backed secret store.
 */
final class PasswordHash private (
    val iterations: Int,
    private val saltBytes: Array[Byte],
    private val expected: Array[Byte]
):
  /** Returns a defensive copy of the non-secret random salt. */
  def salt: Array[Byte] = saltBytes.clone()

  /** Derives and constant-time compares a candidate password. */
  def verify(password: Array[Char]): Boolean =
    PasswordHash.derive(password, saltBytes, iterations, expected.length) match
      case Right(actual) => MessageDigest.isEqual(expected, actual)
      case Left(_) => false

  /** A stable textual form suitable for local configuration persistence. */
  def encoded: String =
    val encoder = Base64.getEncoder.withoutPadding()
    s"pbkdf2-sha256$$$iterations$$${encoder.encodeToString(saltBytes)}$$${encoder.encodeToString(expected)}"

object PasswordHash:
  private val DefaultIterations = 210_000
  private val SaltLength = 16
  private val HashLength = 32

  /** Creates a new verifier with a random 128-bit salt. */
  def create(password: Array[Char], iterations: Int = DefaultIterations): Either[AuthError, PasswordHash] =
    if iterations < 100_000 then Left(AuthError("PBKDF2 iterations must be at least 100000"))
    else
      val salt = Array.ofDim[Byte](SaltLength)
      SecureRandom().nextBytes(salt)
      derive(password, salt, iterations, HashLength).map(hash => new PasswordHash(iterations, salt, hash))

  /** Parses the explicit `pbkdf2-sha256$...` storage format. */
  def parse(value: String): Either[AuthError, PasswordHash] =
    value.split("\\$", -1).toVector match
      case Vector("pbkdf2-sha256", iterationsText, saltText, hashText) =>
        for
          iterations <- iterationsText.toIntOption.toRight(AuthError("invalid PBKDF2 iteration count"))
          _ <- Either.cond(iterations >= 100_000, (), AuthError("PBKDF2 iterations must be at least 100000"))
          salt <- decode(saltText, "salt")
          _ <- Either.cond(salt.length >= SaltLength, (), AuthError("PBKDF2 salt is too short"))
          hash <- decode(hashText, "hash")
          _ <- Either.cond(hash.length >= HashLength, (), AuthError("PBKDF2 hash is too short"))
        yield new PasswordHash(iterations, salt, hash)
      case _ => Left(AuthError("invalid encoded password hash"))

  private[pds] def derive(
      password: Array[Char],
      salt: Array[Byte],
      iterations: Int,
      length: Int
  ): Either[AuthError, Array[Byte]] =
    val spec = PBEKeySpec(password, salt, iterations, length * 8)
    try Right(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded)
    catch case error: Exception => Left(AuthError(s"PBKDF2 failed: ${error.getMessage}"))
    finally spec.clearPassword()

  private def decode(value: String, name: String): Either[AuthError, Array[Byte]] =
    try Right(Base64.getDecoder.decode(value))
    catch case _: IllegalArgumentException => Left(AuthError(s"invalid base64 password $name"))

/** Access and refresh values returned by `com.atproto.server.createSession`. */
final case class SessionTokens(accessJwt: String, refreshJwt: String)

/**
 * Small self-contained legacy token issuer for the local PDS.
 *
 * Tokens are HMAC-bound to this server, carry explicit access/refresh scopes,
 * expire, and can be revoked by JTI. Callers must still migrate user-facing
 * clients to the atproto OAuth profile.
 */
final class SessionStore private (
    secret: Array[Byte],
    nowEpochSeconds: () => Long,
    random: SecureRandom,
    accessLifetimeSeconds: Long,
    refreshLifetimeSeconds: Long
):
  private val revoked = scala.collection.mutable.HashSet.empty[String]
  private val AccessScope = "com.atproto.access"
  private val RefreshScope = "com.atproto.refresh"

  /** Issues independent short-lived access and longer-lived refresh tokens. */
  def issue(did: Did): SessionTokens = synchronized {
    SessionTokens(
      token(did, AccessScope, accessLifetimeSeconds),
      token(did, RefreshScope, refreshLifetimeSeconds)
    )
  }

  /** Validates signature, expiry, revocation, DID syntax, and access scope. */
  def verifyAccess(value: String): Either[AuthError, Did] = verify(value, AccessScope).map(_._1)

  /** Rotates a valid refresh token and revokes the old token's JTI. */
  def refresh(value: String): Either[AuthError, SessionTokens] = synchronized {
    verify(value, RefreshScope).map { (did, jti) =>
      revoked += jti
      issue(did)
    }
  }

  /** Revokes either access or refresh token if it is otherwise authentic. */
  def revoke(value: String): Either[AuthError, Unit] = synchronized {
    decodeAndVerify(value).map { payload =>
      revoked += payload.jti
      ()
    }
  }

  private final case class Payload(did: Did, scope: String, expires: Long, jti: String)

  private def token(did: Did, scope: String, lifetime: Long): String =
    val issued = nowEpochSeconds()
    val jtiBytes = Array.ofDim[Byte](16)
    random.nextBytes(jtiBytes)
    val jti = Base64.getUrlEncoder.withoutPadding().encodeToString(jtiBytes)
    val header = base64Url(Json.obj("alg" -> Json.Str("HS256"), "typ" -> Json.Str("JWT")).render)
    val payload = base64Url(Json.obj(
      "sub" -> Json.Str(did.value),
      "scope" -> Json.Str(scope),
      "iat" -> Json.Num(issued),
      "exp" -> Json.Num(issued + lifetime),
      "jti" -> Json.Str(jti)
    ).render)
    val signingInput = s"$header.$payload"
    s"$signingInput.${Base64.getUrlEncoder.withoutPadding().encodeToString(hmac(signingInput))}"

  private def verify(value: String, expectedScope: String): Either[AuthError, (Did, String)] = synchronized {
    decodeAndVerify(value).flatMap { payload =>
      if payload.scope != expectedScope then Left(AuthError(s"token scope is ${payload.scope}, expected $expectedScope"))
      else Right(payload.did -> payload.jti)
    }
  }

  private def decodeAndVerify(value: String): Either[AuthError, Payload] =
    value.split("\\.", -1).toVector match
      case Vector(headerText, payloadText, signatureText) =>
        val signingInput = s"$headerText.$payloadText"
        for
          signature <- decodeUrl(signatureText, "signature")
          _ <- Either.cond(
            MessageDigest.isEqual(signature, hmac(signingInput)),
            (),
            AuthError("token signature is invalid")
          )
          header <- decodeJson(headerText, "header")
          _ <- Either.cond(
            header.field("alg").flatMap(_.asString).contains("HS256") && header.field("typ").flatMap(_.asString).contains("JWT"),
            (),
            AuthError("unsupported token header")
          )
          json <- decodeJson(payloadText, "payload")
          didText <- json.field("sub").flatMap(_.asString).left.map(error => AuthError(error.message))
          did <- Did.parse(didText).left.map(error => AuthError(error.toString))
          scope <- json.field("scope").flatMap(_.asString).left.map(error => AuthError(error.message))
          expires <- json.field("exp").flatMap(_.asLong).left.map(error => AuthError(error.message))
          jti <- json.field("jti").flatMap(_.asString).left.map(error => AuthError(error.message))
          _ <- Either.cond(expires > nowEpochSeconds(), (), AuthError("token has expired"))
          _ <- Either.cond(!revoked.contains(jti), (), AuthError("token has been revoked"))
        yield Payload(did, scope, expires, jti)
      case _ => Left(AuthError("token must contain three JWT segments"))

  private def hmac(value: String): Array[Byte] =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret, "HmacSHA256"))
    mac.doFinal(value.getBytes(StandardCharsets.US_ASCII))

  private def base64Url(value: String): String =
    Base64.getUrlEncoder.withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8))

  private def decodeUrl(value: String, name: String): Either[AuthError, Array[Byte]] =
    try Right(Base64.getUrlDecoder.decode(value))
    catch case _: IllegalArgumentException => Left(AuthError(s"invalid base64url token $name"))

  private def decodeJson(value: String, name: String): Either[AuthError, Json] =
    decodeUrl(value, name).flatMap { bytes =>
      Json.parse(String(bytes, StandardCharsets.UTF_8)).left.map(error => AuthError(s"invalid token $name: $error"))
    }

object SessionStore:
  /** Creates a production-random local issuer with 15-minute/30-day lifetimes. */
  def secure(): SessionStore =
    val random = SecureRandom()
    val secret = Array.ofDim[Byte](32)
    random.nextBytes(secret)
    new SessionStore(secret, () => System.currentTimeMillis() / 1000L, random, 15 * 60, 30L * 24 * 60 * 60)

  /** Creates a deterministic-time issuer for expiry and scope tests. */
  def testing(
      secret: Array[Byte],
      nowEpochSeconds: () => Long,
      accessLifetimeSeconds: Long = 60,
      refreshLifetimeSeconds: Long = 3600
  ): Either[AuthError, SessionStore] =
    if secret.length < 32 then Left(AuthError("session HMAC secret must contain at least 32 bytes"))
    else if accessLifetimeSeconds < 1 || refreshLifetimeSeconds < accessLifetimeSeconds then
      Left(AuthError("session lifetimes are invalid"))
    else Right(new SessionStore(secret.clone(), nowEpochSeconds, SecureRandom(), accessLifetimeSeconds, refreshLifetimeSeconds))

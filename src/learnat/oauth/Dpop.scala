package learnat.oauth

import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import learnat.crypto.P256
import learnat.crypto.P256KeyPair
import learnat.crypto.P256PublicKey
import learnat.json.Json

/** A public P-256 JWK suitable for DPoP headers and RFC 7638 thumbprints. */
final case class P256Jwk(publicKey: P256PublicKey):
  /** Renders only public key material. */
  def json: Json = Json.obj(
    "kty" -> Json.Str("EC"),
    "crv" -> Json.Str("P-256"),
    "x" -> Json.Str(Base64Url.encode(publicKey.xCoordinate.toArray)),
    "y" -> Json.Str(Base64Url.encode(publicKey.yCoordinate.toArray))
  )

  /** Computes the base64url SHA-256 RFC 7638 JWK thumbprint. */
  def thumbprint: String =
    val canonical = Json.obj(
      "crv" -> Json.Str("P-256"),
      "kty" -> Json.Str("EC"),
      "x" -> Json.Str(Base64Url.encode(publicKey.xCoordinate.toArray)),
      "y" -> Json.Str(Base64Url.encode(publicKey.yCoordinate.toArray))
    ).render
    Base64Url.encode(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)))

object P256Jwk:
  /** Parses a public-only EC JWK and verifies the coordinates are on P-256. */
  def parse(json: Json): Either[OAuthError, P256Jwk] =
    for
      fields <- json.asObject.left.map(error => OAuthError(s"DPoP jwk: ${error.message}"))
      values = fields.toMap
      _ <- Either.cond(!values.contains("d"), (), OAuthError("DPoP jwk must not contain private key material"))
      kty <- string(values, "kty")
      _ <- Either.cond(kty == "EC", (), OAuthError("DPoP jwk kty must be EC"))
      curve <- string(values, "crv")
      _ <- Either.cond(curve == "P-256", (), OAuthError("DPoP jwk curve must be P-256"))
      xText <- string(values, "x")
      x <- Base64Url.decode(xText, "DPoP jwk x")
      yText <- string(values, "y")
      y <- Base64Url.decode(yText, "DPoP jwk y")
      publicKey <- P256.publicKeyFromCoordinates(x, y).left.map(error => OAuthError(error.message))
    yield P256Jwk(publicKey)

  private def string(fields: Map[String, Json], name: String): Either[OAuthError, String] =
    fields.get(name).toRight(OAuthError(s"DPoP jwk is missing $name")).flatMap(
      _.asString.left.map(error => OAuthError(s"DPoP jwk $name: ${error.message}"))
    )

/** Claims authenticated by one DPoP proof. */
final case class VerifiedDpopProof(jti: String, nonce: Option[String], keyThumbprint: String, issuedAt: Long)

/** Creates ES256 DPoP proof JWTs with fresh request IDs. */
object DpopProof:
  /**
   * Signs one request proof. The access token is included only for protected
   * resource calls; PAR and token endpoint proofs omit `ath`.
   */
  def create(
      keyPair: P256KeyPair,
      method: String,
      target: URI,
      nonce: Option[String],
      accessToken: Option[String] = None,
      nowEpochSeconds: Long = System.currentTimeMillis() / 1000L,
      random: SecureRandom = SecureRandom()
  ): Either[OAuthError, String] =
    val jtiBytes = Array.ofDim[Byte](16)
    random.nextBytes(jtiBytes)
    createWithJti(keyPair, method, target, nonce, accessToken, nowEpochSeconds, Base64Url.encode(jtiBytes))

  private[oauth] def createWithJti(
      keyPair: P256KeyPair,
      method: String,
      target: URI,
      nonce: Option[String],
      accessToken: Option[String],
      nowEpochSeconds: Long,
      jti: String
  ): Either[OAuthError, String] =
    for
      htm <- normalizeMethod(method)
      htu <- normalizeTarget(target)
      _ <- Either.cond(nonce.forall(_.nonEmpty), (), OAuthError("DPoP nonce must not be empty"))
      _ <- Either.cond(jti.nonEmpty && jti.length <= 256, (), OAuthError("DPoP jti must contain 1-256 characters"))
      header = Json.obj(
        "typ" -> Json.Str("dpop+jwt"),
        "alg" -> Json.Str("ES256"),
        "jwk" -> P256Jwk(keyPair.publicKey).json
      )
      basePayload = Vector(
        "jti" -> Json.Str(jti),
        "htm" -> Json.Str(htm),
        "htu" -> Json.Str(htu),
        "iat" -> Json.Num(nowEpochSeconds)
      )
      payload = Json.Obj(
        basePayload ++
          nonce.map(value => "nonce" -> Json.Str(value)) ++
          accessToken.map(token => "ath" -> Json.Str(tokenHash(token)))
      )
      encodedHeader = Base64Url.encode(header.render.getBytes(StandardCharsets.UTF_8))
      encodedPayload = Base64Url.encode(payload.render.getBytes(StandardCharsets.UTF_8))
      signingInput = s"$encodedHeader.$encodedPayload"
      signature <- keyPair.sign(signingInput.getBytes(StandardCharsets.US_ASCII)).left.map(error => OAuthError(error.message))
    yield s"$signingInput.${Base64Url.encode(signature.toArray)}"

  private[oauth] def normalizeMethod(method: String): Either[OAuthError, String] =
    val normalized = method.toUpperCase(java.util.Locale.ROOT)
    val valid = normalized.nonEmpty && normalized.forall(character => character >= 'A' && character <= 'Z')
    Either.cond(valid, normalized, OAuthError("DPoP HTTP method must contain ASCII letters"))

  private[oauth] def normalizeTarget(target: URI): Either[OAuthError, String] =
    val scheme = Option(target.getScheme).map(_.toLowerCase(java.util.Locale.ROOT))
    val host = Option(target.getHost).map(_.toLowerCase(java.util.Locale.ROOT))
    val valid = scheme.exists(value => value == "https" || value == "http") && host.nonEmpty &&
      target.getRawUserInfo == null && target.getRawFragment == null
    if !valid then Left(OAuthError("DPoP target must be an absolute HTTP(S) URI without credentials or fragment"))
    else
      val path = Option(target.getRawPath).filter(_.nonEmpty).getOrElse("/")
      val port = target.getPort match
        case 443 if scheme.contains("https") => -1
        case 80 if scheme.contains("http") => -1
        case other => other
      val renderedHost = if host.get.contains(':') then s"[${host.get}]" else host.get
      val renderedPort = if port < 0 then "" else s":$port"
      try Right(URI.create(s"${scheme.get}://$renderedHost$renderedPort$path").toASCIIString)
      catch case error: IllegalArgumentException => Left(OAuthError(s"invalid DPoP target: ${error.getMessage}"))

  private[oauth] def tokenHash(accessToken: String): String =
    Base64Url.encode(MessageDigest.getInstance("SHA-256").digest(accessToken.getBytes(StandardCharsets.US_ASCII)))

/** Server-side verifier for atproto DPoP request proofs. */
object DpopVerifier:
  /**
   * Verifies structure, public JWK, ES256 signature, request binding, nonce,
   * token hash, issuance time, and optional replay cache in that order.
   */
  def verify(
      proof: String,
      expectedMethod: String,
      expectedTarget: URI,
      expectedNonce: Option[String],
      accessToken: Option[String],
      nowEpochSeconds: Long,
      replayCache: Option[DpopReplayCache] = None,
      maximumAgeSeconds: Long = 60,
      clockSkewSeconds: Long = 5
  ): Either[OAuthError, VerifiedDpopProof] =
    if proof.length > 16 * 1024 then Left(OAuthError("DPoP proof exceeds 16 KiB"))
    else if maximumAgeSeconds < 1 || clockSkewSeconds < 0 then Left(OAuthError("invalid DPoP verification time policy"))
    else proof.split("\\.", -1).toVector match
      case Vector(headerText, payloadText, signatureText) =>
        val signingInput = s"$headerText.$payloadText"
        for
          header <- decodeJson(headerText, "DPoP header")
          _ <- expectString(header, "typ", "dpop+jwt")
          _ <- expectString(header, "alg", "ES256")
          jwkJson <- header.field("jwk").left.map(error => OAuthError(s"DPoP header jwk: ${error.message}"))
          jwk <- P256Jwk.parse(jwkJson)
          signature <- Base64Url.decode(signatureText, "DPoP signature")
          _ <- Either.cond(
            jwk.publicKey.verify(signingInput.getBytes(StandardCharsets.US_ASCII), signature),
            (),
            OAuthError("DPoP signature is invalid")
          )
          payload <- decodeJson(payloadText, "DPoP payload")
          jti <- stringField(payload, "jti")
          _ <- Either.cond(jti.nonEmpty && jti.length <= 256, (), OAuthError("DPoP jti must contain 1-256 characters"))
          htm <- stringField(payload, "htm")
          method <- DpopProof.normalizeMethod(expectedMethod)
          _ <- Either.cond(htm == method, (), OAuthError("DPoP htm does not match the HTTP request"))
          htu <- stringField(payload, "htu")
          target <- DpopProof.normalizeTarget(expectedTarget)
          _ <- Either.cond(htu == target, (), OAuthError("DPoP htu does not match the HTTP request"))
          issuedAt <- longField(payload, "iat")
          _ <- Either.cond(issuedAt <= nowEpochSeconds + clockSkewSeconds, (), OAuthError("DPoP proof was issued in the future"))
          _ <- Either.cond(issuedAt >= nowEpochSeconds - maximumAgeSeconds - clockSkewSeconds, (), OAuthError("DPoP proof is too old"))
          nonce <- optionalStringField(payload, "nonce")
          _ <- (nonce, expectedNonce) match
            case (Some(actual), Some(expected)) => constantString(actual, expected, "DPoP nonce does not match")
            case (None, None) => Right(())
            case (None, Some(_)) => Left(OAuthError("DPoP proof requires the server nonce"))
            case (Some(_), None) => Left(OAuthError("initial DPoP proof must not contain a nonce"))
          ath <- optionalStringField(payload, "ath")
          _ <- accessToken match
            case Some(token) => ath match
              case Some(actual) => constantString(actual, DpopProof.tokenHash(token), "DPoP access-token hash does not match")
              case None => Left(OAuthError("protected-resource DPoP proof requires ath"))
            case None => Either.cond(ath.isEmpty, (), OAuthError("PAR/token DPoP proof must not contain ath"))
          verified = VerifiedDpopProof(jti, nonce, jwk.thumbprint, issuedAt)
          _ <- replayCache.fold[Either[OAuthError, Unit]](Right(()))(
            _.accept(verified, nowEpochSeconds, maximumAgeSeconds + clockSkewSeconds)
          )
        yield verified
      case _ => Left(OAuthError("DPoP proof must contain three JWT segments"))

  private def decodeJson(encoded: String, label: String): Either[OAuthError, Json] =
    Base64Url.decode(encoded, label).flatMap { bytes =>
      try
        val decoder = StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = decoder.decode(ByteBuffer.wrap(bytes)).toString
        Json.parse(text).left.map(error => OAuthError(s"$label is not valid JSON: $error"))
      catch case _: java.nio.charset.CharacterCodingException => Left(OAuthError(s"$label is not valid UTF-8"))
    }

  private def expectString(json: Json, name: String, expected: String): Either[OAuthError, Unit] =
    stringField(json, name).flatMap(actual => Either.cond(actual == expected, (), OAuthError(s"DPoP $name must be $expected")))

  private def stringField(json: Json, name: String): Either[OAuthError, String] =
    json.field(name).flatMap(_.asString).left.map(error => OAuthError(s"DPoP $name: ${error.message}"))

  private def optionalStringField(json: Json, name: String): Either[OAuthError, Option[String]] =
    json.optionalField(name).left.map(error => OAuthError(error.message)).flatMap {
      case None => Right(None)
      case Some(value) => value.asString.left.map(error => OAuthError(s"DPoP $name: ${error.message}")).map(Some.apply)
    }

  private def longField(json: Json, name: String): Either[OAuthError, Long] =
    json.field(name).flatMap(_.asLong).left.map(error => OAuthError(s"DPoP $name: ${error.message}"))

  private def constantString(actual: String, expected: String, message: String): Either[OAuthError, Unit] =
    val matches = MessageDigest.isEqual(actual.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))
    Either.cond(matches, (), OAuthError(message))

/** Bounded fail-closed replay cache for verified DPoP key/JTI pairs. */
final class DpopReplayCache(maxEntries: Int):
  require(maxEntries > 0, "maxEntries must be positive")
  private val entries = scala.collection.mutable.HashMap.empty[String, Long]

  /** Records a proof once, rejecting duplicates and capacity exhaustion. */
  def accept(proof: VerifiedDpopProof, nowEpochSeconds: Long, retainSeconds: Long): Either[OAuthError, Unit] = synchronized {
    entries.filterInPlace((_, expires) => expires > nowEpochSeconds)
    val key = s"${proof.keyThumbprint}:${proof.jti}"
    if entries.contains(key) then Left(OAuthError("DPoP proof replay detected"))
    else if entries.size >= maxEntries then Left(OAuthError("DPoP replay cache capacity exhausted"))
    else
      entries.put(key, nowEpochSeconds + math.max(1L, retainSeconds))
      Right(())
  }

/** Rotating mandatory server nonce with a short previous-value overlap. */
final class DpopNonceManager private (
    generate: () => String,
    lifetimeSeconds: Long,
    overlapSeconds: Long,
    initialNow: Long
):
  private var current = generate() -> initialNow
  private var previous: Option[(String, Long)] = None

  /** Returns the current nonce, rotating after at most five minutes. */
  def issue(nowEpochSeconds: Long): String = synchronized {
    rotateIfNeeded(nowEpochSeconds)
    current._1
  }

  /** Accepts the current nonce or the previous nonce during its overlap. */
  def accepts(value: String, nowEpochSeconds: Long): Boolean = synchronized {
    rotateIfNeeded(nowEpochSeconds)
    secureEquals(value, current._1) || previous.exists { (nonce, expires) =>
      expires > nowEpochSeconds && secureEquals(value, nonce)
    }
  }

  private def rotateIfNeeded(now: Long): Unit =
    if now - current._2 >= lifetimeSeconds then
      previous = Some(current._1 -> (now + overlapSeconds))
      current = generate() -> now

  private def secureEquals(left: String, right: String): Boolean =
    MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8))

object DpopNonceManager:
  /** Creates a secure nonce manager; profile nonce lifetime is capped at 300 seconds. */
  def secure(
      nowEpochSeconds: Long,
      lifetimeSeconds: Long = 240,
      overlapSeconds: Long = 30,
      random: SecureRandom = SecureRandom()
  ): Either[OAuthError, DpopNonceManager] =
    create(
      () =>
        val bytes = Array.ofDim[Byte](32)
        random.nextBytes(bytes)
        Base64Url.encode(bytes),
      nowEpochSeconds,
      lifetimeSeconds,
      overlapSeconds
    )

  /** Creates a deterministic manager for rotation tests. */
  def testing(
      values: Iterator[String],
      nowEpochSeconds: Long,
      lifetimeSeconds: Long,
      overlapSeconds: Long
  ): Either[OAuthError, DpopNonceManager] =
    create(
      () => if values.hasNext then values.next() else throw IllegalStateException("test nonce sequence exhausted"),
      nowEpochSeconds,
      lifetimeSeconds,
      overlapSeconds
    )

  private def create(
      generate: () => String,
      now: Long,
      lifetime: Long,
      overlap: Long
  ): Either[OAuthError, DpopNonceManager] =
    if lifetime < 1 || lifetime > 300 then Left(OAuthError("DPoP nonce lifetime must be 1-300 seconds"))
    else if overlap < 0 || overlap >= lifetime then Left(OAuthError("DPoP nonce overlap must be non-negative and shorter than its lifetime"))
    else Right(new DpopNonceManager(generate, lifetime, overlap, now))

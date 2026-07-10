package learnat.crypto

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Arrays
import learnat.ipld.ByteString

/** A malformed key, signature, or unsupported cryptographic representation. */
final case class CryptoError(message: String):
  override def toString: String = message

/** Minimal base58btc codec used by atproto `z` multibase public keys. */
object Base58Btc:
  private val Alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
  private val Radix = BigInteger.valueOf(58)

  /** Encodes bytes without a multibase prefix; zero bytes become leading `1` characters. */
  def encode(bytes: Array[Byte]): String =
    if bytes.isEmpty then ""
    else
      val leadingZeros = bytes.takeWhile(_ == 0).length
      var value = BigInteger(1, bytes)
      val encoded = new java.lang.StringBuilder()
      while value.signum() > 0 do
        val division = value.divideAndRemainder(Radix)
        encoded.append(Alphabet.charAt(division(1).intValue()))
        value = division(0)
      ("1" * leadingZeros) + encoded.reverse.toString

  /** Decodes an unprefixed base58btc string and preserves leading zero bytes. */
  def decode(value: String): Either[CryptoError, Array[Byte]] =
    if value.isEmpty then Right(Array.emptyByteArray)
    else
      var number = BigInteger.ZERO
      var index = 0
      var failure: Option[CryptoError] = None
      while index < value.length && failure.isEmpty do
        val digit = Alphabet.indexOf(value.charAt(index))
        if digit < 0 then failure = Some(CryptoError(s"invalid base58btc character '${value.charAt(index)}' at index $index"))
        else number = number.multiply(Radix).add(BigInteger.valueOf(digit.toLong))
        index += 1
      failure.toLeft {
        val signed = number.toByteArray
        val magnitude = if signed.length > 1 && signed.head == 0 then signed.tail else if number == BigInteger.ZERO then Array.emptyByteArray else signed
        Array.fill[Byte](value.takeWhile(_ == '1').length)(0) ++ magnitude
      }

/**
 * A validated P-256 public key in the current atproto Multikey representation.
 *
 * The wire representation is `z` multibase, the two-byte `p256-pub` multicodec
 * prefix (`0x80 0x24`), and a 33-byte SEC1 compressed public key.
 */
final class P256PublicKey private[crypto] (private val key: ECPublicKey):
  /** Returns the 33-byte compressed SEC1 point. */
  def compressedBytes: ByteString = ByteString(P256.compress(key))

  /** Returns the current DID-document `publicKeyMultibase` string. */
  def multikey: String = s"z${Base58Btc.encode(P256.MulticodecPrefix ++ compressedBytes.toArray)}"

  /** Returns the `did:key` form of this public key. */
  def didKey: String = s"did:key:$multikey"

  /**
   * Verifies an atproto compact ECDSA signature.
   * DER signatures, out-of-range scalars, and malleable high-S signatures fail.
   */
  def verify(message: Array[Byte], compactSignature: Array[Byte]): Boolean =
    P256.verify(key, message, compactSignature)

  private[crypto] def javaKey: PublicKey = key

/** P-256 private/public key pair used to sign repository commits. */
final class P256KeyPair private (private val privateKey: PrivateKey, val publicKey: P256PublicKey):
  /** Signs bytes with SHA-256/ECDSA and returns the canonical 64-byte low-S form. */
  def sign(message: Array[Byte]): Either[CryptoError, ByteString] =
    try
      val signer = Signature.getInstance("SHA256withECDSA")
      signer.initSign(privateKey)
      signer.update(message)
      P256.derToCompact(signer.sign()).map(ByteString.apply)
    catch case error: Exception => Left(CryptoError(s"P-256 signing failed: ${error.getMessage}"))

  /** Exports the standard PKCS#8 private key encoding for local persistence. */
  def privateKeyPkcs8: ByteString = ByteString(privateKey.getEncoded)

object P256KeyPair:
  /** Generates a new P-256 key using the configured JCA secure random source. */
  def generate(): Either[CryptoError, P256KeyPair] =
    try
      val generator = KeyPairGenerator.getInstance("EC")
      generator.initialize(ECGenParameterSpec("secp256r1"))
      val pair = generator.generateKeyPair()
      pair.getPublic match
        case public: ECPublicKey => Right(new P256KeyPair(pair.getPrivate, new P256PublicKey(public)))
        case _ => Left(CryptoError("JCA provider did not return an EC public key"))
    catch case error: Exception => Left(CryptoError(s"P-256 generation failed: ${error.getMessage}"))

  /** Restores a PKCS#8 private key and independently encoded public multikey. */
  def restore(pkcs8: Array[Byte], publicMultikey: String): Either[CryptoError, P256KeyPair] =
    for
      public <- P256.publicKeyFromMultikey(publicMultikey)
      privateKey <-
        try Right(KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8)))
        catch case error: Exception => Left(CryptoError(s"invalid PKCS#8 P-256 private key: ${error.getMessage}"))
    yield new P256KeyPair(privateKey, public)

/** P-256 encoding and signature operations required by the atproto cryptography profile. */
object P256:
  private[crypto] val MulticodecPrefix = Array(0x80.toByte, 0x24.toByte)
  private val FieldPrime = BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
  private val CurveB = BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)
  private val Order = BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)
  private val HalfOrder = Order.shiftRight(1)
  private val CoordinateLength = 32

  /** Parses `z...` current Multikey encoding and validates that the point lies on P-256. */
  def publicKeyFromMultikey(value: String): Either[CryptoError, P256PublicKey] =
    if !value.startsWith("z") then Left(CryptoError("P-256 multikey must use base58btc multibase prefix 'z'"))
    else
      Base58Btc.decode(value.drop(1)).flatMap { bytes =>
        if bytes.length != MulticodecPrefix.length + 33 then Left(CryptoError(s"P-256 multikey must contain 35 bytes, found ${bytes.length}"))
        else if !Arrays.equals(bytes.take(2), MulticodecPrefix) then Left(CryptoError("multikey is not prefixed with the p256-pub multicodec"))
        else decompress(bytes.drop(2)).map(new P256PublicKey(_))
      }

  /** Parses a `did:key:z...` P-256 identifier. */
  def publicKeyFromDidKey(value: String): Either[CryptoError, P256PublicKey] =
    if !value.startsWith("did:key:") then Left(CryptoError("expected did:key identifier"))
    else publicKeyFromMultikey(value.drop(8))

  private[crypto] def compress(key: ECPublicKey): Array[Byte] =
    val point = key.getW
    val prefix = if point.getAffineY.testBit(0) then 0x03.toByte else 0x02.toByte
    Array(prefix) ++ fixedUnsigned(point.getAffineX, CoordinateLength)

  private def decompress(bytes: Array[Byte]): Either[CryptoError, ECPublicKey] =
    if bytes.length != 33 || (bytes.head != 0x02.toByte && bytes.head != 0x03.toByte) then
      Left(CryptoError("compressed P-256 key must be 33 bytes beginning with 0x02 or 0x03"))
    else
      try
        val x = BigInteger(1, bytes.tail)
        if x.compareTo(FieldPrime) >= 0 then Left(CryptoError("P-256 x coordinate is outside the field"))
        else
          val ySquared = x.modPow(BigInteger.valueOf(3), FieldPrime)
            .subtract(x.multiply(BigInteger.valueOf(3)))
            .add(CurveB)
            .mod(FieldPrime)
          val candidate = ySquared.modPow(FieldPrime.add(BigInteger.ONE).shiftRight(2), FieldPrime)
          if candidate.multiply(candidate).mod(FieldPrime) != ySquared then Left(CryptoError("compressed key is not a P-256 curve point"))
          else
            val odd = bytes.head == 0x03.toByte
            val y = if candidate.testBit(0) == odd then candidate else FieldPrime.subtract(candidate)
            val factory = KeyFactory.getInstance("EC")
            val key = factory.generatePublic(ECPublicKeySpec(ECPoint(x, y), parameters))
            key match
              case public: ECPublicKey => Right(public)
              case _ => Left(CryptoError("JCA provider did not decode an EC public key"))
      catch case error: Exception => Left(CryptoError(s"invalid compressed P-256 key: ${error.getMessage}"))

  private def parameters: ECParameterSpec =
    val parameters = AlgorithmParameters.getInstance("EC")
    parameters.init(ECGenParameterSpec("secp256r1"))
    parameters.getParameterSpec(classOf[ECParameterSpec])

  private[crypto] def verify(key: PublicKey, message: Array[Byte], compact: Array[Byte]): Boolean =
    if compact.length != 64 then false
    else
      val r = BigInteger(1, compact.take(32))
      val s = BigInteger(1, compact.drop(32))
      if r.signum() <= 0 || r.compareTo(Order) >= 0 || s.signum() <= 0 || s.compareTo(HalfOrder) > 0 then false
      else
        try
          val verifier = Signature.getInstance("SHA256withECDSA")
          verifier.initVerify(key)
          verifier.update(message)
          verifier.verify(compactToDer(r, s))
        catch case _: Exception => false

  /** Converts JCA DER output into fixed-width atproto form and normalizes S. */
  private[crypto] def derToCompact(der: Array[Byte]): Either[CryptoError, Array[Byte]] =
    parseDer(der).flatMap { (r, originalS) =>
      if r.signum() <= 0 || r.compareTo(Order) >= 0 || originalS.signum() <= 0 || originalS.compareTo(Order) >= 0 then
        Left(CryptoError("ECDSA signature scalar is outside the P-256 order"))
      else
        val s = if originalS.compareTo(HalfOrder) > 0 then Order.subtract(originalS) else originalS
        Right(fixedUnsigned(r, 32) ++ fixedUnsigned(s, 32))
    }

  private def parseDer(der: Array[Byte]): Either[CryptoError, (BigInteger, BigInteger)] =
    try
      var offset = 0
      if read(der, offset) != 0x30 then return Left(CryptoError("ECDSA signature is not a DER sequence"))
      offset += 1
      val (sequenceLength, afterSequenceLength) = readLength(der, offset)
      offset = afterSequenceLength
      if offset + sequenceLength != der.length then return Left(CryptoError("invalid DER sequence length"))
      val (r, afterR) = readInteger(der, offset)
      val (s, afterS) = readInteger(der, afterR)
      if afterS != der.length then Left(CryptoError("trailing bytes in DER ECDSA signature")) else Right(r -> s)
    catch case error: IllegalArgumentException => Left(CryptoError(error.getMessage))

  private def readInteger(bytes: Array[Byte], start: Int): (BigInteger, Int) =
    if read(bytes, start) != 0x02 then throw IllegalArgumentException("expected DER integer")
    val (length, valueStart) = readLength(bytes, start + 1)
    if length < 1 || valueStart + length > bytes.length then throw IllegalArgumentException("truncated DER integer")
    val encoded = bytes.slice(valueStart, valueStart + length)
    if (encoded.head & 0x80) != 0 then throw IllegalArgumentException("negative DER integer")
    if encoded.length > 1 && encoded.head == 0 && (encoded(1) & 0x80) == 0 then throw IllegalArgumentException("non-minimal DER integer")
    BigInteger(1, encoded) -> (valueStart + length)

  private def readLength(bytes: Array[Byte], start: Int): (Int, Int) =
    val first = read(bytes, start)
    if first < 128 then first -> (start + 1)
    else
      val count = first & 0x7f
      if count < 1 || count > 2 || start + 1 + count > bytes.length then throw IllegalArgumentException("unsupported DER length")
      var value = 0
      var index = 0
      while index < count do
        value = (value << 8) | read(bytes, start + 1 + index)
        index += 1
      value -> (start + 1 + count)

  private def read(bytes: Array[Byte], offset: Int): Int =
    if offset >= bytes.length then throw IllegalArgumentException("truncated DER ECDSA signature") else bytes(offset) & 0xff

  private def compactToDer(r: BigInteger, s: BigInteger): Array[Byte] =
    val encodedR = derInteger(r)
    val encodedS = derInteger(s)
    val contentLength = 2 + encodedR.length + 2 + encodedS.length
    val out = ByteArrayOutputStream()
    out.write(0x30)
    writeDerLength(contentLength, out)
    out.write(0x02)
    writeDerLength(encodedR.length, out)
    out.write(encodedR)
    out.write(0x02)
    writeDerLength(encodedS.length, out)
    out.write(encodedS)
    out.toByteArray

  private def derInteger(value: BigInteger): Array[Byte] =
    val bytes = value.toByteArray
    if bytes.head == 0 && bytes.length > 1 && (bytes(1) & 0x80) == 0 then bytes.tail else bytes

  private def writeDerLength(length: Int, out: ByteArrayOutputStream): Unit =
    if length < 128 then out.write(length)
    else
      out.write(0x81)
      out.write(length)

  private def fixedUnsigned(value: BigInteger, length: Int): Array[Byte] =
    val signed = value.toByteArray
    val magnitude = if signed.length > length && signed.head == 0 then signed.tail else signed
    if magnitude.length > length then throw IllegalArgumentException(s"integer does not fit in $length bytes")
    Array.fill[Byte](length - magnitude.length)(0) ++ magnitude

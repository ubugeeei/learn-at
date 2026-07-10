package learnat.ipld

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Arrays

final class ByteString private (private val data: Array[Byte]):
  def size: Int = data.length
  def apply(index: Int): Byte = data(index)
  def toArray: Array[Byte] = data.clone()

  override def equals(other: Any): Boolean = other match
    case bytes: ByteString => Arrays.equals(data, bytes.data)
    case _ => false

  override def hashCode(): Int = Arrays.hashCode(data)
  override def toString: String = data.map(byte => f"${byte & 0xff}%02x").mkString

object ByteString:
  def apply(bytes: Array[Byte]): ByteString = new ByteString(bytes.clone())
  val empty: ByteString = ByteString(Array.emptyByteArray)

enum Ipld:
  case Null
  case Bool(value: Boolean)
  case Integer(value: Long)
  case Text(value: String)
  case Bytes(value: ByteString)
  case List(value: Vector[Ipld])
  case Map(fields: Vector[(String, Ipld)])
  case Link(value: Cid)

object Ipld:
  def obj(fields: (String, Ipld)*): Ipld =
    val sorted = fields.toVector.sortWith { (left, right) =>
      val leftBytes = left._1.getBytes(StandardCharsets.UTF_8)
      val rightBytes = right._1.getBytes(StandardCharsets.UTF_8)
      val length = java.lang.Integer.compare(leftBytes.length, rightBytes.length)
      if length != 0 then length < 0 else Arrays.compareUnsigned(leftBytes, rightBytes) < 0
    }
    Map(sorted)
  def list(values: Ipld*): Ipld = List(values.toVector)

final case class IpldError(message: String, offset: Option[Int] = None):
  override def toString: String = offset.fold(message)(value => s"$message at byte $value")

final class Cid private (val codec: Long, val bytes: ByteString):
  override def toString: String = s"b${Base32.encode(bytes.toArray)}"
  override def equals(other: Any): Boolean = other match
    case cid: Cid => bytes == cid.bytes
    case _ => false
  override def hashCode(): Int = bytes.hashCode()

  def verifies(content: Array[Byte]): Boolean =
    val expected = bytes.toArray.takeRight(32)
    val actual = MessageDigest.getInstance("SHA-256").digest(content)
    MessageDigest.isEqual(expected, actual)

object Cid:
  val DagCborCodec = 0x71L
  val RawCodec = 0x55L
  private val Sha256Code = 0x12L
  private val Sha256Length = 32L

  def forDagCbor(content: Array[Byte]): Cid = create(DagCborCodec, content)
  def forRaw(content: Array[Byte]): Cid = create(RawCodec, content)

  def create(codec: Long, content: Array[Byte]): Cid =
    val digest = MessageDigest.getInstance("SHA-256").digest(content)
    val bytes = Varint.encode(1) ++ Varint.encode(codec) ++ Varint.encode(Sha256Code) ++ Varint.encode(Sha256Length) ++ digest
    new Cid(codec, ByteString(bytes))

  def parse(value: String): Either[IpldError, Cid] =
    if !value.startsWith("b") then Left(IpldError("CID must use lower-case base32 multibase"))
    else Base32.decode(value.drop(1)).flatMap(parseBytes)

  def parseBytes(bytes: Array[Byte]): Either[IpldError, Cid] =
    readPrefix(bytes, 0).flatMap { (cid, next) =>
      if next == bytes.length then Right(cid) else Left(IpldError("trailing bytes after CID", Some(next)))
    }

  def readPrefix(bytes: Array[Byte], start: Int): Either[IpldError, (Cid, Int)] =
    for
      versionResult <- Varint.decode(bytes, start)
      (version, afterVersion) = versionResult
      _ <- Either.cond(version == 1, (), IpldError(s"unsupported CID version: $version", Some(start)))
      codecResult <- Varint.decode(bytes, afterVersion)
      (codec, afterCodec) = codecResult
      hashResult <- Varint.decode(bytes, afterCodec)
      (hashCode, afterHash) = hashResult
      _ <- Either.cond(hashCode == Sha256Code, (), IpldError(s"unsupported multihash code: $hashCode", Some(afterCodec)))
      lengthResult <- Varint.decode(bytes, afterHash)
      (length, afterLength) = lengthResult
      _ <- Either.cond(length == Sha256Length, (), IpldError(s"unsupported SHA-256 digest length: $length", Some(afterHash)))
      end <-
        val candidate = afterLength.toLong + length
        Either.cond(candidate <= bytes.length, candidate.toInt, IpldError("truncated CID digest", Some(afterLength)))
      cidBytes = bytes.slice(start, end)
    yield new Cid(codec, ByteString(cidBytes)) -> end

object Varint:
  def encode(value: Long): Array[Byte] =
    require(value >= 0, "varint only supports non-negative longs")
    val out = ByteArrayOutputStream()
    var remaining = value
    while (remaining & ~0x7fL) != 0 do
      out.write(((remaining & 0x7fL) | 0x80L).toInt)
      remaining = remaining >>> 7
    out.write(remaining.toInt)
    out.toByteArray

  def decode(bytes: Array[Byte], start: Int): Either[IpldError, (Long, Int)] =
    var value = 0L
    var shift = 0
    var index = start
    var complete = false
    while index < bytes.length && !complete && shift <= 63 do
      val current = bytes(index) & 0xff
      if shift == 63 && (current & 0xfe) != 0 then return Left(IpldError("varint overflows 64 bits", Some(index)))
      value |= (current & 0x7f).toLong << shift
      index += 1
      if (current & 0x80) == 0 then complete = true else shift += 7
    if !complete then Left(IpldError("truncated varint", Some(index)))
    else if index - start > 1 && (bytes(index - 1) & 0x7f) == 0 then Left(IpldError("non-minimal varint", Some(start)))
    else Right(value -> index)

object Base32:
  private val Alphabet = "abcdefghijklmnopqrstuvwxyz234567"

  def encode(bytes: Array[Byte]): String =
    val out = new java.lang.StringBuilder()
    var buffer = 0
    var bits = 0
    bytes.foreach { byte =>
      buffer = (buffer << 8) | (byte & 0xff)
      bits += 8
      while bits >= 5 do
        bits -= 5
        out.append(Alphabet.charAt((buffer >>> bits) & 31))
      buffer &= (1 << bits) - 1
    }
    if bits > 0 then out.append(Alphabet.charAt((buffer << (5 - bits)) & 31))
    out.toString

  def decode(value: String): Either[IpldError, Array[Byte]] =
    if value.isEmpty then Left(IpldError("empty base32 value"))
    else
      val out = ByteArrayOutputStream()
      var buffer = 0
      var bits = 0
      var index = 0
      while index < value.length do
        val digit = Alphabet.indexOf(value.charAt(index))
        if digit < 0 then return Left(IpldError(s"invalid lower-case base32 character: ${value.charAt(index)}", Some(index)))
        buffer = (buffer << 5) | digit
        bits += 5
        if bits >= 8 then
          bits -= 8
          out.write((buffer >>> bits) & 0xff)
        buffer &= (1 << bits) - 1
        index += 1
      if bits > 0 && buffer != 0 then Left(IpldError("non-zero base32 padding bits"))
      else Right(out.toByteArray)

object DagCbor:
  final case class Limits(maxDepth: Int = 128, maxBytes: Int = 4 * 1024 * 1024)

  def encode(value: Ipld): Either[IpldError, Array[Byte]] =
    val out = ByteArrayOutputStream()
    encodeValue(value, out).map(_ => out.toByteArray)

  def decode(bytes: Array[Byte], limits: Limits = Limits()): Either[IpldError, Ipld] =
    if bytes.length > limits.maxBytes then Left(IpldError(s"DAG-CBOR input exceeds ${limits.maxBytes} bytes"))
    else
      val decoder = Decoder(bytes, limits)
      decoder.value(1).flatMap { decoded =>
        if decoder.atEnd then Right(decoded) else Left(IpldError("trailing bytes after DAG-CBOR value", Some(decoder.offset)))
      }

  /**
   * Decodes a concatenated sequence of canonical DAG-CBOR values.
   * Event-stream frames use this form for a header followed by a body.
   */
  def decodeSequence(
      bytes: Array[Byte],
      limits: Limits = Limits(),
      maxItems: Int = 100
  ): Either[IpldError, Vector[Ipld]] =
    if bytes.length > limits.maxBytes then Left(IpldError(s"DAG-CBOR input exceeds ${limits.maxBytes} bytes"))
    else if maxItems < 1 then Left(IpldError("DAG-CBOR sequence maxItems must be positive"))
    else
      val decoder = Decoder(bytes, limits)
      val values = Vector.newBuilder[Ipld]
      var count = 0
      var failure: Option[IpldError] = None
      while !decoder.atEnd && failure.isEmpty do
        if count >= maxItems then failure = Some(IpldError(s"DAG-CBOR sequence exceeds $maxItems items", Some(decoder.offset)))
        else
          decoder.value(1) match
            case Right(value) =>
              values += value
              count += 1
            case Left(error) => failure = Some(error)
      failure.toLeft(values.result())

  private def encodeValue(value: Ipld, out: ByteArrayOutputStream): Either[IpldError, Unit] = value match
    case Ipld.Null =>
      out.write(0xf6)
      Right(())
    case Ipld.Bool(boolean) =>
      out.write(if boolean then 0xf5 else 0xf4)
      Right(())
    case Ipld.Integer(integer) =>
      if integer >= 0 then writeTypeAndLength(0, integer, out)
      else writeTypeAndLength(1, -1L - integer, out)
      Right(())
    case Ipld.Text(text) =>
      val bytes = text.getBytes(StandardCharsets.UTF_8)
      writeTypeAndLength(3, bytes.length, out)
      out.write(bytes)
      Right(())
    case Ipld.Bytes(value) =>
      val bytes = value.toArray
      writeTypeAndLength(2, bytes.length, out)
      out.write(bytes)
      Right(())
    case Ipld.List(values) =>
      writeTypeAndLength(4, values.length, out)
      values.foldLeft[Either[IpldError, Unit]](Right(()))((result, item) => result.flatMap(_ => encodeValue(item, out)))
    case Ipld.Map(fields) => encodeMap(fields, out)
    case Ipld.Link(cid) =>
      writeTypeAndLength(6, 42, out)
      val cidBytes = Array(0.toByte) ++ cid.bytes.toArray
      writeTypeAndLength(2, cidBytes.length, out)
      out.write(cidBytes)
      Right(())

  private def encodeMap(fields: Vector[(String, Ipld)], out: ByteArrayOutputStream): Either[IpldError, Unit] =
    val duplicates = fields.groupMapReduce(_._1)(_ => 1)(_ + _).collect { case (key, count) if count > 1 => key }
    if duplicates.nonEmpty then Left(IpldError(s"duplicate DAG-CBOR map keys: ${duplicates.mkString(", ")}"))
    else
      val sorted = fields.map { (key, value) => (key.getBytes(StandardCharsets.UTF_8), key, value) }
        .sortWith((left, right) => compareKeys(left._1, right._1) < 0)
      writeTypeAndLength(5, sorted.length, out)
      sorted.foldLeft[Either[IpldError, Unit]](Right(())) { case (result, (keyBytes, _, value)) =>
        result.flatMap { _ =>
          writeTypeAndLength(3, keyBytes.length, out)
          out.write(keyBytes)
          encodeValue(value, out)
        }
      }

  private def writeTypeAndLength(major: Int, value: Long, out: ByteArrayOutputStream): Unit =
    if value < 24 then out.write((major << 5) | value.toInt)
    else if value <= 0xffL then
      out.write((major << 5) | 24)
      out.write(value.toInt)
    else if value <= 0xffffL then
      out.write((major << 5) | 25)
      out.write((value >>> 8).toInt)
      out.write(value.toInt)
    else if value <= 0xffffffffL then
      out.write((major << 5) | 26)
      writeLong(value, 4, out)
    else
      out.write((major << 5) | 27)
      writeLong(value, 8, out)

  private def writeLong(value: Long, count: Int, out: ByteArrayOutputStream): Unit =
    var shift = (count - 1) * 8
    while shift >= 0 do
      out.write((value >>> shift).toInt & 0xff)
      shift -= 8

  private def compareKeys(left: Array[Byte], right: Array[Byte]): Int =
    val length = Integer.compare(left.length, right.length)
    if length != 0 then length
    else Arrays.compareUnsigned(left, right)

  private final class Decoder(bytes: Array[Byte], limits: Limits):
    var offset: Int = 0
    def atEnd: Boolean = offset == bytes.length

    def value(depth: Int): Either[IpldError, Ipld] =
      if depth > limits.maxDepth then fail(s"DAG-CBOR nesting exceeds ${limits.maxDepth}")
      else if offset >= bytes.length then fail("unexpected end of DAG-CBOR input")
      else
        val initialOffset = offset
        val initial = readByte()
        val major = initial >>> 5
        val additional = initial & 31
        major match
          case 0 => unsigned(additional, initialOffset).flatMap(number =>
            if number <= Long.MaxValue then Right(Ipld.Integer(number)) else fail("positive integer exceeds signed 64-bit range")
          )
          case 1 => unsigned(additional, initialOffset).flatMap(number =>
            if number <= Long.MaxValue then Right(Ipld.Integer(-1L - number)) else fail("negative integer exceeds signed 64-bit range")
          )
          case 2 => byteString(additional, initialOffset).map(value => Ipld.Bytes(ByteString(value)))
          case 3 => text(additional, initialOffset).map(Ipld.Text.apply)
          case 4 => sequence(additional, initialOffset, depth)
          case 5 => map(additional, initialOffset, depth)
          case 6 => link(additional, initialOffset, depth)
          case 7 => additional match
            case 20 => Right(Ipld.Bool(false))
            case 21 => Right(Ipld.Bool(true))
            case 22 => Right(Ipld.Null)
            case _ => fail("floats, undefined, and other CBOR simple values are not allowed")
          case _ => fail(s"unsupported CBOR major type: $major")

    private def sequence(additional: Int, initialOffset: Int, depth: Int): Either[IpldError, Ipld] =
      length(additional, initialOffset).flatMap { count =>
        Vector.fill(count)(()).foldLeft[Either[IpldError, Vector[Ipld]]](Right(Vector.empty)) { (result, _) =>
          result.flatMap(values => value(depth + 1).map(values :+ _))
        }.map(Ipld.List.apply)
      }

    private def map(additional: Int, initialOffset: Int, depth: Int): Either[IpldError, Ipld] =
      length(additional, initialOffset).flatMap { count =>
        var previous: Option[Array[Byte]] = None
        Vector.fill(count)(()).foldLeft[Either[IpldError, Vector[(String, Ipld)]]](Right(Vector.empty)) { (result, _) =>
          result.flatMap { fields =>
            val keyStart = offset
            if keyStart >= bytes.length || ((bytes(keyStart) & 0xff) >>> 5) != 3 then fail("DAG-CBOR map keys must be strings")
            else
              val initial = readByte()
              text(initial & 31, keyStart).flatMap { key =>
                val keyBytes = key.getBytes(StandardCharsets.UTF_8)
                if previous.exists(value => compareKeys(value, keyBytes) >= 0) then fail("DAG-CBOR map keys are duplicate or not in canonical order")
                else
                  previous = Some(keyBytes)
                  value(depth + 1).map(item => fields :+ (key -> item))
              }
          }
        }.map(Ipld.Map.apply)
      }

    private def link(additional: Int, initialOffset: Int, depth: Int): Either[IpldError, Ipld] =
      val _ = depth
      unsigned(additional, initialOffset).flatMap { tag =>
        if tag != 42 then fail(s"unsupported DAG-CBOR tag: $tag")
        else if offset >= bytes.length || ((bytes(offset) & 0xff) >>> 5) != 2 then fail("CID tag must contain a byte string")
        else
          val bytesOffset = offset
          val initial = readByte()
          byteString(initial & 31, bytesOffset).flatMap { value =>
            if value.isEmpty || value.head != 0 then fail("CID byte string must start with identity multibase byte 0")
            else Cid.parseBytes(value.tail).map(Ipld.Link.apply)
          }
      }

    private def text(additional: Int, initialOffset: Int): Either[IpldError, String] =
      byteString(additional, initialOffset).flatMap { value =>
        try
          val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
          Right(decoder.decode(ByteBuffer.wrap(value)).toString)
        catch case _: java.nio.charset.CharacterCodingException => fail("invalid UTF-8 string")
      }

    private def byteString(additional: Int, initialOffset: Int): Either[IpldError, Array[Byte]] =
      length(additional, initialOffset).flatMap { count =>
        if count > bytes.length - offset then fail("truncated byte or text string")
        else
          val value = bytes.slice(offset, offset + count)
          offset += count
          Right(value)
      }

    private def length(additional: Int, initialOffset: Int): Either[IpldError, Int] =
      unsigned(additional, initialOffset).flatMap { value =>
        if value > Int.MaxValue then fail("collection or string length exceeds supported range")
        else Right(value.toInt)
      }

    private def unsigned(additional: Int, initialOffset: Int): Either[IpldError, Long] = additional match
      case value if value < 24 => Right(value.toLong)
      case 24 => readUnsigned(1).flatMap(value => minimal(value, 24, initialOffset))
      case 25 => readUnsigned(2).flatMap(value => minimal(value, 256, initialOffset))
      case 26 => readUnsigned(4).flatMap(value => minimal(value, 65536, initialOffset))
      case 27 => readUnsigned(8).flatMap(value => minimal(value, 4294967296L, initialOffset))
      case 31 => fail("indefinite-length CBOR is not allowed")
      case _ => fail("reserved CBOR additional information")

    private def minimal(value: Long, minimum: Long, initialOffset: Int): Either[IpldError, Long] =
      if value < minimum then Left(IpldError("non-minimal CBOR integer or length encoding", Some(initialOffset)))
      else Right(value)

    private def readUnsigned(count: Int): Either[IpldError, Long] =
      if count > bytes.length - offset then fail("truncated CBOR integer")
      else
        var value = 0L
        var index = 0
        while index < count do
          value = (value << 8) | readByte().toLong
          index += 1
        if value < 0 then fail("unsigned CBOR value exceeds signed implementation range") else Right(value)

    private def readByte(): Int =
      val value = bytes(offset) & 0xff
      offset += 1
      value

    private def fail[A](message: String): Left[IpldError, A] = Left(IpldError(message, Some(offset)))

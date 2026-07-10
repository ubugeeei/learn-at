package learnat.syntax

import java.security.SecureRandom

final case class SyntaxError(kind: String, input: String, message: String):
  override def toString: String = s"invalid $kind '$input': $message"

sealed abstract class ValidatedString protected (val value: String):
  final override def toString: String = value
  final override def hashCode(): Int = 31 * getClass.hashCode + value.hashCode
  final override def equals(other: Any): Boolean = other match
    case identifier: ValidatedString => getClass == identifier.getClass && value == identifier.value
    case _ => false

final class Did private (value: String) extends ValidatedString(value):
  def method: String = value.split(':')(1)
  def isSupportedByAtproto: Boolean = method == "plc" || method == "web"

object Did:
  private val Pattern = "^did:[a-z]+:[a-zA-Z0-9._:%-]*[a-zA-Z0-9._-]$".r

  def parse(input: String): Either[SyntaxError, Did] =
    if input.length > 2048 then invalid(input, "exceeds 2048 characters")
    else if Pattern.matches(input) then Right(new Did(input))
    else invalid(input, "expected did:<lowercase-method>:<method-specific-id>")

  private def invalid(input: String, message: String): Left[SyntaxError, Did] =
    Left(SyntaxError("DID", input, message))

final class Handle private (value: String) extends ValidatedString(value):
  def normalized: String = value.toLowerCase(java.util.Locale.ROOT)

object Handle:
  private val Pattern =
    "^([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$".r

  def parse(input: String): Either[SyntaxError, Handle] =
    if input.length > 253 then invalid(input, "exceeds 253 characters")
    else if Pattern.matches(input) then Right(new Handle(input))
    else invalid(input, "expected a domain name with at least two labels")

  private def invalid(input: String, message: String): Left[SyntaxError, Handle] =
    Left(SyntaxError("handle", input, message))

enum AtIdentifier:
  case DidIdentifier(value: Did)
  case HandleIdentifier(value: Handle)

  def text: String = this match
    case DidIdentifier(did) => did.value
    case HandleIdentifier(handle) => handle.value

object AtIdentifier:
  def parse(input: String): Either[SyntaxError, AtIdentifier] =
    if input.startsWith("did:") then Did.parse(input).map(AtIdentifier.DidIdentifier.apply)
    else Handle.parse(input).map(AtIdentifier.HandleIdentifier.apply)

final class Nsid private (value: String) extends ValidatedString(value):
  private lazy val segments = value.split('.').toVector
  def name: String = segments.last
  def authority: String = segments.init.reverse.mkString(".")
  def normalized: String = (segments.init.map(_.toLowerCase(java.util.Locale.ROOT)) :+ name).mkString(".")

object Nsid:
  private val AllowedSegment = "^[a-zA-Z0-9-]+$".r
  private val Name = "^[a-zA-Z][a-zA-Z0-9]{0,62}$".r

  def parse(input: String): Either[SyntaxError, Nsid] =
    val segments = input.split("\\.", -1).toVector
    val authority = segments.dropRight(1)
    val valid =
      input.length <= 317 &&
        segments.length >= 3 &&
        segments.forall(segment =>
          segment.nonEmpty &&
            segment.length <= 63 &&
            AllowedSegment.matches(segment) &&
            !segment.startsWith("-") &&
            !segment.endsWith("-")
        ) &&
        authority.headOption.exists(_.headOption.exists(_.isLetter)) &&
        segments.lastOption.exists(Name.matches)
    if valid then Right(new Nsid(input))
    else Left(SyntaxError("NSID", input, "expected reversed domain authority and an alphanumeric name"))

final class RecordKey private (value: String) extends ValidatedString(value)

object RecordKey:
  private val Pattern = "^[a-zA-Z0-9_~.:-]{1,512}$".r

  def parse(input: String): Either[SyntaxError, RecordKey] =
    if input != "." && input != ".." && Pattern.matches(input) then Right(new RecordKey(input))
    else
      Left(
        SyntaxError(
          "record key",
          input,
          "expected 1-512 characters from A-Z, a-z, 0-9, _, ~, ., :, -; '.' and '..' are forbidden"
        )
      )

final case class AtUri private (
    authority: AtIdentifier,
    collection: Option[Nsid],
    recordKey: Option[RecordKey],
    fragment: Option[String]
):
  require(recordKey.isEmpty || collection.nonEmpty, "record key requires a collection")

  override def toString: String =
    val path = collection.fold("")(value => s"/${value.value}${recordKey.fold("")(key => s"/${key.value}")}")
    val hash = fragment.fold("")(value => s"#$value")
    s"at://${authority.text}$path$hash"

object AtUri:
  private val Allowed = "^[a-zA-Z0-9._~:@!$&'()*+,;=%/\\[\\]#?-]+$".r
  private val Fragment = "^/[a-zA-Z0-9._~:@!$&')(*+,;=%\\[\\]/-]*$".r

  def parse(input: String): Either[SyntaxError, AtUri] =
    if input.length > 8192 then invalid(input, "exceeds 8192 characters")
    else if !input.startsWith("at://") then invalid(input, "must start with at://")
    else if !Allowed.matches(input) then invalid(input, "contains a disallowed character")
    else if input.contains('?') then invalid(input, "query components are not allowed in strict AT URIs")
    else
      splitFragment(input).flatMap { (withoutFragment, fragment) =>
        val rest = withoutFragment.drop(5)
        val parts = rest.split("/", -1).toVector
        if parts.isEmpty || parts.head.isEmpty then invalid(input, "authority is empty")
        else if parts.length > 3 then invalid(input, "may have at most collection and record-key path segments")
        else if parts.drop(1).exists(_.isEmpty) then invalid(input, "contains an empty or trailing path segment")
        else
          for
            authority <- AtIdentifier.parse(parts.head).left.map(error => retag(input, error.message))
            collection <- parts.lift(1) match
              case Some(value) => Nsid.parse(value).map(Some.apply).left.map(error => retag(input, error.message))
              case None => Right(None)
            recordKey <- parts.lift(2) match
              case Some(value) => RecordKey.parse(value).map(Some.apply).left.map(error => retag(input, error.message))
              case None => Right(None)
          yield AtUri(authority, collection, recordKey, fragment)
      }

  def record(did: Did, collection: Nsid, recordKey: RecordKey): AtUri =
    AtUri(AtIdentifier.DidIdentifier(did), Some(collection), Some(recordKey), None)

  private def splitFragment(input: String): Either[SyntaxError, (String, Option[String])] =
    val firstHash = input.indexOf('#')
    if firstHash < 0 then Right(input -> None)
    else
      val value = input.substring(firstHash + 1)
      if input.indexOf('#', firstHash + 1) >= 0 then invalid(input, "contains multiple fragments")
      else if !Fragment.matches(value) then invalid(input, "fragment must be a valid JSON pointer beginning with '/'")
      else if !hasValidPercentEncoding(value) then invalid(input, "fragment has invalid percent encoding")
      else Right(input.substring(0, firstHash) -> Some(value))

  private def hasValidPercentEncoding(value: String): Boolean =
    var index = 0
    var valid = true
    while index < value.length && valid do
      if value.charAt(index) == '%' then
        valid = index + 2 < value.length && isHex(value.charAt(index + 1)) && isHex(value.charAt(index + 2))
        index += 3
      else index += 1
    valid

  private def isHex(char: Char): Boolean =
    (char >= '0' && char <= '9') || (char >= 'a' && char <= 'f') || (char >= 'A' && char <= 'F')

  private def retag(input: String, message: String): SyntaxError = SyntaxError("AT URI", input, message)
  private def invalid[A](input: String, message: String): Left[SyntaxError, A] = Left(retag(input, message))

final class Tid private (value: String, val timestampMicros: Long, val clockId: Int)
    extends ValidatedString(value):
  def newerThan(other: Tid): Boolean = value > other.value

object Tid:
  private val Alphabet = "234567abcdefghijklmnopqrstuvwxyz"
  private val Pattern = "^[234567abcdefghij][234567abcdefghijklmnopqrstuvwxyz]{12}$".r

  def parse(input: String): Either[SyntaxError, Tid] =
    if !Pattern.matches(input) then
      Left(SyntaxError("TID", input, "expected 13 base32-sortable characters with the high bit clear"))
    else
      val raw = input.foldLeft(0L) { (acc, char) => (acc << 5) | Alphabet.indexOf(char).toLong }
      Right(new Tid(input, raw >>> 10, (raw & 0x3ffL).toInt))

  def fromParts(timestampMicros: Long, clockId: Int): Either[SyntaxError, Tid] =
    if timestampMicros < 0 || timestampMicros >= (1L << 53) then
      Left(SyntaxError("TID timestamp", timestampMicros.toString, "must fit in 53 non-negative bits"))
    else if clockId < 0 || clockId > 1023 then
      Left(SyntaxError("TID clock id", clockId.toString, "must fit in 10 bits"))
    else
      val raw = (timestampMicros << 10) | clockId.toLong
      val chars = Array.fill(13)('2')
      var current = raw
      var index = chars.length - 1
      while index >= 0 do
        chars(index) = Alphabet.charAt((current & 31L).toInt)
        current = current >>> 5
        index -= 1
      val encoded = String(chars)
      Right(new Tid(encoded, timestampMicros, clockId))

final class TidGenerator private (nowMicros: () => Long, clockId: Int):
  private var lastTimestamp = -1L

  def next(previous: Option[Tid] = None): Tid = synchronized {
    val afterLocal = math.max(nowMicros(), lastTimestamp + 1)
    val timestamp = previous.fold(afterLocal)(value => math.max(afterLocal, value.timestampMicros + 1))
    val tid = Tid.fromParts(timestamp, clockId).fold(error => throw IllegalStateException(error.toString), identity)
    lastTimestamp = timestamp
    tid
  }

object TidGenerator:
  def system(): TidGenerator =
    val random = SecureRandom()
    new TidGenerator(() => System.currentTimeMillis() * 1000L, random.nextInt(1024))

  def deterministic(nowMicros: () => Long, clockId: Int): Either[SyntaxError, TidGenerator] =
    if clockId < 0 || clockId > 1023 then
      Left(SyntaxError("TID clock id", clockId.toString, "must fit in 10 bits"))
    else Right(new TidGenerator(nowMicros, clockId))


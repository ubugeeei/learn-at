package learnat.json

import scala.collection.mutable

enum Json:
  case Null
  case Bool(value: Boolean)
  case Num(value: BigDecimal)
  case Str(value: String)
  case Arr(value: Vector[Json])
  case Obj(fields: Vector[(String, Json)])

  def render: String = Json.render(this)

  def field(name: String): Either[Json.AccessError, Json] = this match
    case Json.Obj(fields) =>
      fields.find(_._1 == name).map(_._2).toRight(Json.AccessError(s"missing field: $name"))
    case other => Left(Json.AccessError(s"expected object, found ${other.kind}"))

  def optionalField(name: String): Either[Json.AccessError, Option[Json]] = this match
    case Json.Obj(fields) => Right(fields.find(_._1 == name).map(_._2))
    case other => Left(Json.AccessError(s"expected object, found ${other.kind}"))

  def asString: Either[Json.AccessError, String] = this match
    case Json.Str(value) => Right(value)
    case other => Left(Json.AccessError(s"expected string, found ${other.kind}"))

  def asBoolean: Either[Json.AccessError, Boolean] = this match
    case Json.Bool(value) => Right(value)
    case other => Left(Json.AccessError(s"expected boolean, found ${other.kind}"))

  def asLong: Either[Json.AccessError, Long] = this match
    case Json.Num(value) if value.isWhole && value.isValidLong => Right(value.toLong)
    case Json.Num(_) => Left(Json.AccessError("expected 64-bit integer"))
    case other => Left(Json.AccessError(s"expected number, found ${other.kind}"))

  def asArray: Either[Json.AccessError, Vector[Json]] = this match
    case Json.Arr(value) => Right(value)
    case other => Left(Json.AccessError(s"expected array, found ${other.kind}"))

  def asObject: Either[Json.AccessError, Vector[(String, Json)]] = this match
    case Json.Obj(fields) => Right(fields)
    case other => Left(Json.AccessError(s"expected object, found ${other.kind}"))

  def kind: String = this match
    case Json.Null => "null"
    case Json.Bool(_) => "boolean"
    case Json.Num(_) => "number"
    case Json.Str(_) => "string"
    case Json.Arr(_) => "array"
    case Json.Obj(_) => "object"

object Json:
  final case class Limits(maxDepth: Int = 128, maxInputChars: Int = 2 * 1024 * 1024)
  final case class ParseError(message: String, offset: Int, line: Int, column: Int):
    override def toString: String = s"$message at line $line, column $column (offset $offset)"

  final case class AccessError(message: String):
    override def toString: String = message

  def obj(fields: (String, Json)*): Json = Obj(fields.toVector)
  def arr(values: Json*): Json = Arr(values.toVector)

  def parse(input: String, limits: Limits = Limits()): Either[ParseError, Json] =
    if input.length > limits.maxInputChars then
      Left(ParseError(s"input exceeds ${limits.maxInputChars} characters", 0, 1, 1))
    else if limits.maxDepth < 1 then
      Left(ParseError("maxDepth must be at least 1", 0, 1, 1))
    else Parser(input, limits).parseDocument()

  def render(value: Json): String =
    val out = new java.lang.StringBuilder()
    append(value, out)
    out.toString

  private def append(value: Json, out: java.lang.StringBuilder): Unit = value match
    case Null => out.append("null")
    case Bool(boolean) => out.append(if boolean then "true" else "false")
    case Num(number) =>
      val normalized = number.bigDecimal.stripTrailingZeros
      out.append(if normalized.signum == 0 then "0" else normalized.toPlainString)
    case Str(string) => appendString(string, out)
    case Arr(values) =>
      out.append('[')
      values.zipWithIndex.foreach { (item, index) =>
        if index > 0 then out.append(',')
        append(item, out)
      }
      out.append(']')
    case Obj(fields) =>
      out.append('{')
      fields.zipWithIndex.foreach { case ((key, item), index) =>
        if index > 0 then out.append(',')
        appendString(key, out)
        out.append(':')
        append(item, out)
      }
      out.append('}')

  private def appendString(value: String, out: java.lang.StringBuilder): Unit =
    out.append('"')
    value.foreach {
      case '"' => out.append("\\\"")
      case '\\' => out.append("\\\\")
      case '\b' => out.append("\\b")
      case '\f' => out.append("\\f")
      case '\n' => out.append("\\n")
      case '\r' => out.append("\\r")
      case '\t' => out.append("\\t")
      case char if char < ' ' => out.append(f"\\u${char.toInt}%04x")
      case char => out.append(char)
    }
    out.append('"')

  private final class Parser(input: String, limits: Limits):
    private var offset = 0

    def parseDocument(): Either[ParseError, Json] =
      skipWhitespace()
      parseValue(1).flatMap { value =>
        skipWhitespace()
        if atEnd then Right(value) else fail("unexpected trailing input")
      }

    private def parseValue(depth: Int): Either[ParseError, Json] =
      if depth > limits.maxDepth then fail(s"nesting exceeds maxDepth ${limits.maxDepth}")
      else
        current match
          case Some('n') => keyword("null", Null)
          case Some('t') => keyword("true", Bool(true))
          case Some('f') => keyword("false", Bool(false))
          case Some('"') => parseString().map(Str.apply)
          case Some('[') => parseArray(depth)
          case Some('{') => parseObject(depth)
          case Some(char) if char == '-' || char.isDigit => parseNumber()
          case Some(_) => fail("expected a JSON value")
          case None => fail("unexpected end of input")

    private def keyword(expected: String, value: Json): Either[ParseError, Json] =
      if input.regionMatches(offset, expected, 0, expected.length) then
        offset += expected.length
        Right(value)
      else fail(s"expected $expected")

    private def parseArray(depth: Int): Either[ParseError, Json] =
      offset += 1
      skipWhitespace()
      if consume(']') then Right(Arr(Vector.empty))
      else
        val values = Vector.newBuilder[Json]
        var done = false
        var failure: Option[ParseError] = None
        while !done && failure.isEmpty do
          parseValue(depth + 1) match
            case Right(value) => values += value
            case Left(error) => failure = Some(error)
          if failure.isEmpty then
            skipWhitespace()
            if consume(']') then done = true
            else if consume(',') then skipWhitespace()
            else failure = Some(error("expected ',' or ']'"))
        failure.toLeft(Arr(values.result()))

    private def parseObject(depth: Int): Either[ParseError, Json] =
      offset += 1
      skipWhitespace()
      if consume('}') then Right(Obj(Vector.empty))
      else
        val fields = Vector.newBuilder[(String, Json)]
        val keys = mutable.HashSet.empty[String]
        var done = false
        var failure: Option[ParseError] = None
        while !done && failure.isEmpty do
          if current.contains('"') then
            parseString() match
              case Left(parseError) => failure = Some(parseError)
              case Right(key) if keys.contains(key) => failure = Some(error(s"duplicate object key: $key"))
              case Right(key) =>
                keys += key
                skipWhitespace()
                if !consume(':') then failure = Some(error("expected ':'"))
                else
                  skipWhitespace()
                  parseValue(depth + 1) match
                    case Right(value) => fields += key -> value
                    case Left(parseError) => failure = Some(parseError)
          else failure = Some(error("expected an object key string"))
          if failure.isEmpty then
            skipWhitespace()
            if consume('}') then done = true
            else if consume(',') then skipWhitespace()
            else failure = Some(error("expected ',' or '}'"))
        failure.toLeft(Obj(fields.result()))

    private def parseString(): Either[ParseError, String] =
      if !consume('"') then fail("expected string")
      else
        val out = new java.lang.StringBuilder()
        var closed = false
        var failure: Option[ParseError] = None
        while !closed && failure.isEmpty && !atEnd do
          val char = input.charAt(offset)
          offset += 1
          char match
            case '"' => closed = true
            case '\\' =>
              parseEscape() match
                case Right(value) => out.append(value)
                case Left(parseError) => failure = Some(parseError)
            case control if control < ' ' => failure = Some(error("unescaped control character in string"))
            case value => out.append(value)
        failure match
          case Some(parseError) => Left(parseError)
          case None if !closed => fail("unterminated string")
          case None => Right(out.toString)

    private def parseEscape(): Either[ParseError, String] =
      if atEnd then fail("unterminated escape sequence")
      else
        val escaped = input.charAt(offset)
        offset += 1
        escaped match
          case '"' => Right("\"")
          case '\\' => Right("\\")
          case '/' => Right("/")
          case 'b' => Right("\b")
          case 'f' => Right("\f")
          case 'n' => Right("\n")
          case 'r' => Right("\r")
          case 't' => Right("\t")
          case 'u' => parseUnicodeEscape()
          case _ => fail("invalid escape sequence")

    private def parseUnicodeEscape(): Either[ParseError, String] =
      readHexCodeUnit().flatMap { first =>
        if Character.isHighSurrogate(first.toChar) then
          if offset + 2 <= input.length && input.startsWith("\\u", offset) then
            offset += 2
            readHexCodeUnit().flatMap { second =>
              if Character.isLowSurrogate(second.toChar) then
                Right(String(Character.toChars(Character.toCodePoint(first.toChar, second.toChar))))
              else fail("high surrogate must be followed by a low surrogate")
            }
          else fail("high surrogate must be followed by a unicode escape")
        else if Character.isLowSurrogate(first.toChar) then fail("unexpected low surrogate")
        else Right(first.toChar.toString)
      }

    private def readHexCodeUnit(): Either[ParseError, Int] =
      if offset + 4 > input.length then fail("incomplete unicode escape")
      else
        val digits = input.substring(offset, offset + 4)
        if digits.forall(isHexDigit) then
          offset += 4
          Right(Integer.parseInt(digits, 16))
        else fail("invalid unicode escape")

    private def parseNumber(): Either[ParseError, Json] =
      val start = offset
      consume('-')
      if consume('0') then
        if current.exists(_.isDigit) then return fail("leading zero in number")
      else if current.exists(char => char >= '1' && char <= '9') then
        while current.exists(_.isDigit) do offset += 1
      else return fail("invalid number")

      if consume('.') then
        if !current.exists(_.isDigit) then return fail("fraction requires a digit")
        while current.exists(_.isDigit) do offset += 1

      if current.exists(char => char == 'e' || char == 'E') then
        offset += 1
        if current.exists(char => char == '+' || char == '-') then offset += 1
        if !current.exists(_.isDigit) then return fail("exponent requires a digit")
        while current.exists(_.isDigit) do offset += 1

      val token = input.substring(start, offset)
      try Right(Num(BigDecimal(token)))
      catch case _: NumberFormatException => fail("number is out of range")

    private def isHexDigit(char: Char): Boolean =
      (char >= '0' && char <= '9') || (char >= 'a' && char <= 'f') || (char >= 'A' && char <= 'F')

    private def skipWhitespace(): Unit =
      while current.exists(char => char == ' ' || char == '\n' || char == '\r' || char == '\t') do
        offset += 1

    private def consume(expected: Char): Boolean =
      if current.contains(expected) then
        offset += 1
        true
      else false

    private def current: Option[Char] = if atEnd then None else Some(input.charAt(offset))
    private def atEnd: Boolean = offset >= input.length
    private def fail[A](message: String): Left[ParseError, A] = Left(error(message))

    private def error(message: String): ParseError =
      val prefix = input.substring(0, math.min(offset, input.length))
      val line = prefix.count(_ == '\n') + 1
      val lastNewline = prefix.lastIndexOf('\n')
      val column = offset - lastNewline
      ParseError(message, offset, line, column)


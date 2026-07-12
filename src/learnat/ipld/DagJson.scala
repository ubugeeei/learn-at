package learnat.ipld

import java.util.Base64
import learnat.json.Json

/** Lossless conversion between the atproto JSON encoding and the IPLD data model. */
object DagJson:
  /**
   * Decodes JSON-facing Lexicon data.
   *
   * JSON numbers must be integral signed 64-bit values. Exact single-field
   * `$link` and `$bytes` objects are interpreted as IPLD extension values.
   */
  def decode(json: Json): Either[IpldError, Ipld] = json match
    case Json.Null => Right(Ipld.Null)
    case Json.Bool(value) => Right(Ipld.Bool(value))
    case Json.Num(value) if value.isWhole && value.isValidLong => Right(Ipld.Integer(value.toLong))
    case Json.Num(_) => Left(IpldError("atproto JSON numbers must be signed 64-bit integers"))
    case Json.Str(value) => Right(Ipld.Text(value))
    case Json.Arr(values) =>
      values.foldLeft[Either[IpldError, Vector[Ipld]]](Right(Vector.empty)) { (result, value) =>
        for
          decoded <- result
          item <- decode(value)
        yield decoded :+ item
      }.map(Ipld.List.apply)
    case Json.Obj(Vector(("$link", Json.Str(value)))) =>
      Cid.parse(value).left.map(error => IpldError(s"invalid $$link: $error")).map(Ipld.Link.apply)
    case Json.Obj(Vector(("$bytes", Json.Str(value)))) =>
      try Right(Ipld.Bytes(ByteString(Base64.getDecoder.decode(value))))
      catch case _: IllegalArgumentException => Left(IpldError("invalid base64 in $bytes"))
    case Json.Obj(fields) =>
      fields.foldLeft[Either[IpldError, Vector[(String, Ipld)]]](Right(Vector.empty)) {
        case (result, (key, value)) =>
          for
            decoded <- result
            item <- decode(value)
          yield decoded :+ (key -> item)
      }.map(Ipld.Map.apply)

  /** Encodes an IPLD value using the JSON representation defined by the data model. */
  def encode(value: Ipld): Json = value match
    case Ipld.Null => Json.Null
    case Ipld.Bool(boolean) => Json.Bool(boolean)
    case Ipld.Integer(integer) => Json.Num(BigDecimal(integer))
    case Ipld.Text(text) => Json.Str(text)
    case Ipld.Bytes(bytes) => Json.obj("$bytes" -> Json.Str(Base64.getEncoder.encodeToString(bytes.toArray)))
    case Ipld.List(values) => Json.Arr(values.map(encode))
    case Ipld.Map(fields) => Json.Obj(fields.map((key, item) => key -> encode(item)))
    case Ipld.Link(cid) => Json.obj("$link" -> Json.Str(cid.toString))


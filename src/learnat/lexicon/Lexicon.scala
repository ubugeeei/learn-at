package learnat.lexicon

import learnat.json.Json
import learnat.syntax.Nsid
import learnat.syntax.RecordKey

/** A structural or semantic problem in a Lexicon document. */
final case class LexiconError(path: String, message: String):
  override def toString: String = s"$path: $message"

/** A local (`#name`) or global (`com.example.schema#name`) definition reference. */
final case class LexiconRef(document: Option[Nsid], definition: String):
  /** Renders the reference in its canonical source form. */
  override def toString: String = document.fold(s"#$definition") { id =>
    if definition == "main" then id.value else s"${id.value}#$definition"
  }

object LexiconRef:
  private val DefinitionName = "^[A-Za-z][A-Za-z0-9]{0,63}$".r

  /** Parses a reference without resolving or loading its target document. */
  def parse(value: String): Either[String, LexiconRef] =
    if value.startsWith("#") then definition(value.drop(1)).map(LexiconRef(None, _))
    else
      val parts = value.split("#", -1).toVector
      parts match
        case Vector(idText) => Nsid.parse(idText).left.map(_.toString)
            .map(id => LexiconRef(Some(id), "main"))
        case Vector(idText, name) =>
          for
            id <- Nsid.parse(idText).left.map(_.toString)
            validName <- definition(name)
          yield LexiconRef(Some(id), validName)
        case _ => Left("reference may contain at most one fragment")

  private def definition(value: String): Either[String, String] = Either.cond(
    DefinitionName.matches(value),
    value,
    "definition name must be 1-64 alphanumeric characters starting with a letter"
  )

/** Data schemas supported by the dependency-free runtime validator. */
enum Schema:
  case BooleanValue(constant: Option[Boolean])
  case IntegerValue(
      minimum: Option[Long],
      maximum: Option[Long],
      allowed: Option[Set[Long]],
      constant: Option[Long]
  )
  case StringValue(
      format: Option[String],
      minBytes: Option[Int],
      maxBytes: Option[Int],
      minGraphemes: Option[Int],
      maxGraphemes: Option[Int],
      allowed: Option[Set[String]],
      constant: Option[String]
  )
  case BytesValue(minimum: Option[Int], maximum: Option[Int])
  case CidLink
  case Blob(accept: Vector[String], maxSize: Option[Long])
  case ArrayValue(items: Schema, minimum: Option[Int], maximum: Option[Int])
  case ObjectValue(
      properties: Vector[(String, Schema)],
      required: Set[String],
      nullable: Set[String]
  )
  case Reference(target: LexiconRef)
  case Union(variants: Vector[LexiconRef], closed: Boolean)
  case Unknown

/** A named definition that may be referenced from a schema. */
enum Definition:
  /** A repository record definition. Records require an NSID `$type`. */
  case Record(key: String, schema: Schema.ObjectValue)

  /** A reusable concrete data schema such as an object or constrained string. */
  case Data(schema: Schema)

  /** A symbolic name with no encoded data representation. */
  case Token

  /** XRPC or permission metadata retained as a named primary kind. */
  case Primary(kind: String)

/** One parsed Lexicon v1 file and its named definitions. */
final case class LexiconDocument(id: Nsid, definitions: Map[String, Definition]):
  /** Resolves a definition name within this document. */
  def definition(name: String): Option[Definition] = definitions.get(name)

object LexiconDocument:
  final case class Limits(
      maxSchemaDepth: Int = 64,
      maxDefinitions: Int = 1024,
      maxProperties: Int = 4096
  )

  /** Parses and validates the structural subset needed for data validation. */
  def parse(json: Json, limits: Limits = Limits()): Either[LexiconError, LexiconDocument] =
    for
      _ <- requireVersion(json)
      idText <- requiredString(json, "id", "$")
      id <- Nsid.parse(idText).left.map(error => LexiconError("$.id", error.toString))
      rawDefinitions <- requiredObject(json, "defs", "$")
      _ <- check(
        rawDefinitions.nonEmpty && rawDefinitions.length <= limits.maxDefinitions,
        "$.defs",
        s"must contain 1-${limits.maxDefinitions} definitions"
      )
      definitions <- traverseFields(rawDefinitions) { (name, value) =>
        for
          _ <- LexiconRef.parse(s"#$name").left
            .map(message => LexiconError(s"$$.defs.$name", message))
          definition <- parseDefinition(value, s"$$.defs.$name", limits, depth = 1)
        yield name -> definition
      }
      primary = definitions.collect { case (name, Definition.Record(_, _)) => name } ++
        definitions.collect { case (name, Definition.Primary(_)) => name }
      _ <- check(primary.length <= 1, "$.defs", "may contain at most one primary definition")
      _ <- primary.headOption match
        case Some("main") | None => Right(())
        case Some(name)          =>
          Left(LexiconError(s"$$.defs.$name", "primary definition must be named main"))
    yield LexiconDocument(id, definitions.toMap)

  private def requireVersion(json: Json): Either[LexiconError, Unit] =
    requiredLong(json, "lexicon", "$")
      .flatMap(value => check(value == 1, "$.lexicon", "only Lexicon version 1 is supported"))

  private def parseDefinition(
      json: Json,
      path: String,
      limits: Limits,
      depth: Int
  ): Either[LexiconError, Definition] = requiredString(json, "type", path).flatMap {
    case "record" =>
      for
        key <- requiredString(json, "key", path)
        _ <- validateRecordKeyStrategy(key, s"$path.key")
        recordJson <- required(json, "record", path)
        record <- parseSchema(recordJson, s"$path.record", limits, depth + 1)
        objectSchema <- record match
          case value: Schema.ObjectValue => Right(value)
          case _ => Left(LexiconError(s"$path.record", "record schema must have type object"))
      yield Definition.Record(key, objectSchema)
    case "token" => Right(Definition.Token)
    case kind @ ("query" | "procedure" | "subscription" | "permission-set") =>
      Right(Definition.Primary(kind))
    case forbidden @ ("ref" | "union" | "unknown" | "params" | "permission") =>
      Left(LexiconError(path, s"$forbidden cannot be a named definition"))
    case _ => parseSchema(json, path, limits, depth).map(Definition.Data.apply)
  }

  private def parseSchema(
      json: Json,
      path: String,
      limits: Limits,
      depth: Int
  ): Either[LexiconError, Schema] =
    if depth > limits.maxSchemaDepth then
      Left(LexiconError(path, s"schema nesting exceeds ${limits.maxSchemaDepth}"))
    else
      requiredString(json, "type", path).flatMap {
        case "boolean" => mutuallyExclusive(json, path)
            .flatMap(_ => optionalBoolean(json, "const", path).map(Schema.BooleanValue.apply))
        case "integer" =>
          for
            _ <- mutuallyExclusive(json, path)
            minimum <- optionalLong(json, "minimum", path)
            maximum <- optionalLong(json, "maximum", path)
            _ <- ordered(minimum, maximum, path)
            allowed <- optionalLongSet(json, "enum", path)
            constant <- optionalLong(json, "const", path)
          yield Schema.IntegerValue(minimum, maximum, allowed, constant)
        case "string" =>
          for
            _ <- mutuallyExclusive(json, path)
            format <- optionalString(json, "format", path)
            _ <- format.fold[Either[LexiconError, Unit]](Right(())) { value =>
              check(
                StringFormats.contains(value),
                s"$path.format",
                s"unsupported Lexicon string format: $value"
              )
            }
            minBytes <- optionalCount(json, "minLength", path)
            maxBytes <- optionalCount(json, "maxLength", path)
            _ <- ordered(minBytes, maxBytes, path)
            minGraphemes <- optionalCount(json, "minGraphemes", path)
            maxGraphemes <- optionalCount(json, "maxGraphemes", path)
            _ <- ordered(minGraphemes, maxGraphemes, path)
            allowed <- optionalStringSet(json, "enum", path)
            constant <- optionalString(json, "const", path)
          yield Schema
            .StringValue(format, minBytes, maxBytes, minGraphemes, maxGraphemes, allowed, constant)
        case "bytes" =>
          for
            minimum <- optionalCount(json, "minLength", path)
            maximum <- optionalCount(json, "maxLength", path)
            _ <- ordered(minimum, maximum, path)
          yield Schema.BytesValue(minimum, maximum)
        case "cid-link" => Right(Schema.CidLink)
        case "blob"     =>
          for
            accept <- optionalStringVector(json, "accept", path).map(_.getOrElse(Vector.empty))
            _ <- accept.foldLeft[Either[LexiconError, Unit]](Right(())) { (result, mime) =>
              result.flatMap(_ => checkMime(mime, s"$path.accept"))
            }
            maxSize <- optionalLong(json, "maxSize", path)
            _ <- maxSize.fold[Either[LexiconError, Unit]](Right(()))(size =>
              check(size >= 0, s"$path.maxSize", "must be non-negative")
            )
          yield Schema.Blob(accept, maxSize)
        case "array" =>
          for
            itemsJson <- required(json, "items", path)
            items <- parseSchema(itemsJson, s"$path.items", limits, depth + 1)
            minimum <- optionalCount(json, "minLength", path)
            maximum <- optionalCount(json, "maxLength", path)
            _ <- ordered(minimum, maximum, path)
          yield Schema.ArrayValue(items, minimum, maximum)
        case "object" =>
          for
            rawProperties <- requiredObject(json, "properties", path)
            _ <- check(
              rawProperties.length <= limits.maxProperties,
              s"$path.properties",
              s"exceeds ${limits.maxProperties} properties"
            )
            properties <- traverseFields(rawProperties) { (name, value) =>
              parseSchema(value, s"$path.properties.$name", limits, depth + 1).map(name -> _)
            }
            required <- optionalStringVector(json, "required", path).map(_.getOrElse(Vector.empty))
            nullable <- optionalStringVector(json, "nullable", path).map(_.getOrElse(Vector.empty))
            names = properties.map(_._1).toSet
            _ <- check(
              required.distinct.length == required.length,
              s"$path.required",
              "must not contain duplicates"
            )
            _ <- check(
              nullable.distinct.length == nullable.length,
              s"$path.nullable",
              "must not contain duplicates"
            )
            _ <- check(required.forall(names), s"$path.required", "must name declared properties")
            _ <- check(nullable.forall(names), s"$path.nullable", "must name declared properties")
          yield Schema.ObjectValue(properties, required.toSet, nullable.toSet)
        case "ref" => requiredString(json, "ref", path).flatMap(value =>
            LexiconRef.parse(value).left.map(message => LexiconError(s"$path.ref", message))
          ).map(Schema.Reference.apply)
        case "union" =>
          for
            values <- requiredStringVector(json, "refs", path)
            refs <- traverse(values.zipWithIndex) { (value, index) =>
              LexiconRef.parse(value).left
                .map(message => LexiconError(s"$path.refs[$index]", message))
            }
            closed <- optionalBoolean(json, "closed", path).map(_.getOrElse(false))
            _ <-
              check(!closed || refs.nonEmpty, path, "a closed union must contain at least one ref")
          yield Schema.Union(refs, closed)
        case "unknown" => Right(Schema.Unknown)
        case other     => Left(LexiconError(s"$path.type", s"unsupported schema type: $other"))
      }

  private def mutuallyExclusive(json: Json, path: String): Either[LexiconError, Unit] =
    for
      constant <- optional(json, "const", path)
      default <- optional(json, "default", path)
      _ <-
        check(constant.isEmpty || default.isEmpty, path, "const and default are mutually exclusive")
    yield ()

  private def checkMime(value: String, path: String): Either[LexiconError, Unit] =
    val parts = value.split("/", -1)
    val valid = value == "*/*" ||
      (parts.length == 2 && parts(0).nonEmpty && parts(1).nonEmpty && !parts(0).contains('*') &&
        (parts(1) == "*" || !parts(1).contains('*')))
    check(valid, path, s"invalid MIME pattern: $value")

  private def validateRecordKeyStrategy(value: String, path: String): Either[LexiconError, Unit] =
    value match
      case "tid" | "nsid" | "any"                    => Right(())
      case literal if literal.startsWith("literal:") =>
        RecordKey.parse(literal.drop("literal:".length)).left
          .map(error => LexiconError(path, error.toString)).map(_ => ())
      case _ => Left(LexiconError(path, "expected tid, nsid, any, or literal:<record-key>"))

  private def ordered[A](minimum: Option[A], maximum: Option[A], path: String)(using
      ordering: Ordering[A]
  ): Either[LexiconError, Unit] = check(
    minimum.isEmpty || maximum.isEmpty || ordering.lteq(minimum.get, maximum.get),
    path,
    "minimum exceeds maximum"
  )

  private def optionalCount(
      json: Json,
      name: String,
      path: String
  ): Either[LexiconError, Option[Int]] = optionalLong(json, name, path).flatMap {
    case Some(value) if value < 0 || value > Int.MaxValue =>
      Left(LexiconError(s"$path.$name", "must be a non-negative 32-bit integer"))
    case value => Right(value.map(_.toInt))
  }

  private def required(json: Json, name: String, path: String): Either[LexiconError, Json] = json
    .field(name).left.map(error => LexiconError(s"$path.$name", error.message))

  private def optional(json: Json, name: String, path: String): Either[LexiconError, Option[Json]] =
    json.optionalField(name).left.map(error => LexiconError(path, error.message))

  private def requiredString(json: Json, name: String, path: String): Either[LexiconError, String] =
    required(json, name, path)
      .flatMap(_.asString.left.map(error => LexiconError(s"$path.$name", error.message)))

  private def optionalString(
      json: Json,
      name: String,
      path: String
  ): Either[LexiconError, Option[String]] = optional(json, name, path).flatMap {
    case None        => Right(None)
    case Some(value) => value.asString.left
        .map(error => LexiconError(s"$path.$name", error.message)).map(Some.apply)
  }

  private def requiredLong(json: Json, name: String, path: String): Either[LexiconError, Long] =
    required(json, name, path)
      .flatMap(_.asLong.left.map(error => LexiconError(s"$path.$name", error.message)))

  private def optionalLong(
      json: Json,
      name: String,
      path: String
  ): Either[LexiconError, Option[Long]] = optional(json, name, path).flatMap {
    case None        => Right(None)
    case Some(value) => value.asLong.left.map(error => LexiconError(s"$path.$name", error.message))
        .map(Some.apply)
  }

  private def optionalBoolean(
      json: Json,
      name: String,
      path: String
  ): Either[LexiconError, Option[Boolean]] = optional(json, name, path).flatMap {
    case None        => Right(None)
    case Some(value) => value.asBoolean.left
        .map(error => LexiconError(s"$path.$name", error.message)).map(Some.apply)
  }

  private def requiredObject(
      json: Json,
      name: String,
      path: String
  ): Either[LexiconError, Vector[(String, Json)]] = required(json, name, path)
    .flatMap(_.asObject.left.map(error => LexiconError(s"$path.$name", error.message)))

  private def requiredStringVector(
      json: Json,
      name: String,
      path: String
  ): Either[LexiconError, Vector[String]] = required(json, name, path)
    .flatMap(value => stringVector(value, s"$path.$name"))

  private def optionalStringVector(
      json: Json,
      name: String,
      path: String
  ): Either[LexiconError, Option[Vector[String]]] = optional(json, name, path).flatMap {
    case None        => Right(None)
    case Some(value) => stringVector(value, s"$path.$name").map(Some.apply)
  }

  private def optionalStringSet(
      json: Json,
      name: String,
      path: String
  ): Either[LexiconError, Option[Set[String]]] = optionalStringVector(json, name, path).flatMap {
    case Some(values) if values.distinct.length != values.length =>
      Left(LexiconError(s"$path.$name", "must not contain duplicates"))
    case values => Right(values.map(_.toSet))
  }

  private def optionalLongSet(
      json: Json,
      name: String,
      path: String
  ): Either[LexiconError, Option[Set[Long]]] = optional(json, name, path).flatMap {
    case None        => Right(None)
    case Some(value) => value.asArray.left.map(error => LexiconError(s"$path.$name", error.message))
        .flatMap { values =>
          traverse(values.zipWithIndex) { (item, index) =>
            item.asLong.left.map(error => LexiconError(s"$path.$name[$index]", error.message))
          }.flatMap { decoded =>
            check(
              decoded.distinct.length == decoded.length,
              s"$path.$name",
              "must not contain duplicates"
            ).map(_ => Some(decoded.toSet))
          }
        }
  }

  private def stringVector(json: Json, path: String): Either[LexiconError, Vector[String]] = json
    .asArray.left.map(error => LexiconError(path, error.message)).flatMap { values =>
      traverse(values.zipWithIndex) { (item, index) =>
        item.asString.left.map(error => LexiconError(s"$path[$index]", error.message))
      }
    }

  private def traverse[A, B](
      values: Vector[A]
  )(decode: A => Either[LexiconError, B]): Either[LexiconError, Vector[B]] = values
    .foldLeft[Either[LexiconError, Vector[B]]](Right(Vector.empty)) { (result, value) =>
      result.flatMap(decoded => decode(value).map(decoded :+ _))
    }

  private def traverseFields[A](values: Vector[(String, Json)])(
      decode: (String, Json) => Either[LexiconError, A]
  ): Either[LexiconError, Vector[A]] = traverse(values) { case (name, value) =>
    decode(name, value)
  }

  private def check(
      condition: Boolean,
      path: String,
      message: => String
  ): Either[LexiconError, Unit] = Either.cond(condition, (), LexiconError(path, message))

  private val StringFormats = Set(
    "at-identifier",
    "at-uri",
    "cid",
    "datetime",
    "did",
    "handle",
    "nsid",
    "tid",
    "record-key",
    "uri",
    "language"
  )

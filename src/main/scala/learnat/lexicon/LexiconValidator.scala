package learnat.lexicon

import java.net.URI
import java.nio.charset.StandardCharsets
import java.text.BreakIterator
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale
import learnat.ipld.Ipld
import learnat.syntax.AtIdentifier
import learnat.syntax.AtUri
import learnat.syntax.Did
import learnat.syntax.Handle
import learnat.syntax.Nsid
import learnat.syntax.RecordKey
import learnat.syntax.Tid

/** One value-validation failure with a JSONPath-like location. */
final case class ValidationError(path: String, message: String):
  override def toString: String = s"$path: $message"

/** Non-fatal forward-compatibility information produced during validation. */
final case class ValidationWarning(path: String, message: String)

/** Successful validation, including fields or open variants intentionally ignored. */
final case class ValidationReport(warnings: Vector[ValidationWarning]):
  /** Combines independent validation reports without losing warnings. */
  def ++(other: ValidationReport): ValidationReport = ValidationReport(warnings ++ other.warnings)

object ValidationReport:
  val empty: ValidationReport = ValidationReport(Vector.empty)

/** Immutable in-memory registry used to resolve local and cross-document refs. */
final class LexiconRegistry private (private val documents: Map[Nsid, LexiconDocument]):
  /** Returns a loaded Lexicon document by NSID. */
  def document(id: Nsid): Option[LexiconDocument] = documents.get(id)

  private[lexicon] def resolve(current: Nsid, reference: LexiconRef): Option[(Nsid, Definition)] =
    val documentId = reference.document.getOrElse(current)
    documents.get(documentId).flatMap(_.definition(reference.definition)).map(documentId -> _)

object LexiconRegistry:
  /** Builds a registry while rejecting duplicate document IDs. */
  def from(documents: Vector[LexiconDocument]): Either[LexiconError, LexiconRegistry] =
    val duplicates = documents.groupMapReduce(_.id)(_ => 1)(_ + _).collect { case (id, count) if count > 1 => id.value }
    if duplicates.nonEmpty then Left(LexiconError("$", s"duplicate Lexicon document IDs: ${duplicates.mkString(", ")}"))
    else Right(new LexiconRegistry(documents.map(document => document.id -> document).toMap))

/** Validates IPLD values against parsed Lexicon data schemas. */
final class LexiconValidator(registry: LexiconRegistry, maxDepth: Int = 128):
  require(maxDepth > 0, "maxDepth must be positive")

  /** Validates a repository record against the document's `main` definition. */
  def validateRecord(documentId: Nsid, value: Ipld): Either[ValidationError, ValidationReport] =
    registry.document(documentId) match
      case None => Left(ValidationError("$", s"Lexicon is not loaded: ${documentId.value}"))
      case Some(document) => document.definition("main") match
        case Some(Definition.Record(_, schema)) =>
          for
            fields <- asMap(value, "$")
            typeName <- fields.get("$type") match
              case Some(Ipld.Text(name)) => Right(name)
              case Some(_) => Left(ValidationError("$.$type", "must be a string"))
              case None => Left(ValidationError("$.$type", "record requires a $type discriminator"))
            _ <- expect(typeName == documentId.value, "$.$type", s"expected ${documentId.value}")
            report <- validateSchema(schema, value, documentId, "$", depth = 1, resolving = Set.empty)
          yield report
        case Some(_) => Left(ValidationError("$", s"${documentId.value}#main is not a record definition"))
        case None => Left(ValidationError("$", s"${documentId.value} has no main definition"))

  /** Validates a value against an explicitly selected schema. */
  def validate(schema: Schema, value: Ipld, documentId: Nsid): Either[ValidationError, ValidationReport] =
    validateSchema(schema, value, documentId, "$", depth = 1, resolving = Set.empty)

  private def validateSchema(
      schema: Schema,
      value: Ipld,
      documentId: Nsid,
      path: String,
      depth: Int,
      resolving: Set[(Nsid, String)]
  ): Either[ValidationError, ValidationReport] =
    if depth > maxDepth then Left(ValidationError(path, s"value nesting exceeds $maxDepth"))
    else schema match
      case Schema.BooleanValue(constant) => value match
        case Ipld.Bool(actual) => constraint(constant.forall(_ == actual), path, "boolean does not match const")
        case _ => wrongType(path, "boolean", value)
      case Schema.IntegerValue(minimum, maximum, allowed, constant) => value match
        case Ipld.Integer(actual) =>
          constraints(
            Vector(
              minimum.forall(actual >= _) -> s"must be at least ${minimum.getOrElse(0L)}",
              maximum.forall(actual <= _) -> s"must be at most ${maximum.getOrElse(0L)}",
              allowed.forall(_.contains(actual)) -> "is not in the closed enum",
              constant.forall(_ == actual) -> "does not match const"
            ),
            path
          )
        case _ => wrongType(path, "integer", value)
      case Schema.StringValue(format, minBytes, maxBytes, minGraphemes, maxGraphemes, allowed, constant) => value match
        case Ipld.Text(actual) =>
          val byteLength = actual.getBytes(StandardCharsets.UTF_8).length
          val graphemeLength = graphemes(actual)
          for
            report <- constraints(
              Vector(
                minBytes.forall(byteLength >= _) -> s"must contain at least ${minBytes.getOrElse(0)} UTF-8 bytes",
                maxBytes.forall(byteLength <= _) -> s"must contain at most ${maxBytes.getOrElse(0)} UTF-8 bytes",
                minGraphemes.forall(graphemeLength >= _) -> s"must contain at least ${minGraphemes.getOrElse(0)} graphemes",
                maxGraphemes.forall(graphemeLength <= _) -> s"must contain at most ${maxGraphemes.getOrElse(0)} graphemes",
                allowed.forall(_.contains(actual)) -> "is not in the closed enum",
                constant.forall(_ == actual) -> "does not match const"
              ),
              path
            )
            _ <- format.fold[Either[ValidationError, ValidationReport]](Right(ValidationReport.empty))(name => validateFormat(name, actual, path))
          yield report
        case _ => wrongType(path, "string", value)
      case Schema.BytesValue(minimum, maximum) => value match
        case Ipld.Bytes(bytes) => constraints(
          Vector(
            minimum.forall(bytes.size >= _) -> s"must contain at least ${minimum.getOrElse(0)} bytes",
            maximum.forall(bytes.size <= _) -> s"must contain at most ${maximum.getOrElse(0)} bytes"
          ),
          path
        )
        case _ => wrongType(path, "bytes", value)
      case Schema.CidLink => value match
        case Ipld.Link(_) => Right(ValidationReport.empty)
        case _ => wrongType(path, "CID link", value)
      case Schema.Blob(accept, maxSize) => validateBlob(value, accept, maxSize, path)
      case Schema.ArrayValue(items, minimum, maximum) => value match
        case Ipld.List(values) =>
          for
            lengthReport <- constraints(
              Vector(
                minimum.forall(values.length >= _) -> s"must contain at least ${minimum.getOrElse(0)} items",
                maximum.forall(values.length <= _) -> s"must contain at most ${maximum.getOrElse(0)} items"
              ),
              path
            )
            itemReport <- validateMany(values.zipWithIndex) { (item, index) =>
              validateSchema(items, item, documentId, s"$path[$index]", depth + 1, resolving)
            }
          yield lengthReport ++ itemReport
        case _ => wrongType(path, "array", value)
      case Schema.ObjectValue(properties, required, nullable) =>
        asMap(value, path).flatMap { fields =>
          val missing = required.diff(fields.keySet)
          if missing.nonEmpty then Left(ValidationError(path, s"missing required properties: ${missing.toVector.sorted.mkString(", ")}"))
          else
            val known = properties.map(_._1).toSet
            val warnings = fields.keysIterator.filterNot(known).filterNot(_ == "$type").toVector.sorted
              .map(name => ValidationWarning(s"$path.$name", "unexpected field ignored for forward compatibility"))
            validateMany(properties) { (name, propertySchema) =>
              fields.get(name) match
                case None => Right(ValidationReport.empty)
                case Some(Ipld.Null) if nullable.contains(name) => Right(ValidationReport.empty)
                case Some(Ipld.Null) => Left(ValidationError(s"$path.$name", "null is not allowed"))
                case Some(propertyValue) =>
                  validateSchema(propertySchema, propertyValue, documentId, s"$path.$name", depth + 1, resolving)
            }.map(report => ValidationReport(warnings) ++ report)
        }
      case Schema.Reference(target) => validateReference(target, value, documentId, path, depth, resolving, requireDiscriminator = false)
      case Schema.Union(variants, closed) => validateUnion(variants, closed, value, documentId, path, depth, resolving)
      case Schema.Unknown => value match
        case Ipld.Map(fields) if !fields.exists { case (name, item) => name == "$type" && item == Ipld.Text("blob") } =>
          Right(ValidationReport.empty)
        case Ipld.Map(_) => Left(ValidationError(path, "unknown may not disguise a blob compound value"))
        case _ => wrongType(path, "object", value)

  private def validateReference(
      target: LexiconRef,
      value: Ipld,
      documentId: Nsid,
      path: String,
      depth: Int,
      resolving: Set[(Nsid, String)],
      requireDiscriminator: Boolean
  ): Either[ValidationError, ValidationReport] =
    val targetId = target.document.getOrElse(documentId)
    val key = targetId -> target.definition
    if resolving.contains(key) then Left(ValidationError(path, s"cyclic Lexicon ref: $target"))
    else registry.resolve(documentId, target) match
      case None => Left(ValidationError(path, s"unresolved Lexicon ref: $target"))
      case Some((resolvedId, Definition.Data(schema))) =>
        if requireDiscriminator && !schema.isInstanceOf[Schema.ObjectValue] then
          Left(ValidationError(path, s"union ref must target an object or record: $target"))
        else validateSchema(schema, value, resolvedId, path, depth + 1, resolving + key)
      case Some((resolvedId, Definition.Record(_, schema))) =>
        for
          fields <- asMap(value, path)
          _ <- fields.get("$type") match
            case Some(Ipld.Text(actual)) => expect(actual == canonicalType(resolvedId, target.definition), s"$path.$$type", s"expected ${canonicalType(resolvedId, target.definition)}")
            case _ => Left(ValidationError(s"$path.$$type", "record reference requires a string discriminator"))
          report <- validateSchema(schema, value, resolvedId, path, depth + 1, resolving + key)
        yield report
      case Some((_, Definition.Token)) => Left(ValidationError(path, s"ref may not target token: $target"))
      case Some((_, Definition.Primary(kind))) => Left(ValidationError(path, s"ref may not target $kind: $target"))

  private def validateUnion(
      variants: Vector[LexiconRef],
      closed: Boolean,
      value: Ipld,
      documentId: Nsid,
      path: String,
      depth: Int,
      resolving: Set[(Nsid, String)]
  ): Either[ValidationError, ValidationReport] =
    for
      fields <- asMap(value, path)
      typeName <- fields.get("$type") match
        case Some(Ipld.Text(actual)) => Right(actual)
        case Some(_) => Left(ValidationError(s"$path.$$type", "union discriminator must be a string"))
        case None => Left(ValidationError(s"$path.$$type", "union value requires a $type discriminator"))
      selected = variants.find { reference =>
        val id = reference.document.getOrElse(documentId)
        canonicalType(id, reference.definition) == typeName
      }
      report <- selected match
        case Some(reference) => validateReference(reference, value, documentId, path, depth, resolving, requireDiscriminator = true)
        case None if closed => Left(ValidationError(s"$path.$$type", s"unknown closed-union variant: $typeName"))
        case None => Right(ValidationReport(Vector(ValidationWarning(s"$path.$$type", s"unknown open-union variant preserved: $typeName"))))
    yield report

  private def validateBlob(value: Ipld, accept: Vector[String], maxSize: Option[Long], path: String): Either[ValidationError, ValidationReport] =
    for
      fields <- asMap(value, path)
      _ <- fields.get("$type") match
        case Some(Ipld.Text("blob")) => Right(())
        case _ => Left(ValidationError(s"$path.$$type", "blob requires the blob discriminator"))
      _ <- fields.get("ref") match
        case Some(Ipld.Link(_)) => Right(())
        case _ => Left(ValidationError(s"$path.ref", "blob ref must be a CID link"))
      mimeType <- fields.get("mimeType") match
        case Some(Ipld.Text(actual)) => Right(actual)
        case _ => Left(ValidationError(s"$path.mimeType", "blob mimeType must be a string"))
      size <- fields.get("size") match
        case Some(Ipld.Integer(actual)) if actual >= 0 => Right(actual)
        case _ => Left(ValidationError(s"$path.size", "blob size must be a non-negative integer"))
      _ <- expect(accept.isEmpty || accept.exists(mimeMatches(_, mimeType)), s"$path.mimeType", s"MIME type is not accepted: $mimeType")
      _ <- expect(maxSize.forall(size <= _), s"$path.size", s"blob exceeds maximum size ${maxSize.getOrElse(0L)}")
    yield ValidationReport.empty

  private def validateFormat(format: String, value: String, path: String): Either[ValidationError, ValidationReport] =
    val valid = format match
      case "at-identifier" => AtIdentifier.parse(value).isRight
      case "at-uri" => AtUri.parse(value).isRight
      case "cid" => learnat.ipld.Cid.parse(value).isRight
      case "datetime" => validDatetime(value)
      case "did" => Did.parse(value).isRight
      case "handle" => Handle.parse(value).isRight
      case "nsid" => Nsid.parse(value).isRight
      case "tid" => Tid.parse(value).isRight
      case "record-key" => RecordKey.parse(value).isRight
      case "uri" => validUri(value)
      case "language" => validLanguage(value)
      case _ => false
    expect(valid, path, s"does not satisfy Lexicon string format $format").map(_ => ValidationReport.empty)

  private def validDatetime(value: String): Boolean =
    val pattern = "^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d+))?(Z|[+-]\\d{2}:\\d{2})$".r
    value match
      case pattern(yearText, monthText, dayText, hourText, minuteText, secondText, fraction, offsetText) =>
        if offsetText == "-00:00" then false
        else
          try
            val year = yearText.toInt
            val month = monthText.toInt
            val day = dayText.toInt
            val hour = hourText.toInt
            val minute = minuteText.toInt
            val second = secondText.toInt
            val nanos = Option(fraction).fold(0)(digits => (digits.take(9) + "000000000").take(9).toInt)
            val offset = if offsetText == "Z" then ZoneOffset.UTC else ZoneOffset.of(offsetText)
            val instant = LocalDateTime.of(year, month, day, hour, minute, second, nanos).toInstant(offset)
            !instant.isBefore(Instant.parse("0000-01-01T00:00:00Z"))
          catch case _: DateTimeException | _: NumberFormatException => false
      case _ => false

  private def validUri(value: String): Boolean =
    if value.getBytes(StandardCharsets.UTF_8).length > 8192 then false
    else
      try
        val uri = URI(value)
        uri.isAbsolute
      catch case _: IllegalArgumentException => false

  private def validLanguage(value: String): Boolean =
    if value.isEmpty then false
    else
      try
        new Locale.Builder().setLanguageTag(value).build()
        true
      catch case _: java.util.IllformedLocaleException => false

  private def graphemes(value: String): Int =
    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
    iterator.setText(value)
    var count = 0
    var boundary = iterator.first()
    var next = iterator.next()
    while next != BreakIterator.DONE do
      count += 1
      boundary = next
      next = iterator.next()
    val _ = boundary
    count

  private def canonicalType(documentId: Nsid, definition: String): String =
    if definition == "main" then documentId.value else s"${documentId.value}#$definition"

  private def mimeMatches(pattern: String, actual: String): Boolean =
    pattern == "*/*" || pattern == actual || (pattern.endsWith("/*") && actual.startsWith(pattern.dropRight(1)))

  private def asMap(value: Ipld, path: String): Either[ValidationError, Map[String, Ipld]] = value match
    case Ipld.Map(fields) => Right(fields.toMap)
    case _ => wrongType(path, "object", value)

  private def validateMany[A](values: Vector[A])(validate: A => Either[ValidationError, ValidationReport]): Either[ValidationError, ValidationReport] =
    values.foldLeft[Either[ValidationError, ValidationReport]](Right(ValidationReport.empty)) { (result, value) =>
      for
        accumulated <- result
        report <- validate(value)
      yield accumulated ++ report
    }

  private def constraints(checks: Vector[(Boolean, String)], path: String): Either[ValidationError, ValidationReport] =
    checks.collectFirst { case (false, message) => ValidationError(path, message) }.toLeft(ValidationReport.empty)

  private def constraint(valid: Boolean, path: String, message: String): Either[ValidationError, ValidationReport] =
    expect(valid, path, message).map(_ => ValidationReport.empty)

  private def expect(valid: Boolean, path: String, message: => String): Either[ValidationError, Unit] =
    Either.cond(valid, (), ValidationError(path, message))

  private def wrongType(path: String, expected: String, actual: Ipld): Left[ValidationError, Nothing] =
    Left(ValidationError(path, s"expected $expected, found ${kind(actual)}"))

  private def kind(value: Ipld): String = value match
    case Ipld.Null => "null"
    case Ipld.Bool(_) => "boolean"
    case Ipld.Integer(_) => "integer"
    case Ipld.Text(_) => "string"
    case Ipld.Bytes(_) => "bytes"
    case Ipld.List(_) => "array"
    case Ipld.Map(_) => "object"
    case Ipld.Link(_) => "CID link"

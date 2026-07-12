package learnat.tests

import learnat.ipld.ByteString
import learnat.ipld.Cid
import learnat.ipld.Ipld
import learnat.json.Json
import learnat.lexicon.Definition
import learnat.lexicon.LexiconDocument
import learnat.lexicon.LexiconRef
import learnat.lexicon.LexiconRegistry
import learnat.lexicon.LexiconValidator
import learnat.lexicon.Schema
import learnat.syntax.Nsid
import learnat.tests.TestKit.*

object LexiconTests:
  private val document = parseDocument(
    """
      {
        "lexicon": 1,
        "id": "com.example.note",
        "defs": {
          "main": {
            "type": "record",
            "key": "tid",
            "record": {
              "type": "object",
              "required": ["text", "createdAt"],
              "nullable": ["reply"],
              "properties": {
                "text": {"type": "string", "maxLength": 8, "maxGraphemes": 4},
                "createdAt": {"type": "string", "format": "datetime"},
                "reply": {"type": "ref", "ref": "#replyRef"},
                "tags": {
                  "type": "array",
                  "maxLength": 2,
                  "items": {"type": "string", "format": "language"}
                },
                "subject": {"type": "union", "refs": ["#known"]}
              }
            }
          },
          "replyRef": {
            "type": "object",
            "required": ["uri"],
            "properties": {"uri": {"type": "string", "format": "at-uri"}}
          },
          "known": {
            "type": "object",
            "required": ["value"],
            "properties": {"value": {"type": "integer", "minimum": 1, "maximum": 5}}
          }
        }
      }
    """
  )
  private val id = Nsid.parse("com.example.note").toOption.get
  private val registry = LexiconRegistry.from(Vector(document)).toOption.get
  private val validator = LexiconValidator(registry)

  def run(): Unit =
    println("Lexicon")

    test("parses a record and reusable named definitions") {
      equal(document.id, id)
      assert(document.definition("main").exists(_.isInstanceOf[Definition.Record]))
      assert(document.definition("replyRef").exists(_.isInstanceOf[Definition.Data]))
    }

    test("rejects invalid document and schema invariants") {
      isLeft(parse("""{"lexicon":2,"id":"com.example.bad","defs":{"main":{"type":"token"}}}"""))
      isLeft(parse("""{"lexicon":1,"id":"com.example.bad","defs":{}}"""))
      isLeft(parse("""{"lexicon":1,"id":"com.example.bad","defs":{"main":{"type":"string","const":"x","default":"y"}}}"""))
      isLeft(parse("""{"lexicon":1,"id":"com.example.bad","defs":{"main":{"type":"object","required":["missing"],"properties":{}}}}"""))
      isLeft(parse("""{"lexicon":1,"id":"com.example.bad","defs":{"main":{"type":"union","refs":[]}}}"""))
      isLeft(parse("""{"lexicon":1,"id":"com.example.bad","defs":{"main":{"type":"record","key":"uuid","record":{"type":"object","properties":{}}}}}"""))
      isLeft(parse("""{"lexicon":1,"id":"com.example.bad","defs":{"main":{"type":"string","format":"email"}}}"""))
    }

    test("parses local and global references canonically") {
      equal(LexiconRef.parse("#view").map(_.toString), Right("#view"))
      equal(LexiconRef.parse("com.example.note").map(_.toString), Right("com.example.note"))
      equal(LexiconRef.parse("com.example.note#view").map(_.toString), Right("com.example.note#view"))
      isLeft(LexiconRef.parse("#"))
      isLeft(LexiconRef.parse("com.example.note#bad-name"))
    }

    test("validates a record while preserving unexpected fields as warnings") {
      val value = validRecord(
        "éééé",
        extra = Vector("future" -> Ipld.Text("preserve me"), "reply" -> Ipld.Null)
      )
      val report = validator.validateRecord(id, value).toOption.get
      equal(report.warnings.map(_.path), Vector("$.future"))
    }

    test("counts string maxLength in UTF-8 bytes") {
      assert(validator.validateRecord(id, validRecord("éééé")).isRight)
      isLeft(validator.validateRecord(id, validRecord("ééééé")))
    }

    test("distinguishes missing, null, and false-y values") {
      val missing = Ipld.obj("$type" -> Ipld.Text(id.value), "createdAt" -> Ipld.Text("2026-07-10T00:00:00Z"))
      isLeft(validator.validateRecord(id, missing))
      isLeft(validator.validateRecord(id, validRecord("ok", Vector("text" -> Ipld.Null))))
      assert(validator.validateRecord(id, validRecord("")).isRight)
    }

    test("resolves local refs and validates nested values") {
      val reply = Ipld.obj("uri" -> Ipld.Text("at://did:plc:example/com.example.note/key"))
      assert(validator.validateRecord(id, validRecord("ok", Vector("reply" -> reply))).isRight)
      val badReply = Ipld.obj("uri" -> Ipld.Text("https://not-an-at-uri.example"))
      isLeft(validator.validateRecord(id, validRecord("ok", Vector("reply" -> badReply))))
    }

    test("validates known unions and preserves unknown open variants") {
      val known = Ipld.obj("$type" -> Ipld.Text("com.example.note#known"), "value" -> Ipld.Integer(3))
      assert(validator.validateRecord(id, validRecord("ok", Vector("subject" -> known))).isRight)

      val future = Ipld.obj("$type" -> Ipld.Text("com.example.note#future"), "value" -> Ipld.Integer(3))
      val report = validator.validateRecord(id, validRecord("ok", Vector("subject" -> future))).toOption.get
      assert(report.warnings.exists(_.message.contains("open-union")))

      val closed = Schema.Union(Vector(LexiconRef.parse("#known").toOption.get), closed = true)
      isLeft(validator.validate(closed, future, id))
    }

    test("validates array counts and language tags") {
      val tags = Ipld.List(Vector(Ipld.Text("ja"), Ipld.Text("pt-BR")))
      assert(validator.validateRecord(id, validRecord("ok", Vector("tags" -> tags))).isRight)
      val tooMany = Ipld.List(Vector(Ipld.Text("ja"), Ipld.Text("en"), Ipld.Text("fr")))
      isLeft(validator.validateRecord(id, validRecord("ok", Vector("tags" -> tooMany))))
      isLeft(validator.validateRecord(id, validRecord("ok", Vector("tags" -> Ipld.List(Vector(Ipld.Text("not_a_tag")))))))
    }

    test("validates exact datetime syntax and semantics") {
      val schema = stringFormat("datetime")
      assert(validator.validate(schema, Ipld.Text("1985-04-12T23:20:50.12345678912345Z"), id).isRight)
      assert(validator.validate(schema, Ipld.Text("0000-01-01T00:00:00Z"), id).isRight)
      isLeft(validator.validate(schema, Ipld.Text("1985-00-12T23:20:50Z"), id))
      isLeft(validator.validate(schema, Ipld.Text("1985-04-12t23:20:50z"), id))
      isLeft(validator.validate(schema, Ipld.Text("1985-04-12T23:20:50-00:00"), id))
      isLeft(validator.validate(schema, Ipld.Text("0000-01-01T00:00:00+01:00"), id))
    }

    test("validates byte, CID, and blob data-model values") {
      val bytesSchema = Schema.BytesValue(Some(2), Some(3))
      assert(validator.validate(bytesSchema, Ipld.Bytes(ByteString(Array[Byte](1, 2))), id).isRight)
      isLeft(validator.validate(bytesSchema, Ipld.Bytes(ByteString(Array[Byte](1))), id))

      val cid = Cid.forRaw(Array[Byte](1, 2, 3))
      assert(validator.validate(Schema.CidLink, Ipld.Link(cid), id).isRight)
      val blob = Ipld.obj(
        "$type" -> Ipld.Text("blob"),
        "ref" -> Ipld.Link(cid),
        "mimeType" -> Ipld.Text("image/png"),
        "size" -> Ipld.Integer(3)
      )
      assert(validator.validate(Schema.Blob(Vector("image/*"), Some(4)), blob, id).isRight)
      assert(validator.validate(Schema.Blob(Vector("*/*"), Some(4)), blob, id).isRight)
      isLeft(validator.validate(Schema.Blob(Vector("text/*"), Some(4)), blob, id))
      isLeft(validator.validate(Schema.Blob(Vector("image/*"), Some(2)), blob, id))
    }

    test("fails closed on unresolved and cyclic references") {
      val unresolved = Schema.Reference(LexiconRef.parse("com.example.missing").toOption.get)
      isLeft(validator.validate(unresolved, Ipld.obj(), id))

      val cycleDocument = LexiconDocument(
        Nsid.parse("com.example.cycle").toOption.get,
        Map(
          "loop" -> Definition.Data(
            Schema.ObjectValue(
              Vector("next" -> Schema.Reference(LexiconRef.parse("#loop").toOption.get)),
              Set("next"),
              Set.empty
            )
          )
        )
      )
      val cycleRegistry = LexiconRegistry.from(Vector(cycleDocument)).toOption.get
      val cycleValidator = LexiconValidator(cycleRegistry)
      val cycle = Schema.Reference(LexiconRef.parse("#loop").toOption.get)
      isLeft(cycleValidator.validate(cycle, Ipld.obj("next" -> Ipld.obj("next" -> Ipld.obj())), cycleDocument.id))
    }

    test("rejects duplicate registry documents") {
      isLeft(LexiconRegistry.from(Vector(document, document)))
    }

  private def validRecord(text: String, extra: Vector[(String, Ipld)] = Vector.empty): Ipld =
    Ipld.obj(
      (Vector(
        "$type" -> Ipld.Text(id.value),
        "text" -> Ipld.Text(text),
        "createdAt" -> Ipld.Text("2026-07-10T00:00:00.000Z")
      ) ++ extra)*
    )

  private def stringFormat(name: String): Schema.StringValue =
    Schema.StringValue(Some(name), None, None, None, None, None, None)

  private def parseDocument(value: String): LexiconDocument = parse(value).toOption.get

  private def parse(value: String) =
    Json.parse(value).left.map(_.toString).flatMap(json => LexiconDocument.parse(json).left.map(_.toString))

package learnat.tests

import learnat.json.Json
import learnat.json.Json.*
import learnat.tests.TestKit.*

object JsonTests:
  def run(): Unit =
    println("JSON")

    test("parses every JSON value kind") {
      val input = """{"null":null,"bool":true,"number":-12.5e2,"string":"AT\nProtocol","array":[1,false]}"""
      val parsed = Json.parse(input)
      assert(parsed.isRight, parsed)
      equal(parsed.map(_.render), Right("""{"null":null,"bool":true,"number":-1250,"string":"AT\nProtocol","array":[1,false]}"""))
    }

    test("round trips unicode including surrogate pairs") {
      val parsed = Json.parse("\"Scala \\uD83D\\uDE80\"")
      equal(parsed, Right(Str("Scala 🚀")))
      equal(parsed.map(_.render), Right("\"Scala 🚀\""))
    }

    test("rejects duplicate object keys") {
      isLeft(Json.parse("""{"did":"first","did":"second"}"""))
    }

    test("rejects invalid number grammar") {
      isLeft(Json.parse("01"))
      isLeft(Json.parse("1."))
      isLeft(Json.parse("1e"))
    }

    test("rejects trailing input") {
      isLeft(Json.parse("true false"))
    }

    test("enforces the nesting limit") {
      isLeft(Json.parse("[[[]]]", Json.Limits(maxDepth = 2)))
    }

    test("reads fields without unsafe casts") {
      val document = obj("did" -> Str("did:web:example.com"), "active" -> Bool(true))
      equal(document.field("did").flatMap(_.asString), Right("did:web:example.com"))
      isLeft(document.field("missing"))
      isLeft(document.field("active").flatMap(_.asString))
    }

package learnat.tests

import java.util.Base64
import learnat.ipld.ByteString
import learnat.ipld.Cid
import learnat.ipld.DagCbor
import learnat.ipld.DagJson
import learnat.ipld.Ipld
import learnat.json.Json
import learnat.tests.TestKit.*

object DagJsonTests:
  def run(): Unit =
    println("DAG-JSON")

    test("round trips every supported IPLD value kind") {
      val linkedBytes = DagCbor.encode(Ipld.obj("target" -> Ipld.Bool(true))).toOption.get
      val value = Ipld.obj(
        "nil" -> Ipld.Null,
        "bool" -> Ipld.Bool(true),
        "int" -> Ipld.Integer(42),
        "text" -> Ipld.Text("hello"),
        "bytes" -> Ipld.Bytes(ByteString(Array[Byte](1, 2, 3))),
        "list" -> Ipld.List(Vector(Ipld.Integer(1), Ipld.Integer(2))),
        "link" -> Ipld.Link(Cid.forDagCbor(linkedBytes))
      )
      equal(DagJson.decode(DagJson.encode(value)), Right(value))
    }

    test("rejects floating point and out-of-range JSON numbers") {
      isLeft(DagJson.decode(Json.Num(BigDecimal("1.5"))))
      isLeft(DagJson.decode(Json.Num(BigDecimal(Long.MaxValue) + 1)))
    }

    test("recognizes only exact link and bytes extension objects") {
      val bytes = Array[Byte](1, 2, 3)
      val encoded = Base64.getEncoder.encodeToString(bytes)
      equal(DagJson.decode(Json.obj("$bytes" -> Json.Str(encoded))), Right(Ipld.Bytes(ByteString(bytes))))
      val ordinary = Json.obj("$bytes" -> Json.Str(encoded), "note" -> Json.Str("ordinary map"))
      assert(DagJson.decode(ordinary).exists(_.isInstanceOf[Ipld.Map]))
    }

    test("rejects malformed CID and base64 extension values") {
      isLeft(DagJson.decode(Json.obj("$link" -> Json.Str("not-a-cid"))))
      isLeft(DagJson.decode(Json.obj("$bytes" -> Json.Str("%%%"))))
    }

    test("preserves record field order on JSON conversion") {
      val json = Json.obj(
        "$type" -> Json.Str("com.example.note"),
        "text" -> Json.Str("hello"),
        "count" -> Json.Num(1)
      )
      equal(DagJson.encode(DagJson.decode(json).toOption.get), json)
    }


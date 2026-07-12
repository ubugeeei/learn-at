package learnat.tests

import learnat.ipld.*
import learnat.ipld.Ipld.*
import learnat.tests.TestKit.*

object IpldTests:
  def run(): Unit =
    println("DAG-CBOR / CID")

    test("encodes a canonical CBOR example") {
      val value = obj("b" -> list(Bool(true), Null), "a" -> Integer(1))
      val bytes = DagCbor.encode(value).toOption.get
      equal(hex(bytes), "a2616101616282f5f6")
      equal(DagCbor.decode(bytes), Right(value))
    }

    test("sorts map keys by UTF-8 length then byte order") {
      val value = obj("aa" -> Integer(2), "b" -> Integer(1), "a" -> Integer(0))
      val bytes = DagCbor.encode(value).toOption.get
      equal(hex(bytes), "a361610061620162616102")
      equal(DagCbor.decode(bytes), Right(obj("a" -> Integer(0), "b" -> Integer(1), "aa" -> Integer(2))))
    }

    test("round trips signed 64-bit integer boundaries") {
      val value = list(Integer(Long.MinValue), Integer(-1), Integer(0), Integer(Long.MaxValue))
      equal(DagCbor.decode(DagCbor.encode(value).toOption.get), Right(value))
    }

    test("rejects duplicate keys and non-canonical input") {
      isLeft(DagCbor.encode(obj("a" -> Integer(1), "a" -> Integer(2))))
      isLeft(DagCbor.decode(Array(0x18.toByte, 0x01.toByte)))
      isLeft(DagCbor.decode(Array(0xa2.toByte, 0x61.toByte, 'b'.toByte, 0x01.toByte, 0x61.toByte, 'a'.toByte, 0x02.toByte)))
    }

    test("creates and parses a CIDv1 with SHA-256 multihash") {
      val bytes = DagCbor.encode(obj()).toOption.get
      val cid = Cid.forDagCbor(bytes)
      equal(cid.toString, "bafyreigbtj4x7ip5legnfznufuopl4sg4knzc2cof6duas4b3q2fy6swua")
      equal(Cid.parse(cid.toString), Right(cid))
      assert(cid.verifies(bytes))
      assert(!cid.verifies(Array(0xa1.toByte)))
    }

    test("encodes CID links with DAG-CBOR tag 42") {
      val block = DagCbor.encode(obj("hello" -> Text("world"))).toOption.get
      val cid = Cid.forDagCbor(block)
      val linked = obj("ref" -> Link(cid))
      equal(DagCbor.decode(DagCbor.encode(linked).toOption.get), Right(linked))
    }

    test("defensively copies byte strings") {
      val source = Array[Byte](1, 2, 3)
      val value = ByteString(source)
      source(0) = 9
      equal(value, ByteString(Array[Byte](1, 2, 3)))
    }

    test("decodes concatenated canonical values for event streams") {
      val first = DagCbor.encode(obj("op" -> Integer(1))).toOption.get
      val second = DagCbor.encode(obj("seq" -> Integer(2))).toOption.get
      equal(
        DagCbor.decodeSequence(first ++ second),
        Right(Vector(obj("op" -> Integer(1)), obj("seq" -> Integer(2))))
      )
      isLeft(DagCbor.decodeSequence(first ++ second, maxItems = 1))
    }

  private def hex(bytes: Array[Byte]): String = bytes.map(value => f"${value & 0xff}%02x").mkString

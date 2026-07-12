package learnat.tests

import learnat.ipld.*
import learnat.ipld.Ipld.*
import learnat.tests.TestKit.*

object CarTests:
  def run(): Unit =
    println("CAR")

    test("round trips roots and verified blocks") {
      val firstBytes = DagCbor.encode(obj("name" -> Text("first"))).toOption.get
      val secondBytes = DagCbor.encode(obj("name" -> Text("second"))).toOption.get
      val first = CarBlock(Cid.forDagCbor(firstBytes), ByteString(firstBytes))
      val second = CarBlock(Cid.forDagCbor(secondBytes), ByteString(secondBytes))
      val file = CarFile(Vector(first.cid), Vector(first, second))
      val encoded = Car.write(file).toOption.get
      equal(Car.read(encoded), Right(file))
    }

    test("supports a roots-only CAR header") {
      val rootBytes = DagCbor.encode(obj()).toOption.get
      val file = CarFile(Vector(Cid.forDagCbor(rootBytes)), Vector.empty)
      equal(Car.read(Car.write(file).toOption.get), Right(file))
    }

    test("rejects content that does not match its CID") {
      val blockBytes = DagCbor.encode(obj("ok" -> Bool(true))).toOption.get
      val block = CarBlock(Cid.forDagCbor(blockBytes), ByteString(blockBytes))
      val encoded = Car.write(CarFile(Vector(block.cid), Vector(block))).toOption.get
      encoded(encoded.length - 1) = (encoded.last ^ 1).toByte
      isLeft(Car.read(encoded))
    }

    test("enforces file, header, block, and count limits") {
      val blockBytes = DagCbor.encode(obj("ok" -> Bool(true))).toOption.get
      val block = CarBlock(Cid.forDagCbor(blockBytes), ByteString(blockBytes))
      val encoded = Car.write(CarFile(Vector(block.cid), Vector(block))).toOption.get
      isLeft(Car.read(encoded, Car.Limits(maxFileBytes = 2)))
      isLeft(Car.read(encoded, Car.Limits(maxHeaderBytes = 2)))
      isLeft(Car.read(encoded, Car.Limits(maxBlockBytes = 2)))
      isLeft(Car.read(encoded, Car.Limits(maxBlocks = 0)))
    }

    test("rejects duplicate block sections") {
      val blockBytes = DagCbor.encode(obj("ok" -> Bool(true))).toOption.get
      val block = CarBlock(Cid.forDagCbor(blockBytes), ByteString(blockBytes))
      val encoded = Car.write(CarFile(Vector(block.cid), Vector(block, block))).toOption.get
      isLeft(Car.read(encoded))
    }

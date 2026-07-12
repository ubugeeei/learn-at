package learnat.tests

import learnat.crypto.P256KeyPair
import learnat.ipld.Car
import learnat.ipld.CarBlock
import learnat.ipld.CarFile
import learnat.ipld.Cid
import learnat.ipld.DagCbor
import learnat.ipld.Ipld
import learnat.repo.Repository
import learnat.repo.RepositoryRecord
import learnat.repo.RepositoryVerifier
import learnat.repo.RepositoryWrite
import learnat.syntax.Did
import learnat.syntax.Nsid
import learnat.syntax.RecordKey
import learnat.syntax.TidGenerator
import learnat.tests.TestKit.*

object RepositoryTests:
  private val did = Did.parse("did:web:alice.test").toOption.get
  private val collection = Nsid.parse("com.example.note").toOption.get
  private val firstKey = RecordKey.parse("first").toOption.get
  private val secondKey = RecordKey.parse("second").toOption.get

  def run(): Unit =
    println("Signed repository")

    test("creates, exports, and independently verifies an empty repository") {
      val key = P256KeyPair.generate().toOption.get
      val repo = fresh(key)
      val verified = RepositoryVerifier.verifyCar(repo.exportCar.toOption.get, did, key.publicKey)
      assert(verified.isRight, verified)
      equal(verified.map(_.records), Right(Vector.empty))
      equal(verified.map(_.commitCid), Right(repo.commitCid))
    }

    test("creates, updates, and deletes typed records with increasing revisions") {
      val key = P256KeyPair.generate().toOption.get
      val initial = fresh(key)
      val created = initial.applyWrite(RepositoryWrite.Create(record(firstKey, "one"))).toOption.get
      val updated = created.applyWrite(RepositoryWrite.Put(record(firstKey, "updated"))).toOption.get
      val withSecond = updated.applyWrite(RepositoryWrite.Create(record(secondKey, "two"))).toOption.get
      val deleted = withSecond.applyWrite(RepositoryWrite.Delete(collection, firstKey)).toOption.get

      assert(created.commit.rev.newerThan(initial.commit.rev))
      assert(updated.commit.rev.newerThan(created.commit.rev))
      assert(withSecond.commit.rev.newerThan(updated.commit.rev))
      assert(deleted.commit.rev.newerThan(withSecond.commit.rev))
      equal(deleted.get(collection, firstKey), None)
      assert(deleted.get(collection, secondKey).nonEmpty)
      equal(deleted.previousCommitCid, Some(withSecond.commitCid))
      assert(RepositoryVerifier.verifyCar(deleted.exportCar.toOption.get, did, key.publicKey).isRight)
    }

    test("applies batches atomically and rejects invalid operations") {
      val key = P256KeyPair.generate().toOption.get
      val repo = fresh(key)
      isLeft(repo.applyWrites(Vector(
        RepositoryWrite.Create(record(firstKey, "one")),
        RepositoryWrite.Create(record(firstKey, "duplicate"))
      )))
      equal(repo.records, Vector.empty)
      isLeft(repo.applyWrite(RepositoryWrite.Delete(collection, firstKey)))
    }

    test("requires record maps with matching $type") {
      val key = P256KeyPair.generate().toOption.get
      val repo = fresh(key)
      val missingType = RepositoryRecord(collection, firstKey, Ipld.obj("text" -> Ipld.Text("no type")))
      val wrongType = RepositoryRecord(collection, firstKey, Ipld.obj(
        "$type" -> Ipld.Text("com.example.other"),
        "text" -> Ipld.Text("wrong")
      ))
      isLeft(repo.applyWrite(RepositoryWrite.Create(missingType)))
      isLeft(repo.applyWrite(RepositoryWrite.Create(wrongType)))
    }

    test("rejects a valid repository signed by a different key") {
      val owner = P256KeyPair.generate().toOption.get
      val attacker = P256KeyPair.generate().toOption.get
      val repo = fresh(owner).applyWrite(RepositoryWrite.Create(record(firstKey, "one"))).toOption.get
      isLeft(RepositoryVerifier.verifyCar(repo.exportCar.toOption.get, did, attacker.publicKey))
    }

    test("rejects missing record blocks and unreachable extras") {
      val key = P256KeyPair.generate().toOption.get
      val repo = fresh(key).applyWrite(RepositoryWrite.Create(record(firstKey, "one"))).toOption.get
      val parsed = Car.read(repo.exportCar.toOption.get).toOption.get
      val recordCid = repo.reference(collection, firstKey).get._2
      val withoutRecord = Car.write(parsed.copy(blocks = parsed.blocks.filterNot(_.cid == recordCid))).toOption.get
      isLeft(RepositoryVerifier.verifyCar(withoutRecord, did, key.publicKey))

      val extraBytes = DagCbor.encode(Ipld.obj("extra" -> Ipld.Bool(true))).toOption.get
      val extra = CarBlock(Cid.forDagCbor(extraBytes), learnat.ipld.ByteString(extraBytes))
      val withExtra = Car.write(CarFile(parsed.roots, parsed.blocks :+ extra)).toOption.get
      isLeft(RepositoryVerifier.verifyCar(withExtra, did, key.publicKey))
      assert(RepositoryVerifier.verifyCar(withExtra, did, key.publicKey, allowExtraBlocks = true).isRight)
    }

    test("exports the signed commit as the single CAR root and first block") {
      val key = P256KeyPair.generate().toOption.get
      val repo = fresh(key).applyWrite(RepositoryWrite.Create(record(firstKey, "one"))).toOption.get
      val car = Car.read(repo.exportCar.toOption.get).toOption.get
      equal(car.roots, Vector(repo.commitCid))
      equal(car.blocks.head.cid, repo.commitCid)
      equal(repo.reference(collection, firstKey).map(_._1.toString), Some(s"at://${did.value}/${collection.value}/${firstKey.value}"))
    }

  private def fresh(key: P256KeyPair): Repository =
    val generator = TidGenerator.deterministic(() => 1_700_000_000_000_000L, 42).toOption.get
    Repository.create(did, key, generator).toOption.get

  private def record(key: RecordKey, text: String): RepositoryRecord =
    RepositoryRecord(
      collection,
      key,
      Ipld.obj(
        "$type" -> Ipld.Text(collection.value),
        "text" -> Ipld.Text(text),
        "createdAt" -> Ipld.Text("2026-07-10T00:00:00.000Z")
      )
    )


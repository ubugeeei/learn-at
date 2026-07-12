package learnat.tests

import learnat.ipld.Cid
import learnat.ipld.DagCbor
import learnat.ipld.Ipld
import learnat.repo.Mst
import learnat.repo.MstVerifier
import learnat.tests.TestKit.*

object MstTests:
  // Values and expected roots are pinned from the official @atproto/repo
  // "MST Interop Known Maps" suite at the commit recorded in README.md.
  private val interopValue = Cid
    .parse("bafyreie5cvv4h45feadgeuwhbcutmh6t2ceseocckahdoe6uat64zmz454").toOption.get
  private val entries = Vector(
    "com.example.note/3jzfcijpj2z2a",
    "com.example.note/3jzfcijpj2z2b",
    "com.example.note/3jzfcijpj2z2c",
    "com.example.profile/self",
    "com.example.setting/theme"
  ).map(path => path -> recordCid(path))

  def run(): Unit =
    println("Merkle Search Tree")

    test("is independent of insertion order") {
      val forward = Mst.build(entries).toOption.get
      val reverse = Mst.build(entries.reverse).toOption.get
      equal(forward.root, reverse.root)
      equal(forward.blocks.map(_.cid).toSet, reverse.blocks.map(_.cid).toSet)
      equal(forward.leaves, reverse.leaves)
    }

    test("verifies and reconstructs every sorted leaf") {
      val built = Mst.build(entries).toOption.get
      val verified = MstVerifier.verify(built.root, built.blocks)
      assert(verified.isRight, verified)
      equal(verified.map(_.leaves), Right(built.leaves))
      equal(
        verified.toOption.get.get("com.example.profile/self"),
        Some(recordCid("com.example.profile/self"))
      )
    }

    test("builds the canonical empty tree") {
      val empty = Mst.build(Vector.empty).toOption.get
      equal(empty.root.toString, "bafyreie5737gdxlw5i64vzichcalba3z2v5n6icifvx5xytvske7mr3hpm")
      equal(empty.leaves, Vector.empty)
      equal(empty.blocks.length, 1)
      equal(MstVerifier.verify(empty.root, empty.blocks).map(_.leaves), Right(Vector.empty))
    }

    test("rejects duplicate and malformed repository paths") {
      isLeft(Mst.build(Vector(entries.head, entries.head)))
      isLeft(Mst.build(Vector("short/path" -> recordCid("short/path"))))
      isLeft(Mst.build(Vector("com.example.note/../extra" -> recordCid("bad"))))
    }

    test("requires all reachable MST blocks") {
      val built = Mst.build(entries).toOption.get
      if built.blocks.length > 1 then isLeft(MstVerifier.verify(built.root, built.blocks.drop(1)))
      else throw AssertionError("fixture did not create multiple MST nodes")
    }

    test("uses two SHA-256 leading bits per tree layer") {
      val samples = entries.map(_._1).map(path => path -> Mst.leadingZeros(path))
      assert(samples.forall(_._2 >= 0))
      assert(samples.exists(_._2 > 0), samples)
    }

    test("matches official trivial and layer-two root fixtures") {
      val trivial = Mst.build(Vector("com.example.record/3jqfcqzm3fo2j" -> interopValue)).toOption
        .get
      val layerTwo = Mst.build(Vector("com.example.record/3jqfcqzm3fx2j" -> interopValue)).toOption
        .get
      equal(trivial.root.toString, "bafyreibj4lsc3aqnrvphp5xmrnfoorvru4wynt6lwidqbm2623a6tatzdu")
      equal(layerTwo.root.toString, "bafyreih7wfei65pxzhauoibu3ls7jgmkju4bspy4t2ha2qdjnzqvoy33ai")
    }

    test("matches the official multi-node simple root fixture") {
      val paths = Vector(
        "com.example.record/3jqfcqzm3fp2j",
        "com.example.record/3jqfcqzm3fr2j",
        "com.example.record/3jqfcqzm3fs2j",
        "com.example.record/3jqfcqzm3ft2j",
        "com.example.record/3jqfcqzm4fc2j"
      )
      val tree = Mst.build(paths.map(_ -> interopValue)).toOption.get
      equal(tree.root.toString, "bafyreicmahysq4n6wfuxo522m6dpiy7z7qzym3dzs756t5n7nfdgccwq7m")
    }

  private def recordCid(text: String): Cid =
    val bytes = DagCbor.encode(Ipld.obj("text" -> Ipld.Text(text))).toOption.get
    Cid.forDagCbor(bytes)

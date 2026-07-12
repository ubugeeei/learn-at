package learnat.tests

import java.nio.file.Files
import learnat.ipld.Cid
import learnat.pds.FileBlobStore
import learnat.tests.TestKit.*

object BlobStoreTests:
  def run(): Unit =
    println("Content-addressed blob storage")

    test("stores, deduplicates, and verifies raw blob content") {
      withDirectory { directory =>
        val store = FileBlobStore(directory)
        val bytes = "image bytes".getBytes
        val first = store.put("IMAGE/PNG", bytes)
        val second = store.put("image/png", bytes)
        equal(first, second)
        equal(first.map(_.cid.codec), Right(Cid.RawCodec))
        equal(
          store.get(first.toOption.get.cid).map(_.map(_.bytes.toVector)),
          Right(Some(bytes.toVector))
        )
      }
    }

    cases("rejects invalid blob input")(
      "missing subtype" -> "image",
      "parameter" -> "image/png; charset=utf-8",
      "empty" -> ""
    ) { mime =>
      withDirectory(directory => isLeft(FileBlobStore(directory).put(mime, Array[Byte](1))))
    }

    test("rejects oversized writes and non-raw lookup CIDs") {
      withDirectory { directory =>
        val store = FileBlobStore(directory, maxBlobBytes = 2)
        isLeft(store.put("image/png", Array[Byte](1, 2, 3)))
        isLeft(store.get(Cid.forDagCbor(Array[Byte](1))))
      }
    }

    test("fails closed when stored bytes are modified") {
      withDirectory { directory =>
        val store = FileBlobStore(directory)
        val blob = store.put("application/octet-stream", Array[Byte](1, 2, 3)).toOption.get
        Files.write(directory.resolve(s"${blob.cid.toString}.blob"), Array[Byte](9, 9, 9))
        isLeft(store.get(blob.cid))
      }
    }

  private def withDirectory(body: java.nio.file.Path => Unit): Unit =
    val directory = Files.createTempDirectory("learn-at-blobs")
    try body(directory)
    finally
      val stream = Files.walk(directory)
      try stream.sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists(_))
      finally stream.close()

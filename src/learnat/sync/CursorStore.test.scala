package learnat.tests

import java.nio.file.Files
import learnat.ipld.Ipld
import learnat.sync.CheckpointedFrameHandler
import learnat.sync.CursorStore
import learnat.sync.EventFrame
import learnat.sync.FileCursorStore
import learnat.sync.SyncError
import learnat.tests.TestKit.*

object CursorStoreTests:
  def run(): Unit =
    println("Durable firehose cursor")

    test("atomically persists and restores a cursor") {
      val directory = Files.createTempDirectory("learn-at-cursor")
      val path = directory.resolve("cursor")
      try
        val store = FileCursorStore(path)
        equal(store.load(), Right(None))
        equal(store.save(41), Right(()))
        equal(FileCursorStore(path).load(), Right(Some(41L)))
        equal(store.save(42), Right(()))
        equal(store.load(), Right(Some(42L)))
      finally deleteTree(directory)
    }

    cases("rejects corrupt cursor state")(
      "negative" -> "-1\n",
      "not number" -> "cursor\n",
      "too large" -> ("1" * 64)
    ) { contents =>
      val path = Files.createTempFile("learn-at-bad-cursor", ".txt")
      try
        Files.writeString(path, contents)
        isLeft(FileCursorStore(path).load())
      finally Files.deleteIfExists(path)
    }

    test("advances only after successful event application") {
      val store = MemoryCursorStore()
      var applied = Vector.empty[Long]
      val handler = CheckpointedFrameHandler(
        store,
        (sequence, _, _) =>
          if sequence == 2 then Left(SyncError("storage transaction failed"))
          else
            applied :+= sequence
            Right(())
      )
      equal(handler.handle(message(1)), Right(1L))
      equal(store.load(), Right(Some(1L)))
      isLeft(handler.handle(message(2)))
      equal(store.load(), Right(Some(1L)))
      equal(applied, Vector(1L))
      isLeft(handler.handle(message(1)))
    }

    test("does not advance when durable cursor replacement fails") {
      val store = FailingSaveCursorStore()
      var applied = 0
      val handler = CheckpointedFrameHandler(
        store,
        (_, _, _) =>
          applied += 1
          Right(())
      )
      isLeft(handler.handle(message(7)))
      equal(applied, 1)
      equal(store.load(), Right(None))
    }

    test("rejects missing sequence and server error frames") {
      val handler = CheckpointedFrameHandler(MemoryCursorStore(), (_, _, _) => Right(()))
      isLeft(handler.handle(EventFrame.Message("#commit", Ipld.obj())))
      isLeft(handler.handle(EventFrame.Error("FutureCursor", Some("resync"))))
      equal(handler.resumeCursor, Right(None))
    }

  private def message(sequence: Long): EventFrame = EventFrame
    .Message("#commit", Ipld.obj("seq" -> Ipld.Integer(sequence)))

  final private class MemoryCursorStore extends CursorStore:
    private var cursor: Option[Long] = None
    def load(): Either[SyncError, Option[Long]] = Right(cursor)
    def save(value: Long): Either[SyncError, Unit] =
      cursor = Some(value)
      Right(())

  private object MemoryCursorStore:
    def apply(): MemoryCursorStore = new MemoryCursorStore

  final private class FailingSaveCursorStore extends CursorStore:
    def load(): Either[SyncError, Option[Long]] = Right(None)
    def save(cursor: Long): Either[SyncError, Unit] =
      val _ = cursor
      Left(SyncError("disk full"))

  private object FailingSaveCursorStore:
    def apply(): FailingSaveCursorStore = new FailingSaveCursorStore

  private def deleteTree(root: java.nio.file.Path): Unit = if Files.exists(root) then
    val paths = Files.walk(root)
    try paths.sorted(java.util.Comparator.reverseOrder())
        .forEach(path => Files.deleteIfExists(path))
    finally paths.close()

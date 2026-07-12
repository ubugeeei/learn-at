package learnat.pds

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import learnat.ipld.Cid

/** Immutable metadata returned after content-addressed blob storage. */
final case class StoredBlob(cid: Cid, mimeType: String, size: Long)

/** Verified blob bytes and metadata loaded from storage. */
final case class BlobContent(metadata: StoredBlob, bytes: Array[Byte])

/** Storage contract used by upload/get-blob HTTP boundaries. */
trait BlobStore:
  /** Validates, hashes, and stores exact bytes, deduplicating identical content. */
  def put(mimeType: String, bytes: Array[Byte]): Either[LocalPdsError, StoredBlob]

  /** Loads bytes only if their raw CID still matches the requested content address. */
  def get(cid: Cid): Either[LocalPdsError, Option[BlobContent]]

/** Bounded in-memory blob store used only by ephemeral local PDS tests and processes. */
final class MemoryBlobStore(maxBlobBytes: Int = 5 * 1024 * 1024) extends BlobStore:
  private var values = Map.empty[Cid, BlobContent]

  def put(mimeType: String, bytes: Array[Byte]): Either[LocalPdsError, StoredBlob] = synchronized {
    for
      mime <- FileBlobStore.validateMimeType(mimeType)
      _ <- Either
        .cond(bytes.length <= maxBlobBytes, (), LocalPdsError(s"blob exceeds $maxBlobBytes bytes"))
      cid = Cid.forRaw(bytes)
      metadata = StoredBlob(cid, mime, bytes.length.toLong)
    yield
      values = values.updated(cid, BlobContent(metadata, bytes.clone()))
      metadata
  }

  def get(cid: Cid): Either[LocalPdsError, Option[BlobContent]] = synchronized {
    if cid.codec != Cid.RawCodec then Left(LocalPdsError("blob CID must use the raw codec"))
    else Right(values.get(cid).map(value => value.copy(bytes = value.bytes.clone())))
  }

object MemoryBlobStore:
  def apply(maxBlobBytes: Int = 5 * 1024 * 1024): MemoryBlobStore =
    new MemoryBlobStore(maxBlobBytes)

/**
 * File-backed content-addressed blob store.
 *
 * Each raw CID has a data file and a small UTF-8 MIME sidecar. New files are fully written before
 * rename. Reads recompute the CID, so disk corruption never becomes a successful response.
 */
final class FileBlobStore(directory: Path, maxBlobBytes: Int = 5 * 1024 * 1024) extends BlobStore:
  def put(mimeType: String, bytes: Array[Byte]): Either[LocalPdsError, StoredBlob] =
    for
      normalized <- FileBlobStore.validateMimeType(mimeType)
      _ <- Either
        .cond(bytes.length <= maxBlobBytes, (), LocalPdsError(s"blob exceeds $maxBlobBytes bytes"))
      cid = Cid.forRaw(bytes)
      _ <- writeIfMissing(dataPath(cid), bytes)
      _ <- writeIfMissing(metaPath(cid), normalized.getBytes(StandardCharsets.UTF_8))
    yield StoredBlob(cid, normalized, bytes.length.toLong)

  def get(cid: Cid): Either[LocalPdsError, Option[BlobContent]] =
    if cid.codec != Cid.RawCodec then Left(LocalPdsError("blob CID must use the raw codec"))
    else if !Files.exists(dataPath(cid)) && !Files.exists(metaPath(cid)) then Right(None)
    else if !Files.exists(dataPath(cid)) || !Files.exists(metaPath(cid)) then
      Left(LocalPdsError(s"blob ${cid.toString} has incomplete storage state"))
    else
      try
        val size = Files.size(dataPath(cid))
        if size > maxBlobBytes then Left(LocalPdsError(s"stored blob exceeds $maxBlobBytes bytes"))
        else
          val bytes = Files.readAllBytes(dataPath(cid))
          val actual = Cid.forRaw(bytes)
          if actual != cid then
            Left(LocalPdsError(s"stored blob does not match CID ${cid.toString}"))
          else
            val mimeBytes = Files.readAllBytes(metaPath(cid))
            if mimeBytes.length > FileBlobStore.MaxMimeBytes then
              Left(LocalPdsError("stored blob MIME metadata is oversized"))
            else
              FileBlobStore.validateMimeType(String(mimeBytes, StandardCharsets.UTF_8))
                .map(mime => Some(BlobContent(StoredBlob(cid, mime, size), bytes)))
      catch
        case error: Exception =>
          Left(LocalPdsError(s"failed to read blob ${cid.toString}: ${error.getMessage}"))

  private def dataPath(cid: Cid): Path = directory.resolve(s"${cid.toString}.blob")
  private def metaPath(cid: Cid): Path = directory.resolve(s"${cid.toString}.mime")

  private def writeIfMissing(path: Path, bytes: Array[Byte]): Either[LocalPdsError, Unit] =
    if Files.exists(path) then Right(())
    else
      val temporary = directory.resolve(s".${path.getFileName}.${UUID.randomUUID()}.tmp")
      try
        Files.createDirectories(directory)
        Files.write(temporary, bytes)
        try Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE)
        catch
          case _: AtomicMoveNotSupportedException          => Files.move(temporary, path)
          case _: java.nio.file.FileAlreadyExistsException => Files.deleteIfExists(temporary)
        Right(())
      catch
        case error: Exception =>
          Files.deleteIfExists(temporary)
          Left(LocalPdsError(s"failed to persist blob file $path: ${error.getMessage}"))

object FileBlobStore:
  private val MaxMimeBytes = 256
  private val Mime = "^[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*$".r

  def apply(directory: Path, maxBlobBytes: Int = 5 * 1024 * 1024): FileBlobStore =
    new FileBlobStore(directory, maxBlobBytes)

  private[pds] def validateMimeType(value: String): Either[LocalPdsError, String] =
    val normalized = value.trim.toLowerCase(java.util.Locale.ROOT)
    Either.cond(
      normalized.length <= MaxMimeBytes && Mime.matches(normalized),
      normalized,
      LocalPdsError("blob Content-Type must be a simple type/subtype MIME value")
    )

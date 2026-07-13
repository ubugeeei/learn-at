package learnat.pds

import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.util.Base64
import java.util.UUID
import learnat.crypto.P256KeyPair
import learnat.ipld.DagJson
import learnat.json.Json
import learnat.repo.Repository
import learnat.repo.RepositoryRecord
import learnat.syntax.Did
import learnat.syntax.Nsid
import learnat.syntax.RecordKey
import learnat.syntax.Tid
import learnat.sync.RetainedEventLog

/** Restored signing material and record values before a fresh commit is produced. */
final private[pds] case class StoredPdsState(
    signingKey: P256KeyPair,
    records: Vector[RepositoryRecord],
    lastRevision: Tid,
    eventFrames: Vector[Array[Byte]]
)

/**
 * Atomic JSON persistence for the single-account local PDS.
 *
 * This deliberately readable format helps the storage chapter. PKCS#8 key bytes are protected only
 * by owner-only file permissions, not encryption or hardware custody, and therefore remain
 * development-grade.
 */
final private[pds] class LocalPdsStore(directory: Path, maxStateBytes: Int = 32 * 1024 * 1024):
  private val statePath = directory.resolve("state.json")

  /** Loads and validates state only when the stored DID matches the bound origin. */
  def load(expectedDid: Did): Either[LocalPdsError, Option[StoredPdsState]] =
    if !Files.exists(statePath) then Right(None)
    else
      try
        val size = Files.size(statePath)
        if size > maxStateBytes then Left(LocalPdsError(s"PDS state exceeds $maxStateBytes bytes"))
        else
          val bytes = Files.readAllBytes(statePath)
          for
            json <- Json.parse(String(bytes, StandardCharsets.UTF_8)).left
              .map(error => LocalPdsError(s"invalid PDS state JSON: $error"))
            version <- json.field("version").flatMap(_.asLong).left
              .map(error => LocalPdsError(error.message))
            _ <- Either.cond(
              version == 1 || version == 2,
              (),
              LocalPdsError(s"unsupported PDS state version $version")
            )
            didText <- json.field("did").flatMap(_.asString).left
              .map(error => LocalPdsError(error.message))
            _ <- Either.cond(
              didText == expectedDid.value,
              (),
              LocalPdsError(s"stored DID $didText does not match bound DID ${expectedDid.value}")
            )
            privateText <- json.field("privateKeyPkcs8").flatMap(_.asString).left
              .map(error => LocalPdsError(error.message))
            privateBytes <- decodeBase64(privateText, "private key")
            publicMultikey <- json.field("publicKeyMultibase").flatMap(_.asString).left
              .map(error => LocalPdsError(error.message))
            key <- P256KeyPair.restore(privateBytes, publicMultikey).left
              .map(error => LocalPdsError(error.toString))
            revisionText <- json.field("rev").flatMap(_.asString).left
              .map(error => LocalPdsError(error.message))
            revision <- Tid.parse(revisionText).left.map(error => LocalPdsError(error.toString))
            recordsJson <- json.field("records").flatMap(_.asArray).left
              .map(error => LocalPdsError(error.message))
            records <- recordsJson.foldLeft[Either[LocalPdsError, Vector[RepositoryRecord]]](Right(
              Vector.empty
            )) { (result, item) =>
              for
                values <- result
                collectionText <- item.field("collection").flatMap(_.asString).left
                  .map(error => LocalPdsError(error.message))
                collection <- Nsid.parse(collectionText).left
                  .map(error => LocalPdsError(error.toString))
                recordKeyText <- item.field("rkey").flatMap(_.asString).left
                  .map(error => LocalPdsError(error.message))
                recordKey <- RecordKey.parse(recordKeyText).left
                  .map(error => LocalPdsError(error.toString))
                valueJson <- item.field("value").left.map(error => LocalPdsError(error.message))
                value <- DagJson.decode(valueJson).left.map(error => LocalPdsError(error.toString))
              yield values :+ RepositoryRecord(collection, recordKey, value)
            }
            eventFrames <-
              if version == 1 then Right(Vector.empty)
              else
                for
                  eventsJson <- json.field("events").flatMap(_.asArray).left
                    .map(error => LocalPdsError(error.message))
                  frames <- eventsJson.foldLeft[Either[LocalPdsError, Vector[Array[Byte]]]](Right(
                    Vector.empty
                  )) { (result, item) =>
                    for
                      values <- result
                      encoded <- item.asString.left.map(error => LocalPdsError(error.message))
                      bytes <- decodeBase64(encoded, "event frame")
                    yield values :+ bytes
                  }
                yield frames
          yield Some(StoredPdsState(key, records, revision, eventFrames))
      catch
        case error: Exception =>
          Left(LocalPdsError(s"failed to read ${statePath.toAbsolutePath}: ${error.getMessage}"))

  /** Writes a complete replacement file, forces it, then atomically renames it. */
  def save(
      did: Did,
      signingKey: P256KeyPair,
      repository: Repository,
      eventLog: RetainedEventLog
  ): Either[LocalPdsError, Unit] =
    val json = Json.obj(
      "version" -> Json.Num(2),
      "did" -> Json.Str(did.value),
      "privateKeyPkcs8" ->
        Json.Str(Base64.getEncoder.encodeToString(signingKey.privateKeyPkcs8.toArray)),
      "publicKeyMultibase" -> Json.Str(signingKey.publicKey.multikey),
      "rev" -> Json.Str(repository.commit.rev.value),
      "records" -> Json.Arr(repository.records.map(record =>
        Json.obj(
          "collection" -> Json.Str(record.collection.value),
          "rkey" -> Json.Str(record.recordKey.value),
          "value" -> DagJson.encode(record.value)
        )
      )),
      "events" -> Json.Arr(
        eventLog.framesForPersistence
          .map(bytes => Json.Str(Base64.getEncoder.encodeToString(bytes)))
      )
    )
    val bytes = json.render.getBytes(StandardCharsets.UTF_8)
    if bytes.length > maxStateBytes then
      Left(LocalPdsError(s"PDS state exceeds $maxStateBytes bytes"))
    else
      val temporary = directory.resolve(s"state.json.tmp-${UUID.randomUUID()}")
      try
        Files.createDirectories(directory)
        Files.write(temporary, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        setOwnerOnly(temporary)
        val channel = FileChannel.open(temporary, StandardOpenOption.WRITE)
        try channel.force(true)
        finally channel.close()
        try
          Files.move(
            temporary,
            statePath,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
          )
        catch
          case _: AtomicMoveNotSupportedException => Files
              .move(temporary, statePath, StandardCopyOption.REPLACE_EXISTING)
        setOwnerOnly(statePath)
        Right(())
      catch
        case error: Exception =>
          Files.deleteIfExists(temporary)
          Left(LocalPdsError(s"failed to persist ${statePath.toAbsolutePath}: ${error.getMessage}"))

  private def decodeBase64(value: String, name: String): Either[LocalPdsError, Array[Byte]] =
    try Right(Base64.getDecoder.decode(value))
    catch case _: IllegalArgumentException => Left(LocalPdsError(s"invalid base64 PDS $name"))

  private def setOwnerOnly(path: Path): Unit =
    try
      Files.setPosixFilePermissions(
        path,
        java.util.Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
      )
      ()
    catch case _: UnsupportedOperationException => ()

package learnat.repo

import learnat.crypto.P256KeyPair
import learnat.crypto.P256PublicKey
import learnat.ipld.ByteString
import learnat.ipld.Car
import learnat.ipld.CarBlock
import learnat.ipld.CarFile
import learnat.ipld.Cid
import learnat.ipld.DagCbor
import learnat.ipld.Ipld
import learnat.syntax.AtUri
import learnat.syntax.Did
import learnat.syntax.Nsid
import learnat.syntax.RecordKey
import learnat.syntax.Tid
import learnat.syntax.TidGenerator

/** A failed repository mutation, encoding operation, or verification invariant. */
final case class RepositoryError(message: String):
  override def toString: String = message

/** A typed record location paired with its DAG-CBOR value. */
final case class RepositoryRecord(collection: Nsid, recordKey: RecordKey, value: Ipld):
  def path: String = Mst.recordPath(collection, recordKey)

/** Supported mutations against the current repository record map. */
enum RepositoryWrite:
  case Create(record: RepositoryRecord)
  case Put(record: RepositoryRecord)
  case Delete(collection: Nsid, recordKey: RecordKey)

/** Decoded version-3 repository commit. */
final case class SignedCommit(
    did: Did,
    version: Long,
    data: Cid,
    rev: Tid,
    previous: Option[Cid],
    signature: ByteString
):
  /** Reconstructs the exact canonical object covered by the signature. */
  def unsignedValue: Ipld = SignedCommit.unsignedValue(did, data, rev, previous)

object SignedCommit:
  /** Builds the unsigned commit object in repository format field semantics. */
  def unsignedValue(did: Did, data: Cid, rev: Tid, previous: Option[Cid]): Ipld =
    Ipld.obj(
      "did" -> Ipld.Text(did.value),
      "version" -> Ipld.Integer(3),
      "data" -> Ipld.Link(data),
      "rev" -> Ipld.Text(rev.value),
      "prev" -> previous.fold[Ipld](Ipld.Null)(Ipld.Link.apply)
    )

/**
 * Immutable, fully committed account repository.
 *
 * Mutations return a new instance. This teaching implementation rebuilds the
 * MST from the complete record map to keep the mutation path auditable and to
 * use the deterministic builder as its correctness oracle.
 */
final class Repository private (
    val did: Did,
    private val signingKey: P256KeyPair,
    private val tidGenerator: TidGenerator,
    private val recordsByPath: Map[String, RepositoryRecord],
    val commit: SignedCommit,
    val commitCid: Cid,
    val previousCommitCid: Option[Cid],
    val blocks: Vector[CarBlock]
):
  /** Returns the current record without consulting an application index. */
  def get(collection: Nsid, recordKey: RecordKey): Option[RepositoryRecord] =
    recordsByPath.get(Mst.recordPath(collection, recordKey))

  /** Returns all records in canonical repository path order. */
  def records: Vector[RepositoryRecord] = recordsByPath.values.toVector.sortBy(_.path)

  /** Applies one atomic write and produces a new signed commit. */
  def applyWrite(write: RepositoryWrite): Either[RepositoryError, Repository] =
    applyWrites(Vector(write))

  /**
   * Applies a batch atomically. Any invalid operation leaves this immutable
   * instance usable and no partial commit is produced.
   */
  def applyWrites(writes: Vector[RepositoryWrite]): Either[RepositoryError, Repository] =
    writes.foldLeft[Either[RepositoryError, Map[String, RepositoryRecord]]](Right(recordsByPath)) {
      case (result, RepositoryWrite.Create(record)) =>
        result.flatMap { records =>
          if records.contains(record.path) then Left(RepositoryError(s"record already exists: ${record.path}"))
          else validateRecord(record).map(_ => records.updated(record.path, record))
        }
      case (result, RepositoryWrite.Put(record)) =>
        result.flatMap(records => validateRecord(record).map(_ => records.updated(record.path, record)))
      case (result, RepositoryWrite.Delete(collection, recordKey)) =>
        result.flatMap { records =>
          val path = Mst.recordPath(collection, recordKey)
          if records.contains(path) then Right(records.removed(path))
          else Left(RepositoryError(s"record does not exist: $path"))
        }
    }.flatMap { records =>
      Repository.commit(
        did,
        signingKey,
        tidGenerator,
        records,
        previousRevision = Some(commit.rev),
        previousCommitCid = Some(commitCid)
      )
    }

  /** Exports the complete current repository as a one-root verified CAR. */
  def exportCar: Either[RepositoryError, Array[Byte]] =
    Car.write(CarFile(Vector(commitCid), blocks)).left.map(error => RepositoryError(error.toString))

  /** Returns the stable AT URI and current CID for a record. */
  def reference(collection: Nsid, recordKey: RecordKey): Option[(AtUri, Cid)] =
    get(collection, recordKey).flatMap { record =>
      encodeRecord(record).toOption.map { (_, cid) => AtUri.record(did, collection, recordKey) -> cid }
    }

  private def validateRecord(record: RepositoryRecord): Either[RepositoryError, Unit] = Repository.validateRecord(record)
  private def encodeRecord(record: RepositoryRecord): Either[RepositoryError, (CarBlock, Cid)] = Repository.encodeRecord(record)

object Repository:
  /** Creates the first signed commit, optionally with initial records. */
  def create(
      did: Did,
      signingKey: P256KeyPair,
      tidGenerator: TidGenerator,
      initialRecords: Vector[RepositoryRecord] = Vector.empty
  ): Either[RepositoryError, Repository] =
    val duplicate = initialRecords.groupMapReduce(_.path)(_ => 1)(_ + _).collectFirst { case (path, count) if count > 1 => path }
    duplicate match
      case Some(path) => Left(RepositoryError(s"duplicate initial record: $path"))
      case None =>
        initialRecords.foldLeft[Either[RepositoryError, Map[String, RepositoryRecord]]](Right(Map.empty)) { (result, record) =>
          for
            records <- result
            _ <- validateRecord(record)
          yield records.updated(record.path, record)
        }.flatMap(records => commit(did, signingKey, tidGenerator, records, None, None))

  private[repo] def commit(
      did: Did,
      signingKey: P256KeyPair,
      tidGenerator: TidGenerator,
      records: Map[String, RepositoryRecord],
      previousRevision: Option[Tid],
      previousCommitCid: Option[Cid]
  ): Either[RepositoryError, Repository] =
    val encodedRecords = records.values.toVector.sortBy(_.path).foldLeft[Either[RepositoryError, Vector[(String, CarBlock)]]](Right(Vector.empty)) {
      (result, record) =>
        for
          values <- result
          encoded <- encodeRecord(record)
        yield values :+ (record.path -> encoded._1)
    }
    for
      encoded <- encodedRecords
      mst <- Mst.build(encoded.map { case (path, block) => path -> block.cid })
        .left.map(error => RepositoryError(error.toString))
      revision = tidGenerator.next(previousRevision)
      // Repo format v3 keeps `prev` null; event metadata carries the previous
      // commit CID separately for synchronization.
      unsigned = SignedCommit.unsignedValue(did, mst.root, revision, None)
      unsignedBytes <- DagCbor.encode(unsigned).left.map(error => RepositoryError(error.toString))
      signature <- signingKey.sign(unsignedBytes).left.map(error => RepositoryError(error.toString))
      signed = Ipld.obj(
        "did" -> Ipld.Text(did.value),
        "version" -> Ipld.Integer(3),
        "data" -> Ipld.Link(mst.root),
        "rev" -> Ipld.Text(revision.value),
        "prev" -> Ipld.Null,
        "sig" -> Ipld.Bytes(signature)
      )
      commitBytes <- DagCbor.encode(signed).left.map(error => RepositoryError(error.toString))
    yield
      val commitCid = Cid.forDagCbor(commitBytes)
      val commitBlock = CarBlock(commitCid, ByteString(commitBytes))
      val recordBlocks = encoded.map(_._2)
      val allBlocks = (Vector(commitBlock) ++ mst.blocks ++ recordBlocks).distinctBy(_.cid)
      val decodedCommit = SignedCommit(did, 3, mst.root, revision, None, signature)
      new Repository(did, signingKey, tidGenerator, records, decodedCommit, commitCid, previousCommitCid, allBlocks)

  private[repo] def validateRecord(record: RepositoryRecord): Either[RepositoryError, Unit] =
    Mst.validatePath(record.path).left.map(error => RepositoryError(error.toString)).flatMap { _ =>
      record.value match
        case Ipld.Map(fields) =>
          fields.toMap.get("$type") match
            case Some(Ipld.Text(value)) if value == record.collection.value => Right(())
            case Some(Ipld.Text(value)) => Left(RepositoryError(s"record ${record.path} has mismatched $$type $value"))
            case _ => Left(RepositoryError(s"record ${record.path} must contain string $$type"))
        case _ => Left(RepositoryError(s"record ${record.path} must be a DAG-CBOR map"))
    }

  private def encodeRecord(record: RepositoryRecord): Either[RepositoryError, (CarBlock, Cid)] =
    validateRecord(record).flatMap { _ =>
      DagCbor.encode(record.value).left.map(error => RepositoryError(error.toString)).map { bytes =>
        val cid = Cid.forDagCbor(bytes)
        CarBlock(cid, ByteString(bytes)) -> cid
      }
    }

/** A repository whose commit signature, tree, and every record block were verified. */
final case class VerifiedRepository(commitCid: Cid, commit: SignedCommit, mst: MstSnapshot, records: Vector[RepositoryRecord])

/** Full CAR-to-record verification, independent from the serving PDS. */
object RepositoryVerifier:
  /**
   * Verifies a complete repository export using the expected DID and the
   * signing key obtained from that DID's independently resolved document.
   */
  def verifyCar(
      carBytes: Array[Byte],
      expectedDid: Did,
      signingKey: P256PublicKey,
      allowExtraBlocks: Boolean = false
  ): Either[RepositoryError, VerifiedRepository] =
    for
      car <- Car.read(carBytes).left.map(error => RepositoryError(error.toString))
      root <- car.roots match
        case Vector(value) => Right(value)
        case roots => Left(RepositoryError(s"repository CAR must have exactly one root, found ${roots.length}"))
      blockMap = car.blocks.map(block => block.cid -> block).toMap
      commitBlock <- blockMap.get(root).toRight(RepositoryError(s"repository CAR is missing root commit block $root"))
      commitValue <- DagCbor.decode(commitBlock.bytes.toArray).left.map(error => RepositoryError(error.toString))
      commit <- decodeCommit(commitValue)
      _ <- Either.cond(commit.did == expectedDid, (), RepositoryError(s"commit DID ${commit.did.value} does not match ${expectedDid.value}"))
      unsignedBytes <- DagCbor.encode(commit.unsignedValue).left.map(error => RepositoryError(error.toString))
      _ <- Either.cond(
        signingKey.verify(unsignedBytes, commit.signature.toArray),
        (),
        RepositoryError("repository commit signature is invalid")
      )
      mst <- MstVerifier.verify(commit.data, car.blocks).left.map(error => RepositoryError(error.toString))
      records <- decodeRecords(mst.leaves, blockMap)
      reachable = Set(root) ++ mst.blocks.map(_.cid) ++ mst.leaves.map(_.value)
      extras = blockMap.keySet.diff(reachable)
      _ <- Either.cond(allowExtraBlocks || extras.isEmpty, (), RepositoryError(s"repository CAR contains ${extras.size} unreachable blocks"))
    yield VerifiedRepository(root, commit, mst, records)

  private def decodeCommit(value: Ipld): Either[RepositoryError, SignedCommit] = value match
    case Ipld.Map(fields) if fields.map(_._1).toSet == Set("did", "version", "data", "rev", "prev", "sig") =>
      val values = fields.toMap
      for
        did <- values("did") match
          case Ipld.Text(value) => Did.parse(value).left.map(error => RepositoryError(error.toString))
          case _ => Left(RepositoryError("commit did must be a string"))
        version <- values("version") match
          case Ipld.Integer(3) => Right(3L)
          case Ipld.Integer(other) => Left(RepositoryError(s"unsupported repository version $other"))
          case _ => Left(RepositoryError("commit version must be an integer"))
        data <- values("data") match
          case Ipld.Link(cid) => Right(cid)
          case _ => Left(RepositoryError("commit data must be a CID link"))
        rev <- values("rev") match
          case Ipld.Text(value) => Tid.parse(value).left.map(error => RepositoryError(error.toString))
          case _ => Left(RepositoryError("commit rev must be a TID string"))
        previous <- values("prev") match
          case Ipld.Null => Right(None)
          case Ipld.Link(cid) => Right(Some(cid))
          case _ => Left(RepositoryError("commit prev must be null or a CID link"))
        signature <- values("sig") match
          case Ipld.Bytes(bytes) if bytes.size == 64 => Right(bytes)
          case Ipld.Bytes(bytes) => Left(RepositoryError(s"commit signature must be 64 bytes, found ${bytes.size}"))
          case _ => Left(RepositoryError("commit sig must be bytes"))
      yield SignedCommit(did, version, data, rev, previous, signature)
    case Ipld.Map(_) => Left(RepositoryError("commit must contain exactly did, version, data, rev, prev, and sig"))
    case _ => Left(RepositoryError("repository root must be a commit map"))

  private def decodeRecords(
      leaves: Vector[MstLeaf],
      blocks: Map[Cid, CarBlock]
  ): Either[RepositoryError, Vector[RepositoryRecord]] =
    leaves.foldLeft[Either[RepositoryError, Vector[RepositoryRecord]]](Right(Vector.empty)) { (result, leaf) =>
      for
        records <- result
        block <- blocks.get(leaf.value).toRight(RepositoryError(s"missing record block ${leaf.value} for ${leaf.path}"))
        value <- DagCbor.decode(block.bytes.toArray).left.map(error => RepositoryError(s"invalid record ${leaf.path}: $error"))
        parts = leaf.path.split("/", -1)
        collection <- Nsid.parse(parts(0)).left.map(error => RepositoryError(error.toString))
        recordKey <- RecordKey.parse(parts(1)).left.map(error => RepositoryError(error.toString))
        record = RepositoryRecord(collection, recordKey, value)
        _ <- Repository.validateRecord(record)
      yield records :+ record
    }

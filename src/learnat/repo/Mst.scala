package learnat.repo

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import learnat.ipld.ByteString
import learnat.ipld.CarBlock
import learnat.ipld.Cid
import learnat.ipld.DagCbor
import learnat.ipld.Ipld
import learnat.syntax.Nsid
import learnat.syntax.RecordKey

/** A structural, encoding, or completeness failure while building or verifying an atproto MST. */
final case class MstError(message: String):
  override def toString: String = message

/** A repository path and the CID of the DAG-CBOR record stored at that path. */
final case class MstLeaf(path: String, value: Cid)

/**
 * A fully materialized Merkle Search Tree.
 *
 * `blocks` contains only MST node blocks. Record blocks are intentionally kept
 * separate so callers can distinguish tree proofs from record content.
 */
final case class MstSnapshot(root: Cid, blocks: Vector[CarBlock], leaves: Vector[MstLeaf]):
  /** Looks up a record CID by its normalized `collection/rkey` path. */
  def get(path: String): Option[Cid] = leaves.find(_.path == path).map(_.value)

/**
 * Deterministic atproto Merkle Search Tree construction.
 *
 * This correctness-first implementation rebuilds the tree from a complete
 * key/value set. It is therefore intentionally simpler than an incremental
 * mutator while producing the same interoperable root CID.
 */
object Mst:
  private final case class Item(path: String, value: Cid, layer: Int)
  private sealed trait Element
  private final case class Child(node: BuiltNode) extends Element
  private final case class Leaf(item: Item) extends Element
  private final case class BuiltNode(layer: Int, elements: Vector[Element], cid: Cid, blocks: Vector[CarBlock])

  /**
   * Builds a canonical MST from repository path to record-CID mappings.
   * Input order has no effect on the returned root; duplicate paths fail.
   */
  def build(entries: Vector[(String, Cid)]): Either[MstError, MstSnapshot] =
    validateEntries(entries).flatMap { items =>
      if items.isEmpty then buildNode(Vector.empty, 0).map(node => MstSnapshot(node.cid, node.blocks, Vector.empty))
      else
        val rootLayer = items.map(_.layer).max
        buildNode(items, rootLayer).map { node =>
          MstSnapshot(node.cid, node.blocks, items.map(item => MstLeaf(item.path, item.value)))
        }
    }

  /** Creates the normalized repository path used as an MST key. */
  def recordPath(collection: Nsid, recordKey: RecordKey): String = s"${collection.value}/${recordKey.value}"

  /** Validates the fixed `NSID/record-key` repository path grammar. */
  def validatePath(path: String): Either[MstError, Unit] =
    val parts = path.split("/", -1).toVector
    if path.length > 1024 then Left(MstError(s"MST path exceeds 1024 characters: $path"))
    else if parts.length != 2 then Left(MstError(s"MST path must have collection/record-key form: $path"))
    else
      for
        _ <- Nsid.parse(parts.head).left.map(error => MstError(error.toString))
        _ <- RecordKey.parse(parts(1)).left.map(error => MstError(error.toString))
      yield ()

  /**
   * Returns the key's deterministic MST layer.
   *
   * Each layer consumes two leading zero bits from SHA-256, giving an expected
   * fanout of four as required by the repository format.
   */
  def leadingZeros(path: String): Int =
    val hash = MessageDigest.getInstance("SHA-256").digest(path.getBytes(StandardCharsets.UTF_8))
    var zeros = 0
    var index = 0
    var continue = true
    while index < hash.length && continue do
      val byte = hash(index) & 0xff
      if byte < 64 then zeros += 1
      if byte < 16 then zeros += 1
      if byte < 4 then zeros += 1
      if byte == 0 then zeros += 1 else continue = false
      index += 1
    zeros

  private def validateEntries(entries: Vector[(String, Cid)]): Either[MstError, Vector[Item]] =
    val sorted = entries.sortBy(_._1)
    val duplicate = sorted.sliding(2).collectFirst { case Vector(left, right) if left._1 == right._1 => left._1 }
    duplicate match
      case Some(path) => Left(MstError(s"duplicate MST path: $path"))
      case None =>
        sorted.foldLeft[Either[MstError, Vector[Item]]](Right(Vector.empty)) { case (result, (path, cid)) =>
          for
            items <- result
            _ <- validatePath(path)
          yield items :+ Item(path, cid, leadingZeros(path))
        }

  private def buildNode(items: Vector[Item], layer: Int): Either[MstError, BuiltNode] =
    if layer < 0 then Left(MstError("MST construction descended below layer zero"))
    else if items.isEmpty then serializeNode(layer, Vector.empty)
    else
      val pivotIndexes = items.indices.filter(index => items(index).layer == layer).toVector
      if pivotIndexes.isEmpty then
        buildNode(items, layer - 1).flatMap(child => serializeNode(layer, Vector(Child(child))))
      else
        val elements = Vector.newBuilder[Element]
        var cursor = 0
        var failure: Option[MstError] = None
        pivotIndexes.foreach { pivot =>
          if pivot > cursor && failure.isEmpty then
            buildNode(items.slice(cursor, pivot), layer - 1) match
              case Right(child) => elements += Child(child)
              case Left(error) => failure = Some(error)
          if failure.isEmpty then elements += Leaf(items(pivot))
          cursor = pivot + 1
        }
        if cursor < items.length && failure.isEmpty then
          buildNode(items.drop(cursor), layer - 1) match
            case Right(child) => elements += Child(child)
            case Left(error) => failure = Some(error)
        failure match
          case Some(error) => Left(error)
          case None => serializeNode(layer, elements.result())

  private def serializeNode(layer: Int, elements: Vector[Element]): Either[MstError, BuiltNode] =
    val left = elements.headOption.collect { case Child(node) => node }
    val rows = Vector.newBuilder[Ipld]
    var index = if left.nonEmpty then 1 else 0
    var previous = ""
    var failure: Option[MstError] = None
    while index < elements.length && failure.isEmpty do
      elements(index) match
        case Child(_) => failure = Some(MstError("invalid MST node: neighboring child pointers"))
        case Leaf(item) =>
          val right = elements.lift(index + 1).collect { case Child(node) => node }
          val prefix = commonPrefix(previous, item.path)
          rows += Ipld.obj(
            "p" -> Ipld.Integer(prefix),
            "k" -> Ipld.Bytes(ByteString(item.path.drop(prefix).getBytes(StandardCharsets.US_ASCII))),
            "v" -> Ipld.Link(item.value),
            "t" -> right.fold[Ipld](Ipld.Null)(node => Ipld.Link(node.cid))
          )
          previous = item.path
          index += (if right.nonEmpty then 2 else 1)
    failure match
      case Some(error) => Left(error)
      case None =>
        val value = Ipld.obj(
          "l" -> left.fold[Ipld](Ipld.Null)(node => Ipld.Link(node.cid)),
          "e" -> Ipld.List(rows.result())
        )
        DagCbor.encode(value).left.map(error => MstError(error.toString)).map { bytes =>
          val cid = Cid.forDagCbor(bytes)
          val childBlocks = elements.collect { case Child(node) => node.blocks }.flatten.distinctBy(_.cid)
          val current = CarBlock(cid, ByteString(bytes))
          BuiltNode(layer, elements, cid, childBlocks :+ current)
        }

  private def commonPrefix(left: String, right: String): Int =
    var index = 0
    val limit = math.min(left.length, right.length)
    while index < limit && left.charAt(index) == right.charAt(index) do index += 1
    index

/** Decodes untrusted MST blocks and re-checks links, layers, key order, and reachability. */
object MstVerifier:
  /** Resource limits applied before an untrusted tree can exhaust memory or recursion. */
  final case class Limits(maxNodes: Int = 1_000_000, maxLeaves: Int = 10_000_000)

  private final case class Row(path: String, value: Cid, right: Option[DecodedNode])
  private final case class DecodedNode(cid: Cid, left: Option[DecodedNode], rows: Vector[Row])
  private final case class ValidatedNode(layer: Int, leaves: Vector[MstLeaf])

  /**
   * Verifies every node reachable from `root` and reconstructs sorted leaves.
   * Missing blocks, shared child nodes, malformed prefix compression, and
   * incorrect SHA-256 layer placement are rejected.
   */
  def verify(root: Cid, blocks: Vector[CarBlock], limits: Limits = Limits()): Either[MstError, MstSnapshot] =
    val blockMap = blocks.map(block => block.cid -> block).toMap
    val visited = scala.collection.mutable.HashSet.empty[Cid]
    decodeNode(root, blockMap, visited, limits).flatMap(validateNode(_, isRoot = true, limits)).map { validated =>
      MstSnapshot(root, visited.toVector.flatMap(blockMap.get), validated.leaves)
    }

  private def decodeNode(
      cid: Cid,
      blocks: Map[Cid, CarBlock],
      visited: scala.collection.mutable.HashSet[Cid],
      limits: Limits
  ): Either[MstError, DecodedNode] =
    if cid.codec != Cid.DagCborCodec then Left(MstError(s"MST node must use dag-cbor CID: $cid"))
    else if visited.contains(cid) then Left(MstError(s"MST contains a cycle or shared child node: $cid"))
    else if visited.size >= limits.maxNodes then Left(MstError(s"MST exceeds ${limits.maxNodes} nodes"))
    else
      blocks.get(cid).toRight(MstError(s"missing MST block: $cid")).flatMap { block =>
        val bytes = block.bytes.toArray
        if !cid.verifies(bytes) then Left(MstError(s"MST block does not match CID: $cid"))
        else
          visited += cid
          DagCbor.decode(bytes).left.map(error => MstError(error.toString)).flatMap(decodeNodeValue(cid, _, blocks, visited, limits))
      }

  private def decodeNodeValue(
      cid: Cid,
      value: Ipld,
      blocks: Map[Cid, CarBlock],
      visited: scala.collection.mutable.HashSet[Cid],
      limits: Limits
  ): Either[MstError, DecodedNode] = value match
    case Ipld.Map(fields) if fields.map(_._1).toSet == Set("l", "e") =>
      val values = fields.toMap
      for
        left <- decodeChild(values("l"), blocks, visited, limits)
        rows <- values("e") match
          case Ipld.List(entries) => decodeRows(entries, blocks, visited, limits)
          case _ => Left(MstError("MST field 'e' must be a list"))
      yield DecodedNode(cid, left, rows)
    case Ipld.Map(_) => Left(MstError("MST node must contain exactly 'l' and 'e' fields"))
    case _ => Left(MstError("MST node must be a map"))

  private def decodeRows(
      entries: Vector[Ipld],
      blocks: Map[Cid, CarBlock],
      visited: scala.collection.mutable.HashSet[Cid],
      limits: Limits
  ): Either[MstError, Vector[Row]] =
    var previous = ""
    entries.foldLeft[Either[MstError, Vector[Row]]](Right(Vector.empty)) { (result, entry) =>
      result.flatMap { rows =>
        entry match
          case Ipld.Map(fields) if fields.map(_._1).toSet == Set("p", "k", "v", "t") =>
            val values = fields.toMap
            for
              prefix <- values("p") match
                case Ipld.Integer(value) if value >= 0 && value <= previous.length => Right(value.toInt)
                case _ => Left(MstError("MST entry prefix is out of range"))
              suffix <- values("k") match
                case Ipld.Bytes(value) => ascii(value)
                case _ => Left(MstError("MST entry key suffix must be bytes"))
              path = previous.take(prefix) + suffix
              _ <- Mst.validatePath(path)
              valueCid <- values("v") match
                case Ipld.Link(value) => Right(value)
                case _ => Left(MstError("MST entry value must be a CID link"))
              right <- decodeChild(values("t"), blocks, visited, limits)
            yield
              previous = path
              rows :+ Row(path, valueCid, right)
          case _ => Left(MstError("MST entry must contain exactly p, k, v, and t"))
      }
    }

  private def decodeChild(
      value: Ipld,
      blocks: Map[Cid, CarBlock],
      visited: scala.collection.mutable.HashSet[Cid],
      limits: Limits
  ): Either[MstError, Option[DecodedNode]] = value match
    case Ipld.Null => Right(None)
    case Ipld.Link(cid) => decodeNode(cid, blocks, visited, limits).map(Some.apply)
    case _ => Left(MstError("MST child pointer must be null or a CID link"))

  private def validateNode(node: DecodedNode, isRoot: Boolean, limits: Limits): Either[MstError, ValidatedNode] =
    val children = node.left.toVector ++ node.rows.flatMap(_.right)
    val childResults = children.foldLeft[Either[MstError, Vector[ValidatedNode]]](Right(Vector.empty)) { (result, child) =>
      for
        values <- result
        validated <- validateNode(child, isRoot = false, limits)
      yield values :+ validated
    }
    childResults.flatMap { validatedChildren =>
      val leafLayers = node.rows.map(row => Mst.leadingZeros(row.path)).distinct
      val layerResult =
        if leafLayers.length > 1 then Left(MstError(s"MST node ${node.cid} mixes leaf layers"))
        else if leafLayers.nonEmpty then Right(leafLayers.head)
        else if validatedChildren.nonEmpty then
          val childLayers = validatedChildren.map(_.layer).distinct
          if childLayers.length == 1 then Right(childLayers.head + 1)
          else Left(MstError(s"MST node ${node.cid} has children on different layers"))
        else if isRoot then Right(0)
        else Left(MstError(s"non-root MST node ${node.cid} is empty"))
      layerResult.flatMap { layer =>
        val expectedChildLayer = layer - 1
        if validatedChildren.exists(_.layer != expectedChildLayer) then
          Left(MstError(s"MST child of ${node.cid} is not exactly one layer lower"))
        else
          val leaves = interleave(node, validatedChildren)
          if leaves.length > limits.maxLeaves then Left(MstError(s"MST exceeds ${limits.maxLeaves} leaves"))
          else if leaves.sliding(2).exists { case Vector(left, right) => left.path >= right.path; case _ => false } then
            Left(MstError(s"MST leaves below ${node.cid} are not strictly sorted"))
          else Right(ValidatedNode(layer, leaves))
      }
    }

  private def interleave(node: DecodedNode, children: Vector[ValidatedNode]): Vector[MstLeaf] =
    val out = Vector.newBuilder[MstLeaf]
    var childIndex = 0
    if node.left.nonEmpty then
      out ++= children(childIndex).leaves
      childIndex += 1
    node.rows.foreach { row =>
      out += MstLeaf(row.path, row.value)
      if row.right.nonEmpty then
        out ++= children(childIndex).leaves
        childIndex += 1
    }
    out.result()

  private def ascii(bytes: ByteString): Either[MstError, String] =
    val array = bytes.toArray
    if array.exists(byte => (byte & 0xff) > 0x7f) then Left(MstError("MST key suffix must be ASCII"))
    else Right(String(array, StandardCharsets.US_ASCII))

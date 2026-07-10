package learnat.ipld

import java.io.ByteArrayOutputStream

final case class CarBlock(cid: Cid, bytes: ByteString)
final case class CarFile(roots: Vector[Cid], blocks: Vector[CarBlock])

final case class CarError(message: String, offset: Option[Int] = None):
  override def toString: String = offset.fold(message)(value => s"$message at byte $value")

object Car:
  final case class Limits(
      maxFileBytes: Int = 64 * 1024 * 1024,
      maxHeaderBytes: Int = 1024 * 1024,
      maxBlockBytes: Int = 8 * 1024 * 1024,
      maxBlocks: Int = 1_000_000
  )

  def write(file: CarFile): Either[CarError, Array[Byte]] =
    val header = Ipld.obj(
      "roots" -> Ipld.List(file.roots.map(Ipld.Link.apply)),
      "version" -> Ipld.Integer(1)
    )
    DagCbor.encode(header).left.map(error => CarError(error.toString)).map { headerBytes =>
      val out = ByteArrayOutputStream()
      out.write(Varint.encode(headerBytes.length))
      out.write(headerBytes)
      file.blocks.foreach { block =>
        val cidBytes = block.cid.bytes.toArray
        val value = block.bytes.toArray
        out.write(Varint.encode(cidBytes.length.toLong + value.length))
        out.write(cidBytes)
        out.write(value)
      }
      out.toByteArray
    }

  def read(bytes: Array[Byte], limits: Limits = Limits()): Either[CarError, CarFile] =
    if bytes.length > limits.maxFileBytes then Left(CarError(s"CAR exceeds ${limits.maxFileBytes} bytes"))
    else
      for
        headerLengthResult <- Varint.decode(bytes, 0).left.map(error => CarError(error.toString, error.offset))
        (headerLength, afterHeaderLength) = headerLengthResult
        _ <- Either.cond(
          headerLength <= limits.maxHeaderBytes,
          (),
          CarError(s"CAR header exceeds ${limits.maxHeaderBytes} bytes", Some(afterHeaderLength))
        )
        headerEnd <- checkedEnd(bytes, afterHeaderLength, headerLength, "truncated CAR header")
        header <- DagCbor.decode(bytes.slice(afterHeaderLength, headerEnd)).left.map(error => CarError(s"invalid CAR header: $error"))
        roots <- decodeHeader(header)
        blocks <- decodeBlocks(bytes, headerEnd, limits)
      yield CarFile(roots, blocks)

  private def decodeHeader(header: Ipld): Either[CarError, Vector[Cid]] = header match
    case Ipld.Map(fields) =>
      val values = fields.toMap
      (values.get("version"), values.get("roots")) match
        case (Some(Ipld.Integer(1)), Some(Ipld.List(roots))) =>
          roots.foldLeft[Either[CarError, Vector[Cid]]](Right(Vector.empty)) {
            case (result, Ipld.Link(cid)) => result.map(_ :+ cid)
            case (_, _) => Left(CarError("CAR header roots must contain only CID links"))
          }
        case (Some(Ipld.Integer(version)), _) => Left(CarError(s"unsupported CAR version: $version"))
        case _ => Left(CarError("CAR header must contain version 1 and roots"))
    case _ => Left(CarError("CAR header must be a DAG-CBOR map"))

  private def decodeBlocks(bytes: Array[Byte], start: Int, limits: Limits): Either[CarError, Vector[CarBlock]] =
    val blocks = Vector.newBuilder[CarBlock]
    val seen = scala.collection.mutable.HashSet.empty[Cid]
    var offset = start
    var count = 0
    var failure: Option[CarError] = None
    while offset < bytes.length && failure.isEmpty do
      Varint.decode(bytes, offset) match
        case Left(error) => failure = Some(CarError(error.toString, error.offset))
        case Right((sectionLength, sectionStart)) =>
          if sectionLength <= 0 then failure = Some(CarError("CAR block section must not be empty", Some(offset)))
          else if sectionLength > limits.maxBlockBytes then
            failure = Some(CarError(s"CAR block section exceeds ${limits.maxBlockBytes} bytes", Some(offset)))
          else
            checkedEnd(bytes, sectionStart, sectionLength, "truncated CAR block section") match
              case Left(error) => failure = Some(error)
              case Right(sectionEnd) =>
                Cid.readPrefix(bytes, sectionStart) match
                  case Left(error) => failure = Some(CarError(s"invalid block CID: $error", error.offset))
                  case Right((_, contentStart)) if contentStart > sectionEnd =>
                    failure = Some(CarError("CID extends beyond CAR block section", Some(sectionStart)))
                  case Right((cid, contentStart)) =>
                    val content = bytes.slice(contentStart, sectionEnd)
                    if !cid.verifies(content) then failure = Some(CarError(s"block content does not match CID $cid", Some(contentStart)))
                    else if seen.contains(cid) then failure = Some(CarError(s"duplicate CAR block $cid", Some(sectionStart)))
                    else
                      seen += cid
                      blocks += CarBlock(cid, ByteString(content))
                      count += 1
                      if count > limits.maxBlocks then failure = Some(CarError(s"CAR contains more than ${limits.maxBlocks} blocks"))
                      offset = sectionEnd
    failure.toLeft(blocks.result())

  private def checkedEnd(bytes: Array[Byte], start: Int, length: Long, message: String): Either[CarError, Int] =
    val end = start.toLong + length
    if length < 0 || end > bytes.length then Left(CarError(message, Some(start))) else Right(end.toInt)

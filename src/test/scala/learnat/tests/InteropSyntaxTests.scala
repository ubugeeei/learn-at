package learnat.tests

import learnat.syntax.*
import learnat.tests.TestKit.*
import scala.io.Source

object InteropSyntaxTests:
  private type Parser = String => Either[SyntaxError, ?]

  def run(): Unit =
    println("Official syntax interoperability fixtures")
    verify("did", Did.parse)
    verify("handle", Handle.parse)
    verify("nsid", Nsid.parse)
    verify("aturi", AtUri.parse)
    verify("recordkey", RecordKey.parse)
    verify("tid", Tid.parse)

  private def verify(name: String, parser: Parser): Unit =
    test(s"accepts all valid $name fixtures") {
      val failures = fixtures(s"${name}_syntax_valid.txt").filter(value => parser(value).isLeft)
      equal(failures, Vector.empty)
    }
    test(s"rejects all invalid $name fixtures") {
      val failures = fixtures(s"${name}_syntax_invalid.txt").filter(value => parser(value).isRight)
      equal(failures, Vector.empty)
    }

  private def fixtures(name: String): Vector[String] =
    val stream = Option(getClass.getResourceAsStream(s"/interop/syntax/$name"))
      .getOrElse(throw IllegalStateException(s"missing fixture: $name"))
    val source = Source.fromInputStream(stream, "UTF-8")
    try
      source.getLines().filter(line => line.nonEmpty && line != "#" && !line.startsWith("# ")).toVector
    finally source.close()


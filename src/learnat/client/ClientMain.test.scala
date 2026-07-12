package learnat.tests

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import learnat.client.ClientMain
import learnat.tests.TestKit.*

object ClientMainTests:
  def run(): Unit =
    println("Client command line")

    cases("rejects invalid bounded list options")(
      "zero limit" -> Array("--limit", "0"),
      "excessive limit" -> Array("--limit", "101"),
      "non-numeric limit" -> Array("--limit", "many"),
      "missing cursor" -> Array("--cursor"),
      "unknown option" -> Array("--all")
    ) { options =>
      val result = run(
        Array("list", "http://localhost:2583", "did:web:localhost%3A2583", "com.example.note") ++
          options
      )
      equal(result.status, 2)
      assert(result.error.nonEmpty)
      equal(result.output, "")
    }

    test("requires credentials before starting a write request") {
      val result = run(
        Array("create", "http://localhost:2583", "alice.test", "com.example.note", "record.json")
      )
      equal(result.status, 2)
      assert(result.error.contains("LEARN_AT_PASSWORD is required"), result.error)
    }

    test("prints complete usage for an unknown command") {
      val result = run(Array("unknown"))
      equal(result.status, 2)
      assert(result.error.contains("client create"), result.error)
      assert(result.error.contains("client verify"), result.error)
    }

  final private case class Result(status: Int, output: String, error: String)

  private def run(arguments: Array[String]): Result =
    val output = ByteArrayOutputStream()
    val error = ByteArrayOutputStream()
    val status = ClientMain.run(arguments, Map.empty, PrintStream(output), PrintStream(error))
    Result(
      status,
      output.toString(StandardCharsets.UTF_8).trim,
      error.toString(StandardCharsets.UTF_8).trim
    )

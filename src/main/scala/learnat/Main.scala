package learnat

object Main:
  def main(args: Array[String]): Unit =
    val command = args.headOption.getOrElse("help")
    command match
      case "help" => printHelp()
      case "pds" => learnat.pds.LocalPdsMain.main(args.drop(1))
      case "client" => learnat.client.ClientMain.main(args.drop(1))
      case other =>
        Console.err.println(s"unknown command: $other")
        printHelp()
        sys.exit(2)

  private def printHelp(): Unit =
    println(
      """learn-at — understand AT Protocol by implementing it in Scala 3
        |
        |usage:
        |  learn-at help
        |  learn-at pds [port]
        |  learn-at client <get|list|post|export> ...
        |
        |Start the hands-on guide at docs/00-learning-path.md.
        |""".stripMargin
    )

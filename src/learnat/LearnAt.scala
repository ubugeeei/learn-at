package learnat

/** The single, user-facing entry point for the learning project. */
object LearnAt:
  def main(args: Array[String]): Unit =
    val command = args.headOption.getOrElse("help")
    command match
      case "help"   => printHelp()
      case "pds"    => learnat.pds.LocalPdsMain.main(args.drop(1))
      case "client" => learnat.client.ClientMain.main(args.drop(1))
      case other    =>
        Console.err.println(s"unknown command: $other")
        printHelp()
        sys.exit(2)

  private def printHelp(): Unit =
    println("""learn-at — understand AT Protocol by implementing it in Scala 3
        |
        |usage:
        |  sbt run
        |  sbt "run pds [port]"
        |  sbt "run client <get|list|post|export> ..."
        |
        |commands:
        |  help                 show this map
        |  pds [port]           start the local teaching PDS
        |  client ...           call a PDS from the teaching client
        |
        |Read README.md first, then continue at docs/00-learning-path.md.
        |""".stripMargin)

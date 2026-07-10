package learnat

object Main:
  def main(args: Array[String]): Unit =
    val command = args.headOption.getOrElse("help")
    command match
      case "help" => printHelp()
      case "pds" => learnat.pds.LocalPdsMain.main(args.drop(1))
      case other =>
        Console.err.println(s"unknown command: $other")
        printHelp()
        sys.exit(2)

  private def printHelp(): Unit =
    println(
      """learn-at — AT ProtocolをScala 3で理解する参照実装
        |
        |usage:
        |  learn-at help
        |  learn-at pds [port]
        |
        |ハンズオンは docs/00-learning-path.md から始めてください。
        |""".stripMargin
    )

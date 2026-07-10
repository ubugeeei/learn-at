package learnat.tests

object AllTests:
  def main(args: Array[String]): Unit =
    val _ = args
    JsonTests.run()
    SyntaxTests.run()
    InteropSyntaxTests.run()
    XrpcTests.run()
    println(s"${TestKit.count} tests passed")

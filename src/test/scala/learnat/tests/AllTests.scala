package learnat.tests

object AllTests:
  def main(args: Array[String]): Unit =
    val _ = args
    JsonTests.run()
    println(s"${TestKit.count} tests passed")

package learnat.tests

object TestKit:
  private var executed = 0

  def test(name: String)(body: => Unit): Unit =
    try
      body
      executed += 1
      println(s"  ok - $name")
    catch
      case error: Throwable =>
        Console.err.println(s"  not ok - $name")
        throw error

  def equal[A](actual: A, expected: A): Unit =
    assert(actual == expected, s"expected: $expected\nactual:   $actual")

  def isLeft[A, B](value: Either[A, B]): Unit =
    assert(value.isLeft, s"expected Left, got $value")

  def count: Int = executed


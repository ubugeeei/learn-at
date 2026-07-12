package learnat.tests

object TestKit:
  private var executed = 0

  /**
   * Declares one observable behavior. A test name should describe a contract, not the
   * implementation method that happens to enforce it.
   */
  def test(name: String)(body: => Unit): Unit =
    try
      body
      executed += 1
      println(s"  ok - $name")
    catch
      case error: Throwable =>
        Console.err.println(s"  not ok - $name")
        throw error

  /**
   * Expands named data rows into independent tests.
   *
   * Each row is reported separately, so adding protocol fixtures stays declarative without
   * collapsing failures into one opaque loop.
   */
  def cases[A](behavior: String)(rows: (String, A)*)(assertion: A => Unit): Unit = rows
    .foreach { case (name, value) => test(s"$behavior [$name]")(assertion(value)) }

  /** Compares a complete typed result and prints both values on failure. */
  def equal[A](actual: A, expected: A): Unit =
    assert(actual == expected, s"expected: $expected\nactual:   $actual")

  /** Requires a validation or decoding operation to reject its input. */
  def isLeft[A, B](value: Either[A, B]): Unit = assert(value.isLeft, s"expected Left, got $value")

  /** Number of independently reported contracts executed by the suite. */
  def count: Int = executed

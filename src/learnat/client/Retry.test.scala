package learnat.client

import java.time.Duration
import learnat.tests.TestKit.*

object RetryTests:
  def run(): Unit =
    println("Bounded client retry")

    cases("rejects invalid retry policies")(
      "no attempts" -> RetryPolicy.create(maxAttempts = 0),
      "zero delay" -> RetryPolicy.create(initialDelay = Duration.ZERO),
      "inverted delays" ->
        RetryPolicy.create(initialDelay = Duration.ofSeconds(2), maxDelay = Duration.ofSeconds(1)),
      "excess jitter" -> RetryPolicy.create(jitterRatio = 1.1)
    )(isLeft)

    test("retries retryable errors with capped exponential delays") {
      val policy = RetryPolicy.create(
        maxAttempts = 5,
        initialDelay = Duration.ofMillis(100),
        maxDelay = Duration.ofMillis(250),
        jitterRatio = 0.0
      ).toOption.get
      var attempts = 0
      var delays = Vector.empty[Duration]
      val executor = RetryExecutor.deterministic(
        policy,
        delay =>
          delays :+= delay
          Right(())
        ,
        () => 0.5
      )
      val result = executor.run {
        attempts += 1
        if attempts < 4 then Left(retryable(503)) else Right("ok")
      }
      equal(result, Right("ok"))
      equal(attempts, 4)
      equal(delays, Vector(Duration.ofMillis(100), Duration.ofMillis(200), Duration.ofMillis(250)))
    }

    test("stops immediately for a non-retryable error") {
      val policy = RetryPolicy.create().toOption.get
      var attempts = 0
      var slept = false
      val executor = RetryExecutor.deterministic(
        policy,
        _ =>
          slept = true
          Right(())
        ,
        () => 0.5
      )
      val result = executor.run[String] {
        attempts += 1
        Left(ClientError("unauthorized", status = Some(401)))
      }
      isLeft(result)
      equal(attempts, 1)
      equal(slept, false)
    }

    test("returns the last error after bounded exhaustion") {
      val policy = RetryPolicy.create(maxAttempts = 3, jitterRatio = 0.0).toOption.get
      var attempts = 0
      val executor = RetryExecutor.deterministic(policy, _ => Right(()), () => 0.5)
      val result = executor.run[String] {
        attempts += 1
        Left(retryable(429))
      }
      equal(attempts, 3)
      equal(result.left.toOption.flatMap(_.status), Some(429))
    }

    test("stops when waiting is interrupted or cancelled") {
      val policy = RetryPolicy.create().toOption.get
      var attempts = 0
      val cancelled = ClientError("cancelled", kind = ClientErrorKind.Transport)
      val executor = RetryExecutor.deterministic(policy, _ => Left(cancelled), () => 0.5)
      val result = executor.run[String] {
        attempts += 1
        Left(retryable(503))
      }
      equal(result, Left(cancelled))
      equal(attempts, 1)
    }

  private def retryable(status: Int): ClientError = ClientError(
    s"HTTP $status",
    kind = ClientErrorKind.Remote,
    status = Some(status),
    retryable = true
  )

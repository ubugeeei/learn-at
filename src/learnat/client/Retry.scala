package learnat.client

import java.time.Duration
import java.util.concurrent.ThreadLocalRandom

/** Bounded retry parameters for idempotent client operations. */
final case class RetryPolicy private (
    maxAttempts: Int,
    initialDelay: Duration,
    maxDelay: Duration,
    jitterRatio: Double
)

object RetryPolicy:
  /**
   * Validates a retry policy before any request is attempted.
   *
   * `maxAttempts` includes the first call. Jitter is a ratio from 0.0 (none) through 1.0; delay is
   * always capped by `maxDelay` after jitter is applied.
   */
  def create(
      maxAttempts: Int = 4,
      initialDelay: Duration = Duration.ofMillis(200),
      maxDelay: Duration = Duration.ofSeconds(5),
      jitterRatio: Double = 0.2
  ): Either[ClientError, RetryPolicy] =
    if maxAttempts < 1 then Left(ClientError("retry maxAttempts must be positive"))
    else if initialDelay.isNegative || initialDelay.isZero then
      Left(ClientError("retry initialDelay must be positive"))
    else if maxDelay.compareTo(initialDelay) < 0 then
      Left(ClientError("retry maxDelay must be at least initialDelay"))
    else if !jitterRatio.isFinite || jitterRatio < 0.0 || jitterRatio > 1.0 then
      Left(ClientError("retry jitterRatio must be from 0.0 through 1.0"))
    else Right(new RetryPolicy(maxAttempts, initialDelay, maxDelay, jitterRatio))

/**
 * Executes only caller-designated idempotent operations with bounded exponential backoff.
 *
 * The executor stops on the first success, any non-retryable error, or exhaustion. It does not
 * inspect HTTP methods and must not wrap a create/write procedure unless the caller supplies an
 * application-level idempotency guarantee.
 */
final class RetryExecutor private (
    policy: RetryPolicy,
    sleep: Duration => Either[ClientError, Unit],
    random: () => Double
):
  /** Runs an operation with at most `policy.maxAttempts` total calls. */
  def run[A](operation: => Either[ClientError, A]): Either[ClientError, A] =
    def loop(attempt: Int): Either[ClientError, A] = operation match
      case success @ Right(_)                                                         => success
      case failure @ Left(error) if !error.retryable || attempt >= policy.maxAttempts => failure
      case Left(_) => sleep(delayBefore(attempt)).flatMap(_ => loop(attempt + 1))
    loop(1)

  private def delayBefore(completedAttempt: Int): Duration =
    val exponent = math.min(completedAttempt - 1, 30)
    val uncapped = BigInt(policy.initialDelay.toMillis) * (BigInt(1) << exponent)
    val base = uncapped.min(BigInt(policy.maxDelay.toMillis)).toLong
    val unit = math.max(0.0, math.min(1.0, random()))
    val multiplier = 1.0 - policy.jitterRatio + (2.0 * policy.jitterRatio * unit)
    Duration.ofMillis(math.min(policy.maxDelay.toMillis, math.max(1L, (base * multiplier).toLong)))

object RetryExecutor:
  /**
   * Creates the production executor using interrupt-preserving thread sleep and secure per-thread
   * jitter.
   */
  def apply(policy: RetryPolicy): RetryExecutor = new RetryExecutor(
    policy,
    duration =>
      try
        Thread.sleep(duration.toMillis)
        Right(())
      catch
        case _: InterruptedException =>
          Thread.currentThread().interrupt()
          Left(ClientError(
            "retry wait was interrupted",
            kind = ClientErrorKind.Transport,
            retryable = false
          )),
    () => ThreadLocalRandom.current().nextDouble()
  )

  private[client] def deterministic(
      policy: RetryPolicy,
      sleep: Duration => Either[ClientError, Unit],
      random: () => Double
  ): RetryExecutor = new RetryExecutor(policy, sleep, random)

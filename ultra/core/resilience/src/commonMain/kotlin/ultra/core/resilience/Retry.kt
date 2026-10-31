package ultra.core.resilience

import kotlinx.coroutines.delay
import kotlin.random.Random
import kotlin.time.Duration

public class RetryPolicy(
    /** Total tries including the first one; 1 means "never retry". */
    public val maxAttempts: Int = 3,
    public val backoff: BackoffPolicy = BackoffPolicy(),
    /** Whether a failure is worth another try. An open circuit never is: retrying only burns the wait. */
    public val shouldRetry: (Throwable) -> Boolean = { it !is CircuitOpenException },
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1: $maxAttempts" }
    }
}

public class RetryEvent(
    /** The attempt that just failed (1-based). */
    public val attempt: Int,
    /** How long we are about to wait before the next attempt. */
    public val delay: Duration,
    public val cause: Throwable,
)

/**
 * Runs [block] up to `policy.maxAttempts` times, waiting per `policy.backoff` between attempts, and
 * rethrows the last failure when it gives up. Cancellation of the caller is never retried or swallowed.
 * The delay uses coroutine `delay`, so it is skipped by virtual time in `runTest`.
 */
public suspend fun <T> retrying(
    policy: RetryPolicy = RetryPolicy(),
    random: Random = Random.Default,
    onRetry: (RetryEvent) -> Unit = {},
    block: suspend (attempt: Int) -> T,
): T {
    var attempt = 1
    while (true) {
        try {
            return block(attempt)
        } catch (e: Throwable) {
            if (e.isOwnCancellation() || attempt >= policy.maxAttempts || !policy.shouldRetry(e)) throw e
            val wait = policy.backoff.delayFor(attempt, random)
            onRetry(RetryEvent(attempt, wait, e))
            delay(wait)
            attempt++
        }
    }
}

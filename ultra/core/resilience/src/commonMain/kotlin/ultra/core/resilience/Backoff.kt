package ultra.core.resilience

import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * How the exponential delay is randomised. Without jitter, every client that failed together retries
 * together and hammers a recovering server in waves.
 */
public enum class Jitter {
    /** Exactly the exponential delay. Deterministic; use only in tests or single-client cases. */
    None,

    /** Uniform in `[0, delay]`. Spreads retries the most; can retry almost immediately. */
    Full,

    /** Uniform in `[delay / 2, delay]`. Keeps a minimum wait while still spreading retries. */
    Equal,
}

/**
 * Exponential backoff: the delay before retry number `n` is `initialDelay * multiplier^(n-1)`, capped at
 * [maxDelay], then randomised by [jitter]. It is a pure value: randomness is passed to [delayFor], so
 * the same policy can be shared across callers and tested with a seeded [Random].
 */
public class BackoffPolicy(
    public val initialDelay: Duration = 500.milliseconds,
    public val maxDelay: Duration = 30.seconds,
    public val multiplier: Double = 2.0,
    public val jitter: Jitter = Jitter.Full,
) {
    init {
        require(initialDelay.isPositive() && initialDelay.isFinite()) { "initialDelay must be positive: $initialDelay" }
        require(maxDelay >= initialDelay && maxDelay.isFinite()) { "maxDelay must be at least initialDelay: $maxDelay" }
        require(multiplier >= 1.0 && multiplier.isFinite()) { "multiplier must be >= 1: $multiplier" }
    }

    /** The delay to wait after the [retry]-th failure (1 for the first retry). */
    public fun delayFor(
        retry: Int,
        random: Random = Random.Default,
    ): Duration {
        require(retry >= 1) { "retry is 1-based: $retry" }
        val capMs = maxDelay.toDouble(DurationUnit.MILLISECONDS)
        // pow overflows to +Infinity for large retry counts, which min() folds into the cap.
        val exponentialMs = initialDelay.toDouble(DurationUnit.MILLISECONDS) * multiplier.pow(retry - 1)
        val ceilingMs = minOf(exponentialMs, capMs)
        val delayMs =
            when (jitter) {
                Jitter.None -> ceilingMs
                Jitter.Full -> random.nextDouble() * ceilingMs
                Jitter.Equal -> ceilingMs / 2 + random.nextDouble() * ceilingMs / 2
            }
        return delayMs.milliseconds
    }
}

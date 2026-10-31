package ultra.core.resilience

import kotlin.concurrent.atomics.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** A point-in-time view of one dependency's health, safe to hand to UI. */
public data class HealthSnapshot(
    public val key: String,
    public val circuit: CircuitState,
    public val successes: Long,
    public val failures: Long,
    public val consecutiveFailures: Int,
    /** Exponentially weighted average latency of calls that got an answer; null before the first one. */
    public val averageLatency: Duration?,
    public val lastError: String?,
    public val sinceLastSuccess: Duration?,
    public val sinceLastFailure: Duration?,
)

/**
 * Tracks health per key (an API instance id, say): one [CircuitBreaker] plus running statistics each.
 * Entries are created on first use. Everything is lock-free and thread-safe.
 *
 * A call rejected by the breaker does not touch the statistics, since the dependency was never asked.
 *
 * @param onCircuitStateChange called after each breaker transition, with the entry key.
 */
public class HealthRegistry(
    private val breakerConfig: CircuitBreakerConfig = CircuitBreakerConfig(),
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val onCircuitStateChange: (key: String, from: CircuitState, to: CircuitState) -> Unit = { _, _, _ -> },
) {
    private class Stats(
        val successes: Long = 0,
        val failures: Long = 0,
        val consecutiveFailures: Int = 0,
        val ewmaLatencyMs: Double? = null,
        val lastError: String? = null,
        val lastSuccess: TimeMark? = null,
        val lastFailure: TimeMark? = null,
    )

    private inner class Entry(
        val key: String,
    ) {
        val breaker = CircuitBreaker(key, breakerConfig, timeSource, onCircuitStateChange)
        val stats = AtomicReference(Stats())

        fun recordSuccess(latency: Duration) {
            val now = timeSource.markNow()
            update(stats) {
                val ms = latency.toDouble(DurationUnit.MILLISECONDS)
                Stats(
                    successes = it.successes + 1,
                    failures = it.failures,
                    consecutiveFailures = 0,
                    ewmaLatencyMs = it.ewmaLatencyMs?.let { prev -> prev + LATENCY_ALPHA * (ms - prev) } ?: ms,
                    lastError = it.lastError,
                    lastSuccess = now,
                    lastFailure = it.lastFailure,
                )
            }
        }

        fun recordFailure(error: Throwable?) {
            val now = timeSource.markNow()
            val description = error?.let { "${it::class.simpleName}: ${it.message}" }
            update(stats) {
                Stats(
                    successes = it.successes,
                    failures = it.failures + 1,
                    consecutiveFailures = it.consecutiveFailures + 1,
                    ewmaLatencyMs = it.ewmaLatencyMs,
                    lastError = description ?: it.lastError,
                    lastSuccess = it.lastSuccess,
                    lastFailure = now,
                )
            }
        }

        fun snapshot(): HealthSnapshot {
            val s = stats.load()
            return HealthSnapshot(
                key = key,
                circuit = breaker.state,
                successes = s.successes,
                failures = s.failures,
                consecutiveFailures = s.consecutiveFailures,
                averageLatency = s.ewmaLatencyMs?.milliseconds,
                lastError = s.lastError,
                sinceLastSuccess = s.lastSuccess?.elapsedNow(),
                sinceLastFailure = s.lastFailure?.elapsedNow(),
            )
        }
    }

    private val entries = AtomicReference<Map<String, Entry>>(emptyMap())

    private fun entry(key: String): Entry {
        entries.load()[key]?.let { return it }
        val created = Entry(key)
        while (true) {
            val map = entries.load()
            map[key]?.let { return it } // lost the race: use the winner's entry
            if (entries.compareAndSet(map, map + (key to created))) return created
        }
    }

    /** The breaker for [key], for callers that need [CircuitBreaker.tryAcquire] directly. */
    public fun breaker(key: String): CircuitBreaker = entry(key).breaker

    /** False only when the circuit is open. Unknown keys have no history, so they are available. */
    public fun isAvailable(key: String): Boolean = entries.load()[key]?.breaker?.state != CircuitState.Open

    /** Runs [block] under the key's breaker and records its latency and outcome. */
    public suspend fun <T> execute(
        key: String,
        block: suspend () -> T,
    ): T {
        val entry = entry(key)
        val permit = entry.breaker.tryAcquire() ?: throw CircuitOpenException(key, entry.breaker.retryAfter())
        val start = timeSource.markNow()
        return observeOutcome(
            isFailure = breakerConfig.isFailure,
            onOutcome = { outcome, error ->
                when (outcome) {
                    CallOutcome.Success -> {
                        entry.recordSuccess(start.elapsedNow())
                        permit.recordSuccess()
                    }
                    CallOutcome.Failure -> {
                        entry.recordFailure(error)
                        permit.recordFailure()
                    }
                    CallOutcome.Ignored -> permit.release()
                }
            },
            block = block,
        )
    }

    public fun snapshot(key: String): HealthSnapshot? = entries.load()[key]?.snapshot()

    public fun snapshots(): List<HealthSnapshot> = entries.load().values.map { it.snapshot() }.sortedBy { it.key }

    /** Closes the key's breaker now. Statistics are kept. */
    public fun reset(key: String) {
        entries.load()[key]?.breaker?.reset()
    }

    /** Drops everything known about [key], for example when an instance is removed from the registry. */
    public fun forget(key: String) {
        while (true) {
            val map = entries.load()
            if (key !in map) return
            if (entries.compareAndSet(map, map - key)) return
        }
    }

}

/** Weight of the newest sample in the latency average; 0.2 means it settles over roughly five calls. */
private const val LATENCY_ALPHA = 0.2

private inline fun <T> update(
    ref: AtomicReference<T>,
    transform: (T) -> T,
) {
    while (true) {
        val old = ref.load()
        if (ref.compareAndSet(old, transform(old))) return
    }
}

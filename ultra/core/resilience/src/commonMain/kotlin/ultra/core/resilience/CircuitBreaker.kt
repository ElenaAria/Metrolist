package ultra.core.resilience

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

public enum class CircuitState {
    /** Healthy: calls flow, consecutive failures are counted. */
    Closed,

    /** Tripped: calls are rejected until the open duration passes. */
    Open,

    /** Probing: a limited number of calls are let through to test recovery. */
    HalfOpen,
}

public class CircuitBreakerConfig(
    /** Consecutive failures that trip the breaker. */
    public val failureThreshold: Int = 5,
    /** How long the breaker stays open before probing. */
    public val openDuration: Duration = 30.seconds,
    /** Probe calls admitted while half-open. All must succeed to close; any failure re-opens. */
    public val halfOpenProbes: Int = 1,
    /** Which exceptions mean "the dependency is unhealthy". Others (say HTTP 404) count as a working call. */
    public val isFailure: (Throwable) -> Boolean = { true },
) {
    init {
        require(failureThreshold >= 1) { "failureThreshold must be at least 1: $failureThreshold" }
        require(openDuration.isPositive()) { "openDuration must be positive: $openDuration" }
        require(halfOpenProbes >= 1) { "halfOpenProbes must be at least 1: $halfOpenProbes" }
    }
}

/**
 * A consecutive-failure circuit breaker. It is lock-free: the state is an immutable snapshot swapped
 * with compare-and-set, so it is safe from any thread without suspending.
 *
 * Every state change bumps an *epoch*, and each [Permit] remembers the epoch it was granted in. A call
 * that was admitted while the breaker was closed and finishes after it has tripped and recovered is
 * therefore ignored instead of wrongly closing (or re-opening) a later state.
 *
 * @param onStateChange called after each transition, on the thread that caused it. Keep it cheap.
 */
public class CircuitBreaker(
    public val name: String,
    public val config: CircuitBreakerConfig = CircuitBreakerConfig(),
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val onStateChange: (name: String, from: CircuitState, to: CircuitState) -> Unit = { _, _, _ -> },
) {
    private sealed class Snapshot(
        val epoch: Long,
    ) {
        class Closed(
            epoch: Long,
            val consecutiveFailures: Int,
        ) : Snapshot(epoch)

        class Open(
            epoch: Long,
            val openedAt: TimeMark,
        ) : Snapshot(epoch)

        class HalfOpen(
            epoch: Long,
            val admitted: Int,
            val succeeded: Int,
        ) : Snapshot(epoch)
    }

    private val current = AtomicReference<Snapshot>(Snapshot.Closed(epoch = 0, consecutiveFailures = 0))

    /** The state as of now: an open breaker whose wait has elapsed already reads as [CircuitState.HalfOpen]. */
    public val state: CircuitState
        get() =
            when (val snapshot = current.load()) {
                is Snapshot.Closed -> CircuitState.Closed
                is Snapshot.HalfOpen -> CircuitState.HalfOpen
                is Snapshot.Open ->
                    if (snapshot.openedAt.elapsedNow() >= config.openDuration) CircuitState.HalfOpen else CircuitState.Open
            }

    public val consecutiveFailures: Int
        get() = (current.load() as? Snapshot.Closed)?.consecutiveFailures ?: 0

    /** Time until the next probe is allowed; zero unless the breaker is open. */
    public fun retryAfter(): Duration =
        when (val snapshot = current.load()) {
            is Snapshot.Open -> (config.openDuration - snapshot.openedAt.elapsedNow()).coerceAtLeast(Duration.ZERO)
            else -> Duration.ZERO
        }

    /**
     * Asks to make a call. Returns null when the breaker rejects it. A returned permit must be completed
     * exactly once, through [Permit.recordSuccess], [Permit.recordFailure] or [Permit.release]; later
     * completions are ignored.
     */
    public fun tryAcquire(): Permit? {
        while (true) {
            when (val snapshot = current.load()) {
                is Snapshot.Closed -> return Permit(this, snapshot.epoch)

                is Snapshot.Open -> {
                    if (snapshot.openedAt.elapsedNow() < config.openDuration) return null
                    val next = Snapshot.HalfOpen(snapshot.epoch + 1, admitted = 1, succeeded = 0)
                    if (current.compareAndSet(snapshot, next)) {
                        onStateChange(name, CircuitState.Open, CircuitState.HalfOpen)
                        return Permit(this, next.epoch)
                    }
                }

                is Snapshot.HalfOpen -> {
                    if (snapshot.admitted >= config.halfOpenProbes) return null
                    val next = Snapshot.HalfOpen(snapshot.epoch, snapshot.admitted + 1, snapshot.succeeded)
                    if (current.compareAndSet(snapshot, next)) return Permit(this, snapshot.epoch)
                }
            }
        }
    }

    /**
     * Runs [block] through the breaker. Throws [CircuitOpenException] without running it when rejected.
     * An inner timeout counts as a failure; cancellation of the caller does not (its permit is released).
     */
    public suspend fun <T> execute(block: suspend () -> T): T {
        val permit = tryAcquire() ?: throw CircuitOpenException(name, retryAfter())
        return observeOutcome(
            isFailure = config.isFailure,
            onOutcome = { outcome, _ ->
                when (outcome) {
                    CallOutcome.Success -> permit.recordSuccess()
                    CallOutcome.Failure -> permit.recordFailure()
                    CallOutcome.Ignored -> permit.release()
                }
            },
            block = block,
        )
    }

    /** Forces the breaker closed, for example from a "retry this instance now" action. */
    public fun reset() {
        while (true) {
            val snapshot = current.load()
            if (snapshot is Snapshot.Closed && snapshot.consecutiveFailures == 0) return
            val next = Snapshot.Closed(snapshot.epoch + 1, consecutiveFailures = 0)
            if (current.compareAndSet(snapshot, next)) {
                if (snapshot !is Snapshot.Closed) onStateChange(name, snapshot.asState(), CircuitState.Closed)
                return
            }
        }
    }

    private fun Snapshot.asState(): CircuitState =
        when (this) {
            is Snapshot.Closed -> CircuitState.Closed
            is Snapshot.Open -> CircuitState.Open
            is Snapshot.HalfOpen -> CircuitState.HalfOpen
        }

    private fun complete(
        epoch: Long,
        outcome: CallOutcome,
    ) {
        while (true) {
            val snapshot = current.load()
            if (snapshot.epoch != epoch) return // granted in an earlier state; it no longer says anything

            val next: Snapshot =
                when (snapshot) {
                    is Snapshot.Open -> return // never hands out permits, so none can complete here

                    is Snapshot.Closed ->
                        when (outcome) {
                            CallOutcome.Success ->
                                if (snapshot.consecutiveFailures == 0) return else Snapshot.Closed(epoch, 0)
                            CallOutcome.Ignored -> return
                            CallOutcome.Failure -> {
                                val failures = snapshot.consecutiveFailures + 1
                                if (failures >= config.failureThreshold) {
                                    Snapshot.Open(epoch + 1, timeSource.markNow())
                                } else {
                                    Snapshot.Closed(epoch, failures)
                                }
                            }
                        }

                    is Snapshot.HalfOpen ->
                        when (outcome) {
                            CallOutcome.Success -> {
                                val succeeded = snapshot.succeeded + 1
                                if (succeeded >= config.halfOpenProbes) {
                                    Snapshot.Closed(epoch + 1, 0)
                                } else {
                                    Snapshot.HalfOpen(epoch, snapshot.admitted, succeeded)
                                }
                            }
                            CallOutcome.Failure -> Snapshot.Open(epoch + 1, timeSource.markNow())
                            CallOutcome.Ignored -> Snapshot.HalfOpen(epoch, snapshot.admitted - 1, snapshot.succeeded)
                        }
                }

            if (current.compareAndSet(snapshot, next)) {
                val from = snapshot.asState()
                val to = next.asState()
                if (from != to) onStateChange(name, from, to)
                return
            }
        }
    }

    /** A granted call. Idempotent: only the first completion counts. */
    public class Permit internal constructor(
        private val breaker: CircuitBreaker,
        private val epoch: Long,
    ) {
        private val completed = AtomicInt(0)

        public fun recordSuccess(): Unit = finish(CallOutcome.Success)

        public fun recordFailure(): Unit = finish(CallOutcome.Failure)

        /** Gives the permit back without judging the dependency (the caller was cancelled, say). */
        public fun release(): Unit = finish(CallOutcome.Ignored)

        private fun finish(outcome: CallOutcome) {
            if (completed.compareAndSet(0, 1)) breaker.complete(epoch, outcome)
        }
    }
}

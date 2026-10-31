package ultra.core.resilience

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalAtomicApi::class)
class CircuitBreakerTest {
    private class Boom : RuntimeException("boom")

    private val time = TestTimeSource()
    private val transitions = mutableListOf<Pair<CircuitState, CircuitState>>()

    private fun breaker(
        failureThreshold: Int = 3,
        halfOpenProbes: Int = 1,
        isFailure: (Throwable) -> Boolean = { true },
    ) = CircuitBreaker(
        name = "piped-1",
        config = CircuitBreakerConfig(failureThreshold, openDuration = 30.seconds, halfOpenProbes = halfOpenProbes, isFailure = isFailure),
        timeSource = time,
        onStateChange = { _, from, to -> transitions += from to to },
    )

    private fun CircuitBreaker.fail(times: Int = 1) = repeat(times) { assertNotNull(tryAcquire()).recordFailure() }

    private fun CircuitBreaker.openIt() {
        fail(config.failureThreshold)
        assertEquals(CircuitState.Open, state)
    }

    private fun CircuitBreaker.advanceToHalfOpen() {
        time += config.openDuration
        assertEquals(CircuitState.HalfOpen, state)
    }

    @Test
    fun `it starts closed and opens after the threshold of consecutive failures`() {
        val breaker = breaker(failureThreshold = 3)

        breaker.fail(2)
        assertEquals(CircuitState.Closed, breaker.state)
        assertEquals(2, breaker.consecutiveFailures)

        breaker.fail()
        assertEquals(CircuitState.Open, breaker.state)
        assertEquals(listOf(CircuitState.Closed to CircuitState.Open), transitions)
    }

    @Test
    fun `a success resets the consecutive failure count`() {
        val breaker = breaker(failureThreshold = 3)

        breaker.fail(2)
        assertNotNull(breaker.tryAcquire()).recordSuccess()
        breaker.fail(2)

        assertEquals(CircuitState.Closed, breaker.state)
    }

    @Test
    fun `an open breaker rejects calls and says how long to wait`() =
        runTest {
            val breaker = breaker()
            breaker.openIt()
            time += 10.seconds

            assertNull(breaker.tryAcquire())
            val rejection = assertFailsWith<CircuitOpenException> { breaker.execute { "never runs" } }
            assertEquals(20.seconds, rejection.retryAfter)
            assertEquals("piped-1", rejection.name)
        }

    @Test
    fun `after the open duration exactly one probe is let through`() {
        val breaker = breaker()
        breaker.openIt()
        breaker.advanceToHalfOpen()

        assertNotNull(breaker.tryAcquire())
        assertNull(breaker.tryAcquire())
        assertEquals(CircuitState.HalfOpen, breaker.state)
    }

    @Test
    fun `a successful probe closes the breaker`() {
        val breaker = breaker()
        breaker.openIt()
        breaker.advanceToHalfOpen()

        assertNotNull(breaker.tryAcquire()).recordSuccess()

        assertEquals(CircuitState.Closed, breaker.state)
        assertEquals(
            listOf(
                CircuitState.Closed to CircuitState.Open,
                CircuitState.Open to CircuitState.HalfOpen,
                CircuitState.HalfOpen to CircuitState.Closed,
            ),
            transitions,
        )
    }

    @Test
    fun `a failed probe re-opens the breaker with a fresh wait`() {
        val breaker = breaker()
        breaker.openIt()
        breaker.advanceToHalfOpen()

        assertNotNull(breaker.tryAcquire()).recordFailure()

        assertEquals(CircuitState.Open, breaker.state)
        assertEquals(30.seconds, breaker.retryAfter())
    }

    @Test
    fun `with several probes all of them must succeed to close`() {
        val breaker = breaker(halfOpenProbes = 2)
        breaker.openIt()
        breaker.advanceToHalfOpen()

        val first = assertNotNull(breaker.tryAcquire())
        val second = assertNotNull(breaker.tryAcquire())
        assertNull(breaker.tryAcquire())

        first.recordSuccess()
        assertEquals(CircuitState.HalfOpen, breaker.state)
        second.recordSuccess()
        assertEquals(CircuitState.Closed, breaker.state)
    }

    @Test
    fun `releasing a probe frees its slot without judging the dependency`() {
        val breaker = breaker()
        breaker.openIt()
        breaker.advanceToHalfOpen()

        assertNotNull(breaker.tryAcquire()).release()

        assertEquals(CircuitState.HalfOpen, breaker.state)
        assertNotNull(breaker.tryAcquire())
    }

    @Test
    fun `a permit completes at most once`() {
        val breaker = breaker(failureThreshold = 2)

        val permit = assertNotNull(breaker.tryAcquire())
        permit.recordFailure()
        permit.recordFailure()
        permit.recordFailure()

        assertEquals(1, breaker.consecutiveFailures)
        assertEquals(CircuitState.Closed, breaker.state)
    }

    @Test
    fun `a call admitted before the breaker tripped cannot affect a later state`() {
        val breaker = breaker()
        val slowCall = assertNotNull(breaker.tryAcquire()) // admitted while closed, still running

        breaker.openIt()
        breaker.advanceToHalfOpen()
        val probe = assertNotNull(breaker.tryAcquire())

        slowCall.recordSuccess() // finishes late: must NOT close the half-open breaker
        assertEquals(CircuitState.HalfOpen, breaker.state)

        probe.recordFailure()
        assertEquals(CircuitState.Open, breaker.state)
    }

    @Test
    fun `a late failure from an earlier era does not re-open a recovered breaker`() {
        val breaker = breaker()
        val slowCall = assertNotNull(breaker.tryAcquire())

        breaker.openIt()
        breaker.advanceToHalfOpen()
        assertNotNull(breaker.tryAcquire()).recordSuccess()
        assertEquals(CircuitState.Closed, breaker.state)

        slowCall.recordFailure()

        assertEquals(CircuitState.Closed, breaker.state)
        assertEquals(0, breaker.consecutiveFailures)
    }

    @Test
    fun `exceptions that are not failures count as a working call`() =
        runTest {
            val breaker = breaker(failureThreshold = 2, isFailure = { it is Boom })

            breaker.fail()
            assertFailsWith<IllegalArgumentException> { breaker.execute { throw IllegalArgumentException("404") } }

            assertEquals(0, breaker.consecutiveFailures) // the 404 proved the server answers
        }

    @Test
    fun `rejections from other resilience primitives are not the dependency's fault`() =
        runTest {
            val breaker = breaker(failureThreshold = 2)

            repeat(5) {
                assertFailsWith<BulkheadFullException> { breaker.execute { throw BulkheadFullException("b", 1) } }
            }

            assertEquals(CircuitState.Closed, breaker.state)
            assertEquals(0, breaker.consecutiveFailures)
        }

    @Test
    fun `an inner timeout counts as a failure`() =
        runTest {
            val breaker = breaker(failureThreshold = 2)

            repeat(2) {
                assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> {
                    breaker.execute { withTimeout(50.milliseconds) { delay(1.seconds) } }
                }
            }

            assertEquals(CircuitState.Open, breaker.state)
        }

    @Test
    fun `cancelling the caller releases a probe instead of failing it`() =
        runTest {
            val breaker = breaker()
            breaker.openIt()
            breaker.advanceToHalfOpen()

            val job = launch(start = CoroutineStart.UNDISPATCHED) { breaker.execute { awaitCancellation() } }
            runCurrent()
            assertNull(breaker.tryAcquire()) // the probe slot is taken while the call runs

            job.cancelAndJoin()

            assertEquals(CircuitState.HalfOpen, breaker.state) // not re-opened by the cancellation
            assertNotNull(breaker.tryAcquire()) // and the slot is free again
        }

    @Test
    fun `reset closes an open breaker`() {
        val breaker = breaker()
        breaker.openIt()

        breaker.reset()

        assertEquals(CircuitState.Closed, breaker.state)
        assertNotNull(breaker.tryAcquire())
        assertEquals(CircuitState.Open to CircuitState.Closed, transitions.last())
    }

    @Test
    fun `concurrent failures trip the breaker exactly once`() =
        runTest {
            val opened = AtomicInt(0)
            val stress =
                CircuitBreaker(
                    name = "stress",
                    config = CircuitBreakerConfig(failureThreshold = 5, openDuration = 1.seconds),
                    timeSource = time, // frozen, so it can never leave Open
                    onStateChange = { _, _, to ->
                        if (to == CircuitState.Open) {
                            while (true) {
                                val seen = opened.load()
                                if (opened.compareAndSet(seen, seen + 1)) break
                            }
                        }
                    },
                )

            withContext(Dispatchers.Default) {
                repeat(8) {
                    launch {
                        repeat(1_000) { stress.tryAcquire()?.recordFailure() }
                    }
                }
            }

            assertEquals(CircuitState.Open, stress.state)
            assertEquals(1, opened.load())
        }
}

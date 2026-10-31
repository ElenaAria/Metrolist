package ultra.core.resilience

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class RetryTest {
    private class Boom(
        message: String = "boom",
    ) : RuntimeException(message)

    private val deterministic = BackoffPolicy(100.milliseconds, 10.seconds, 2.0, Jitter.None)

    @Test
    fun `a first-try success does not wait`() =
        runTest {
            val result = retrying(RetryPolicy(backoff = deterministic)) { "ok" }

            assertEquals("ok", result)
            assertEquals(0L, currentTime)
        }

    @Test
    fun `it retries with the configured backoff until the call succeeds`() =
        runTest {
            var calls = 0

            val result =
                retrying(RetryPolicy(maxAttempts = 5, backoff = deterministic)) { attempt ->
                    calls++
                    if (attempt < 3) throw Boom()
                    "done on $attempt"
                }

            assertEquals("done on 3", result)
            assertEquals(3, calls)
            assertEquals(300L, currentTime) // 100 ms + 200 ms of virtual waiting
        }

    @Test
    fun `it gives up after max attempts and rethrows the last failure`() =
        runTest {
            var calls = 0

            val failure =
                assertFailsWith<Boom> {
                    retrying(RetryPolicy(maxAttempts = 3, backoff = deterministic)) {
                        calls++
                        throw Boom("failure #$calls")
                    }
                }

            assertEquals(3, calls)
            assertEquals("failure #3", failure.message)
            assertEquals(300L, currentTime) // no wait after the final attempt
        }

    @Test
    fun `shouldRetry can veto a retry`() =
        runTest {
            var calls = 0

            assertFailsWith<IllegalStateException> {
                retrying(RetryPolicy(maxAttempts = 5, backoff = deterministic, shouldRetry = { it is Boom })) {
                    calls++
                    throw IllegalStateException("fatal")
                }
            }

            assertEquals(1, calls)
        }

    @Test
    fun `an open circuit is not retried by default`() =
        runTest {
            var calls = 0

            assertFailsWith<CircuitOpenException> {
                retrying(RetryPolicy(maxAttempts = 5, backoff = deterministic)) {
                    calls++
                    throw CircuitOpenException("piped", 5.seconds)
                }
            }

            assertEquals(1, calls)
        }

    @Test
    fun `onRetry reports each failed attempt with the delay that follows`() =
        runTest {
            val events = mutableListOf<RetryEvent>()

            retrying(RetryPolicy(maxAttempts = 3, backoff = deterministic), onRetry = { events += it }) { attempt ->
                if (attempt < 3) throw Boom("attempt $attempt")
            }

            assertEquals(listOf(1, 2), events.map { it.attempt })
            assertEquals(listOf(100.milliseconds, 200.milliseconds), events.map { it.delay })
            assertEquals(listOf("attempt 1", "attempt 2"), events.map { it.cause.message })
        }

    @Test
    fun `an inner timeout is an ordinary failure and gets retried`() =
        runTest {
            var calls = 0

            val result =
                retrying(RetryPolicy(maxAttempts = 3, backoff = deterministic)) {
                    calls++
                    withTimeout(50.milliseconds) {
                        if (calls == 1) delay(1.seconds) // too slow once
                        "recovered"
                    }
                }

            assertEquals("recovered", result)
            assertEquals(2, calls)
        }

    @Test
    fun `cancelling the caller during backoff stops retrying`() =
        runTest {
            var calls = 0
            val job =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    retrying(RetryPolicy(maxAttempts = 10, backoff = deterministic)) {
                        calls++
                        throw Boom()
                    }
                }
            runCurrent()
            assertEquals(1, calls) // failed once, now sleeping in backoff

            job.cancelAndJoin()

            assertTrue(job.isCancelled)
            assertEquals(1, calls)
        }

    @Test
    fun `an outer timeout cancels instead of being retried`() =
        runTest {
            var calls = 0

            val outcome =
                async {
                    runCatching {
                        withTimeout(250.milliseconds) {
                            retrying(RetryPolicy(maxAttempts = 100, backoff = deterministic)) {
                                calls++
                                throw Boom()
                            }
                        }
                    }
                }

            val failure = outcome.await().exceptionOrNull()
            assertTrue(failure is kotlinx.coroutines.TimeoutCancellationException, "was $failure")
            assertEquals(2, calls) // attempts at 0 and 100 ms; the timeout (250 ms) fires inside the 100..300 ms wait
        }
}

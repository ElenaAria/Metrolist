package ultra.core.resilience

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class BulkheadTest {
    @Test
    fun `calls beyond the limit fail fast when there is no wait`() =
        runTest {
            val bulkhead = Bulkhead("homelab", maxConcurrent = 2)
            val release = CompletableDeferred<Unit>()
            repeat(2) { launch(start = CoroutineStart.UNDISPATCHED) { bulkhead.execute { release.await() } } }
            assertEquals(0, bulkhead.availablePermits)

            val rejection = assertFailsWith<BulkheadFullException> { bulkhead.execute { "never runs" } }

            assertEquals(2, rejection.maxConcurrent)
            release.complete(Unit)
            runCurrent()
            assertEquals(2, bulkhead.availablePermits)
        }

    @Test
    fun `the slot is returned when the call throws`() =
        runTest {
            val bulkhead = Bulkhead("homelab", maxConcurrent = 1)

            assertFailsWith<IllegalStateException> { bulkhead.execute { error("boom") } }

            assertEquals(1, bulkhead.availablePermits)
            assertEquals("ok", bulkhead.execute { "ok" })
        }

    @Test
    fun `a waiting call gets in when a slot frees up within the wait`() =
        runTest {
            val bulkhead = Bulkhead("homelab", maxConcurrent = 1, maxWait = 5.seconds)
            launch(start = CoroutineStart.UNDISPATCHED) { bulkhead.execute { delay(2.seconds) } }

            val second = async { bulkhead.execute { "second ran at ${currentTime}ms" } }

            assertEquals("second ran at 2000ms", second.await())
        }

    @Test
    fun `a waiting call gives up when the wait runs out`() =
        runTest {
            val bulkhead = Bulkhead("homelab", maxConcurrent = 1, maxWait = 1.seconds)
            val release = CompletableDeferred<Unit>()
            launch(start = CoroutineStart.UNDISPATCHED) { bulkhead.execute { release.await() } }

            assertFailsWith<BulkheadFullException> { bulkhead.execute { "never runs" } }

            assertEquals(1000L, currentTime)
            release.complete(Unit)
        }

    @Test
    fun `a cancelled waiter does not leak a slot`() =
        runTest {
            val bulkhead = Bulkhead("homelab", maxConcurrent = 1, maxWait = 10.seconds)
            val release = CompletableDeferred<Unit>()
            launch(start = CoroutineStart.UNDISPATCHED) { bulkhead.execute { release.await() } }
            val waiter = launch(start = CoroutineStart.UNDISPATCHED) { bulkhead.execute { awaitCancellation() } }
            runCurrent()

            waiter.cancelAndJoin()
            release.complete(Unit)
            runCurrent()

            assertEquals(1, bulkhead.availablePermits)
        }
}

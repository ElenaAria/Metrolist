package ultra.core.resilience

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class HealthRegistryTest {
    private class Boom : IllegalStateException("boom")

    private val time = TestTimeSource()
    private val changes = mutableListOf<Triple<String, CircuitState, CircuitState>>()

    private fun registry(
        failureThreshold: Int = 2,
        isFailure: (Throwable) -> Boolean = { true },
    ) = HealthRegistry(
        breakerConfig = CircuitBreakerConfig(failureThreshold, openDuration = 30.seconds, isFailure = isFailure),
        timeSource = time,
        onCircuitStateChange = { key, from, to -> changes += Triple(key, from, to) },
    )

    @Test
    fun `an unknown key is available and has no snapshot`() {
        val registry = registry()

        assertTrue(registry.isAvailable("piped-1"))
        assertNull(registry.snapshot("piped-1"))
        assertTrue(registry.snapshots().isEmpty())
    }

    @Test
    fun `successful calls are counted and their latency is averaged`() =
        runTest {
            val registry = registry()

            registry.execute("piped-1") { time += 120.milliseconds }
            assertEquals(120.milliseconds, registry.snapshot("piped-1")?.averageLatency)

            registry.execute("piped-1") { time += 220.milliseconds }
            val snapshot = assertNotNull(registry.snapshot("piped-1"))

            assertEquals(2, snapshot.successes)
            assertEquals(0, snapshot.failures)
            // EWMA with alpha 0.2: 120 + 0.2 * (220 - 120) = 140 ms
            assertEquals(140.0, snapshot.averageLatency!!.inWholeMicroseconds / 1000.0, absoluteTolerance = 0.001)
        }

    @Test
    fun `failures are recorded with the last error and trip the circuit`() =
        runTest {
            val registry = registry(failureThreshold = 2)

            repeat(2) { assertFailsWith<Boom> { registry.execute("piped-1") { throw Boom() } } }

            val snapshot = assertNotNull(registry.snapshot("piped-1"))
            assertEquals(2, snapshot.failures)
            assertEquals(2, snapshot.consecutiveFailures)
            assertEquals(CircuitState.Open, snapshot.circuit)
            assertEquals("Boom: boom", snapshot.lastError)
            assertFalse(registry.isAvailable("piped-1"))
            assertEquals(listOf(Triple("piped-1", CircuitState.Closed, CircuitState.Open)), changes)
        }

    @Test
    fun `a rejected call is not counted because the dependency was never asked`() =
        runTest {
            val registry = registry(failureThreshold = 1)
            assertFailsWith<Boom> { registry.execute("piped-1") { throw Boom() } }

            val rejection = assertFailsWith<CircuitOpenException> { registry.execute("piped-1") { "never runs" } }

            assertEquals("piped-1", rejection.name)
            assertEquals(1, registry.snapshot("piped-1")?.failures)
        }

    @Test
    fun `keys are independent`() =
        runTest {
            val registry = registry(failureThreshold = 1)

            assertFailsWith<Boom> { registry.execute("bad") { throw Boom() } }
            registry.execute("good") { "fine" }

            assertFalse(registry.isAvailable("bad"))
            assertTrue(registry.isAvailable("good"))
            assertEquals(listOf("bad", "good"), registry.snapshots().map { it.key })
        }

    @Test
    fun `errors the policy does not treat as failures leave the instance healthy`() =
        runTest {
            val registry = registry(failureThreshold = 1, isFailure = { it is Boom })

            repeat(3) { assertFailsWith<IllegalArgumentException> { registry.execute("piped-1") { throw IllegalArgumentException("404") } } }

            val snapshot = assertNotNull(registry.snapshot("piped-1"))
            assertEquals(CircuitState.Closed, snapshot.circuit)
            assertEquals(0, snapshot.failures)
            assertEquals(3, snapshot.successes)
        }

    @Test
    fun `reset closes the circuit but keeps the statistics`() =
        runTest {
            val registry = registry(failureThreshold = 1)
            assertFailsWith<Boom> { registry.execute("piped-1") { throw Boom() } }

            registry.reset("piped-1")

            assertTrue(registry.isAvailable("piped-1"))
            assertEquals(1, registry.snapshot("piped-1")?.failures)
        }

    @Test
    fun `forget drops everything known about a key`() =
        runTest {
            val registry = registry(failureThreshold = 1)
            assertFailsWith<Boom> { registry.execute("piped-1") { throw Boom() } }

            registry.forget("piped-1")

            assertNull(registry.snapshot("piped-1"))
            assertTrue(registry.isAvailable("piped-1"))
        }

    @Test
    fun `time since the last success and failure is reported`() =
        runTest {
            val registry = registry(failureThreshold = 10)
            registry.execute("piped-1") { }
            time += 5.seconds
            assertFailsWith<Boom> { registry.execute("piped-1") { throw Boom() } }
            time += 2.seconds

            val snapshot = assertNotNull(registry.snapshot("piped-1"))

            assertEquals(7.seconds, snapshot.sinceLastSuccess)
            assertEquals(2.seconds, snapshot.sinceLastFailure)
        }
}

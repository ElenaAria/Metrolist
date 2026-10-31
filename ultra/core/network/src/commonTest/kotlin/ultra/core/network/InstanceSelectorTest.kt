package ultra.core.network

import kotlinx.coroutines.test.runTest
import ultra.core.resilience.CircuitBreakerConfig
import ultra.core.resilience.HealthRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class InstanceSelectorTest {
    private val time = TestTimeSource()
    private val health = HealthRegistry(CircuitBreakerConfig(failureThreshold = 3, openDuration = 30.seconds), time)

    private fun piped(
        name: String,
        priority: Int = 0,
        enabled: Boolean = true,
    ) = ApiInstance.create(InstanceKind.Piped, "https://$name.example", priority, enabled)

    private fun selector(vararg instances: ApiInstance) = InstanceSelector(InstanceRegistry(instances.toList()), health)

    private fun InstanceSelector.names(kind: InstanceKind = InstanceKind.Piped) = candidates(kind).map { it.baseUrl.removePrefix("https://").removeSuffix(".example") }

    private suspend fun succeedAfter(
        instance: ApiInstance,
        latency: Duration,
    ) = health.execute(instance.id) { time += latency }

    private suspend fun fail(instance: ApiInstance) = runCatching { health.execute(instance.id) { error("boom") } }

    @Test
    fun `disabled instances other kinds and open circuits are not candidates`() =
        runTest {
            val off = piped("off", enabled = false)
            val dead = piped("dead")
            val live = piped("live")
            val other = ApiInstance.create(InstanceKind.Invidious, "https://inv.example")
            repeat(3) { fail(dead) } // trips its circuit

            val selector = selector(off, dead, live, other)

            assertEquals(listOf("live"), selector.names())
            assertEquals(2, selector.enabledCount(InstanceKind.Piped)) // off is excluded, dead still counts as configured
        }

    @Test
    fun `an explicit priority beats everything else`() =
        runTest {
            val slowButPreferred = piped("slow", priority = 10)
            val fast = piped("fast", priority = 0)
            succeedAfter(slowButPreferred, 900.milliseconds)
            succeedAfter(fast, 10.milliseconds)

            assertEquals(listOf("slow", "fast"), selector(fast, slowButPreferred).names())
        }

    @Test
    fun `within a priority the faster measured instance comes first`() =
        runTest {
            val a = piped("a")
            val b = piped("b")
            val c = piped("c")
            succeedAfter(a, 300.milliseconds)
            succeedAfter(b, 50.milliseconds)
            succeedAfter(c, 120.milliseconds)

            assertEquals(listOf("b", "c", "a"), selector(a, b, c).names())
        }

    @Test
    fun `instances not measured yet come after measured ones`() =
        runTest {
            val unknown = piped("unknown")
            val measured = piped("measured")
            succeedAfter(measured, 400.milliseconds)

            assertEquals(listOf("measured", "unknown"), selector(unknown, measured).names())
        }

    @Test
    fun `an instance that just failed is demoted below healthy ones of the same priority`() =
        runTest {
            val flaky = piped("flaky")
            val steady = piped("steady")
            succeedAfter(flaky, 10.milliseconds) // the fastest...
            succeedAfter(steady, 500.milliseconds)
            fail(flaky) // ...but it failed last

            assertEquals(listOf("steady", "flaky"), selector(flaky, steady).names())
        }

    @Test
    fun `a success clears the demotion`() =
        runTest {
            val flaky = piped("flaky")
            val steady = piped("steady")
            succeedAfter(steady, 500.milliseconds)
            fail(flaky)
            succeedAfter(flaky, 10.milliseconds)

            assertEquals(listOf("flaky", "steady"), selector(steady, flaky).names())
        }

    @Test
    fun `ties keep registration order`() {
        assertEquals(listOf("x", "y", "z"), selector(piped("x"), piped("y"), piped("z")).names())
    }
}

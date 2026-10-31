package ultra.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ultra.core.resilience.BackoffPolicy
import ultra.core.resilience.CircuitBreakerConfig
import ultra.core.resilience.CircuitState
import ultra.core.resilience.HealthRegistry
import ultra.core.resilience.Jitter
import ultra.core.resilience.RetryPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

@OptIn(ExperimentalCoroutinesApi::class)
class FailoverExecutorTest {
    private class Boom(
        message: String = "boom",
    ) : RuntimeException(message)

    private val time = TestTimeSource()
    private val a = ApiInstance.create(InstanceKind.Piped, "https://a.example")
    private val b = ApiInstance.create(InstanceKind.Piped, "https://b.example")
    private val c = ApiInstance.create(InstanceKind.Piped, "https://c.example")
    private val registry = InstanceRegistry(listOf(a, b, c))
    private val calls = mutableListOf<String>()

    private fun health(isFailure: (Throwable) -> Boolean = { true }) =
        HealthRegistry(CircuitBreakerConfig(failureThreshold = 2, openDuration = 30.seconds, isFailure = isFailure), time)

    private fun executor(
        health: HealthRegistry = health(),
        config: FailoverConfig = FailoverConfig(),
    ) = FailoverExecutor(InstanceSelector(registry, health), health, config)

    private suspend fun FailoverExecutor.run(
        failing: Set<ApiInstance> = emptySet(),
        kind: InstanceKind = InstanceKind.Piped,
    ): String =
        execute(kind) { instance ->
            calls += instance.baseUrl.substringAfter("//")
            // By id, not equality: setPriority() replaces the registry's copy, which is no longer == to `a`.
            if (failing.any { it.id == instance.id }) throw Boom("${instance.baseUrl} down")
            "answer from ${instance.baseUrl.substringAfter("//")}"
        }

    @Test
    fun `the best candidate answers and nobody else is asked`() =
        runTest {
            val health = health()

            val answer = executor(health).run()

            assertEquals("answer from a.example", answer)
            assertEquals(listOf("a.example"), calls)
            assertEquals(1, health.snapshot(a.id)?.successes)
        }

    @Test
    fun `a failing instance fails over to the next and both outcomes are recorded`() =
        runTest {
            val health = health()
            val failovers = mutableListOf<InstanceFailure>()

            val answer = executor(health, FailoverConfig(onFailover = { failovers += it })).run(failing = setOf(a))

            assertEquals("answer from b.example", answer)
            assertEquals(listOf("a.example", "b.example"), calls)
            assertEquals(1, health.snapshot(a.id)?.failures)
            assertEquals(1, health.snapshot(b.id)?.successes)
            assertEquals(listOf(a), failovers.map { it.instance })
        }

    @Test
    fun `when every candidate fails the exception lists them all and chains the last cause`() =
        runTest {
            val failure =
                assertFailsWith<AllInstancesFailedException> {
                    executor().run(failing = setOf(a, b, c))
                }

            assertEquals(InstanceKind.Piped, failure.kind)
            assertEquals(listOf(a, b, c), failure.failures.map { it.instance })
            assertEquals("https://c.example down", failure.cause?.message)
            assertTrue("a.example" in failure.message.orEmpty() && "c.example" in failure.message.orEmpty())
        }

    @Test
    fun `it stops after maxInstancesToTry`() =
        runTest {
            assertFailsWith<AllInstancesFailedException> {
                executor(config = FailoverConfig(maxInstancesToTry = 2)).run(failing = setOf(a, b, c))
            }

            assertEquals(listOf("a.example", "b.example"), calls)
        }

    @Test
    fun `after a failure the next call goes straight to another instance`() =
        runTest {
            val executor = executor()

            executor.run(failing = setOf(a)) // a fails once and is demoted
            calls.clear()
            executor.run(failing = setOf(a))

            assertEquals(listOf("b.example"), calls) // a is not tried again while others are healthy
        }

    @Test
    fun `an instance whose circuit opens is skipped without being called`() =
        runTest {
            registry.setPriority(a.id, 10) // the user's preference outranks demotion, so a keeps being tried
            val health = health()
            val executor = executor(health)

            repeat(2) { executor.run(failing = setOf(a)) } // threshold is 2: a's circuit opens
            assertEquals(CircuitState.Open, health.snapshot(a.id)?.circuit)
            calls.clear()
            val answer = executor.run(failing = setOf(a))

            assertEquals("answer from b.example", answer)
            assertEquals(listOf("b.example"), calls) // a was not even tried
        }

    @Test
    fun `a dead instance is retried again once its circuit lets a probe through`() =
        runTest {
            registry.setPriority(a.id, 10)
            val health = health()
            val executor = executor(health)
            repeat(2) { executor.run(failing = setOf(a)) }
            assertEquals(CircuitState.Open, health.snapshot(a.id)?.circuit)

            time += 30.seconds
            calls.clear()
            executor.run() // a has recovered

            assertEquals(listOf("a.example"), calls)
            assertEquals(CircuitState.Closed, health.snapshot(a.id)?.circuit)
        }

    @Test
    fun `with every circuit open it fails fast without calling anyone`() =
        runTest {
            val executor = executor()
            repeat(2) { assertFailsWith<AllInstancesFailedException> { executor.run(failing = setOf(a, b, c)) } }
            calls.clear()

            val rejection = assertFailsWith<NoInstanceAvailableException> { executor.run() }

            assertEquals(3, rejection.enabledCount)
            assertTrue(calls.isEmpty())
            assertTrue("unavailable" in rejection.message.orEmpty())
        }

    @Test
    fun `with nothing configured the error says so`() =
        runTest {
            registry.replaceAll(emptyList())

            val rejection = assertFailsWith<NoInstanceAvailableException> { executor().run() }

            assertEquals(0, rejection.enabledCount)
            assertTrue("No enabled" in rejection.message.orEmpty())
        }

    @Test
    fun `disabled instances are never called and kinds do not mix`() =
        runTest {
            registry.setEnabled(a.id, false)
            registry.upsert(ApiInstance.create(InstanceKind.Invidious, "https://inv.example"))

            executor().run()
            executor().run(kind = InstanceKind.Invidious)

            assertEquals(listOf("b.example", "inv.example"), calls)
        }

    @Test
    fun `shouldFailover can turn a failure into an immediate rethrow`() =
        runTest {
            val config = FailoverConfig(shouldFailover = { it !is IllegalArgumentException })

            val failure =
                assertFailsWith<IllegalArgumentException> {
                    executor(config = config).execute(InstanceKind.Piped) { instance ->
                        calls += instance.baseUrl
                        throw IllegalArgumentException("bad request, no instance can fix it")
                    }
                }

            assertEquals("bad request, no instance can fix it", failure.message)
            assertEquals(1, calls.size)
        }

    @Test
    fun `cancelling the caller never moves on to the next instance`() =
        runTest {
            val job =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    executor().execute(InstanceKind.Piped) { instance ->
                        calls += instance.baseUrl
                        awaitCancellation()
                    }
                }
            runCurrent()
            assertEquals(1, calls.size)

            job.cancelAndJoin()

            assertTrue(job.isCancelled)
            assertEquals(1, calls.size)
        }

    @Test
    fun `per-instance retries are used before moving on`() =
        runTest {
            val retry = RetryPolicy(maxAttempts = 2, backoff = BackoffPolicy(1.milliseconds, 1.milliseconds, 1.0, Jitter.None))
            val executor = executor(health(), FailoverConfig(perInstanceRetry = retry))

            val answer = executor.run(failing = setOf(a))

            assertEquals("answer from b.example", answer)
            assertEquals(listOf("a.example", "a.example", "b.example"), calls)
        }

    // --- Failover driven by real Ktor status exceptions, through the HTTP failure classifier ---

    private fun clientAnswering(
        statusFor: (host: String) -> HttpStatusCode,
    ) = HttpClient(
        MockEngine { request ->
            val status = statusFor(request.url.host)
            respond(content = if (status.value < 300) "streams from ${request.url.host}" else "", status = status)
        },
    ) {
        applyHttpProfile(HttpProfile.Default)
    }

    @Test
    fun `a 503 from one instance fails over and counts against that instance`() =
        runTest {
            val health = health(HttpFailures::isInstanceFailure)
            val client = clientAnswering { host -> if (host == "a.example") HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK }

            val body = executor(health).execute(InstanceKind.Piped) { client.get(it.url("/streams/abc")).bodyAsText() }

            assertEquals("streams from b.example", body)
            val snapshot = assertNotNull(health.snapshot(a.id))
            assertEquals(1, snapshot.failures)
            assertEquals(1, snapshot.consecutiveFailures)
        }

    @Test
    fun `a 404 fails over for this request but does not count against a healthy instance`() =
        runTest {
            val health = health(HttpFailures::isInstanceFailure)
            val client = clientAnswering { host -> if (host == "a.example") HttpStatusCode.NotFound else HttpStatusCode.OK }

            val body = executor(health).execute(InstanceKind.Piped) { client.get(it.url("/streams/abc")).bodyAsText() }

            assertEquals("streams from b.example", body)
            val snapshot = assertNotNull(health.snapshot(a.id))
            assertEquals(0, snapshot.failures) // a answered, it simply did not have the video
            assertEquals(1, snapshot.successes)
            assertEquals(CircuitState.Closed, snapshot.circuit)
        }

    @Test
    fun `repeated 429s take an instance out of rotation`() =
        runTest {
            registry.setPriority(a.id, 10) // keep a first so it is hit until its breaker opens
            val health = health(HttpFailures::isInstanceFailure)
            val client = clientAnswering { host -> if (host == "a.example") HttpStatusCode.TooManyRequests else HttpStatusCode.OK }
            val executor = executor(health)

            repeat(2) { executor.execute(InstanceKind.Piped) { client.get(it.url("/x")).bodyAsText() } }

            assertSame(CircuitState.Open, health.snapshot(a.id)?.circuit)
            assertEquals(listOf(b, c), InstanceSelector(registry, health).candidates(InstanceKind.Piped))
        }
}

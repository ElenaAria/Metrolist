package ultra.core.network

import ultra.core.resilience.HealthRegistry
import ultra.core.resilience.ResilienceException
import ultra.core.resilience.RetryPolicy
import ultra.core.resilience.isOwnCancellation
import ultra.core.resilience.retrying
import kotlin.random.Random

public class InstanceFailure(
    public val instance: ApiInstance,
    public val cause: Throwable,
)

/** There was nobody to ask: no enabled instance is configured for [kind], or every one has an open circuit. */
public class NoInstanceAvailableException(
    public val kind: InstanceKind,
    /** Enabled instances of this kind that are configured. */
    public val enabledCount: Int,
) : ResilienceException(
        if (enabledCount == 0) {
            "No enabled $kind instance is configured"
        } else {
            "All $enabledCount enabled $kind instance(s) are unavailable (circuit open)"
        },
    )

/** Every candidate was tried and failed. The last failure is the [cause]; all of them are in [failures]. */
public class AllInstancesFailedException(
    public val kind: InstanceKind,
    public val failures: List<InstanceFailure>,
) : ResilienceException(
        "All ${failures.size} $kind instance(s) tried failed: " +
            failures.joinToString("; ") { "${it.instance.id} (${it.cause::class.simpleName}: ${it.cause.message})" },
    ) {
    init {
        failures.lastOrNull()?.let { initCause(it.cause) }
    }
}

public class FailoverConfig(
    /** Candidates to try before giving up. */
    public val maxInstancesToTry: Int = 3,
    /**
     * Retries on the *same* instance before moving on. The default is one attempt: moving to another
     * instance usually beats waiting on one that just failed. Each attempt goes through the instance's breaker.
     */
    public val perInstanceRetry: RetryPolicy = RetryPolicy(maxAttempts = 1),
    /** Whether a failure should move on to the next instance (false rethrows it as is). Cancellation never does. */
    public val shouldFailover: (Throwable) -> Boolean = { true },
    /** Called each time an instance is given up on, e.g. for logging. */
    public val onFailover: (InstanceFailure) -> Unit = {},
) {
    init {
        require(maxInstancesToTry >= 1) { "maxInstancesToTry must be at least 1: $maxInstancesToTry" }
    }
}

/**
 * Runs a call against the best available instance of a kind and, when it fails, against the next one.
 *
 * Every attempt goes through the instance's circuit breaker in [health], so repeated failures take a
 * dead instance out of rotation and its latency and errors are recorded for [InstanceSelector]. The
 * caller's [block] decides what a call is; this class only chooses where it runs.
 */
public class FailoverExecutor(
    private val selector: InstanceSelector,
    private val health: HealthRegistry,
    private val config: FailoverConfig = FailoverConfig(),
    private val random: Random = Random.Default,
) {
    public suspend fun <T> execute(
        kind: InstanceKind,
        block: suspend (ApiInstance) -> T,
    ): T {
        val candidates = selector.candidates(kind)
        if (candidates.isEmpty()) throw NoInstanceAvailableException(kind, selector.enabledCount(kind))

        val failures = mutableListOf<InstanceFailure>()
        for (instance in candidates.take(config.maxInstancesToTry)) {
            try {
                return retrying(config.perInstanceRetry, random) {
                    health.execute(instance.id) { block(instance) }
                }
            } catch (e: Throwable) {
                if (e.isOwnCancellation() || !config.shouldFailover(e)) throw e
                val failure = InstanceFailure(instance, e)
                failures += failure
                config.onFailover(failure)
            }
        }
        throw AllInstancesFailedException(kind, failures)
    }
}

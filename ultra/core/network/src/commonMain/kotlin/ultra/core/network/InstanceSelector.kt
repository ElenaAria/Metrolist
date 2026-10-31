package ultra.core.network

import ultra.core.resilience.HealthRegistry
import kotlin.time.Duration

/**
 * Decides which instances to try, and in what order, for a given [InstanceKind].
 *
 * Candidates are the enabled instances of that kind whose circuit is not open, ordered by:
 * 1. [ApiInstance.priority], highest first (the user's explicit preference always wins);
 * 2. instances that have not just failed before ones that have (`consecutiveFailures > 0`);
 * 3. measured latency, fastest first, with instances not measured yet after those that are;
 * 4. registration order, so the result is stable.
 */
public class InstanceSelector(
    private val registry: InstanceRegistry,
    private val health: HealthRegistry,
) {
    public fun candidates(kind: InstanceKind): List<ApiInstance> {
        val usable = registry.instances(kind).filter { it.enabled && health.isAvailable(it.id) }
        return usable
            .withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<ApiInstance>> { it.value.priority }
                    .thenBy { health.snapshot(it.value.id)?.consecutiveFailures?.let { failures -> failures > 0 } ?: false }
                    .thenBy { health.snapshot(it.value.id)?.averageLatency ?: Duration.INFINITE }
                    .thenBy { it.index },
            ).map { it.value }
    }

    /** How many enabled instances of [kind] are configured, regardless of circuit state. */
    public fun enabledCount(kind: InstanceKind): Int = registry.instances(kind).count { it.enabled }
}

package ultra.core.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The set of configured [ApiInstance]s, observable and safe to change from any thread. It holds
 * configuration only: persistence belongs to the layer that owns storage, which loads instances
 * into the registry at startup and observes [instances] to save changes.
 *
 * The registry deliberately ships with no default instances: public instance lists rot, so the
 * app seeds them from settings.
 */
public class InstanceRegistry(
    initial: List<ApiInstance> = emptyList(),
) {
    private val state = MutableStateFlow(checkedUnique(initial))

    /** All instances in registration order, including disabled ones. */
    public val instances: StateFlow<List<ApiInstance>> = state.asStateFlow()

    public fun instances(kind: InstanceKind): List<ApiInstance> = state.value.filter { it.kind == kind }

    public fun find(id: String): ApiInstance? = state.value.firstOrNull { it.id == id }

    /** Adds the instance, or replaces the one with the same id in place (keeping its position). */
    public fun upsert(instance: ApiInstance) {
        state.update { list ->
            if (list.any { it.id == instance.id }) list.map { if (it.id == instance.id) instance else it } else list + instance
        }
    }

    /** Returns true when an instance was removed. */
    public fun remove(id: String): Boolean {
        var removed = false
        state.update { list ->
            removed = list.any { it.id == id }
            list.filterNot { it.id == id }
        }
        return removed
    }

    /** Returns false when there is no such instance. */
    public fun setEnabled(
        id: String,
        enabled: Boolean,
    ): Boolean = modify(id) { it.copy(enabled = enabled) }

    /** Returns false when there is no such instance. */
    public fun setPriority(
        id: String,
        priority: Int,
    ): Boolean = modify(id) { it.copy(priority = priority) }

    public fun replaceAll(instances: List<ApiInstance>) {
        state.value = checkedUnique(instances)
    }

    private fun modify(
        id: String,
        change: (ApiInstance) -> ApiInstance,
    ): Boolean {
        var found = false
        state.update { list ->
            found = list.any { it.id == id }
            list.map { if (it.id == id) change(it) else it }
        }
        return found
    }

    private fun checkedUnique(list: List<ApiInstance>): List<ApiInstance> {
        val duplicate = list.groupBy { it.id }.entries.firstOrNull { it.value.size > 1 }?.key
        require(duplicate == null) { "duplicate instance id: $duplicate" }
        return list.toList()
    }
}

package ultra.core.resilience

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/**
 * Caps concurrent calls to one dependency so a slow instance cannot soak up every coroutine and
 * connection. Callers either fail fast ([maxWait] of zero) or queue for a bounded time.
 */
public class Bulkhead(
    public val name: String,
    public val maxConcurrent: Int,
    public val maxWait: Duration = Duration.ZERO,
) {
    init {
        require(maxConcurrent >= 1) { "maxConcurrent must be at least 1: $maxConcurrent" }
        require(!maxWait.isNegative()) { "maxWait must not be negative: $maxWait" }
    }

    private val semaphore = Semaphore(maxConcurrent)

    public val availablePermits: Int get() = semaphore.availablePermits

    public suspend fun <T> execute(block: suspend () -> T): T {
        if (!acquire()) throw BulkheadFullException(name, maxConcurrent)
        try {
            return block()
        } finally {
            semaphore.release()
        }
    }

    private suspend fun acquire(): Boolean {
        if (semaphore.tryAcquire()) return true
        if (maxWait == Duration.ZERO) return false
        // Semaphore.acquire is cancellable and gives its slot back if cancelled mid-wait.
        return withTimeoutOrNull(maxWait) {
            semaphore.acquire()
            true
        } ?: false
    }
}

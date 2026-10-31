package ultra.core.resilience

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.coroutines.cancellation.CancellationException

internal enum class CallOutcome {
    /** The dependency answered (including with an error the caller chose not to treat as unhealthy). */
    Success,

    /** The dependency is unhealthy: counts against it. */
    Failure,

    /** Says nothing about the dependency: the caller was cancelled, or a primitive rejected the call. */
    Ignored,
}

/**
 * True when [this] is cancellation of the *calling* coroutine, which must always propagate. An inner
 * `withTimeout` also throws a [CancellationException] subclass but leaves the caller alive; that is an
 * ordinary failure (the dependency was too slow), so it is not "own" cancellation. Anything that catches
 * `Throwable` around a guarded call should rethrow when this is true.
 */
public suspend fun Throwable.isOwnCancellation(): Boolean {
    if (this !is CancellationException) return false
    return !(this is TimeoutCancellationException && currentCoroutineContext().isActive)
}

/**
 * Runs [block] and reports how it went to [onOutcome] exactly once, then returns or rethrows. It is the
 * single place that decides what counts as a failure, shared by [CircuitBreaker] and [HealthRegistry]
 * so the two can never disagree.
 */
internal suspend fun <T> observeOutcome(
    isFailure: (Throwable) -> Boolean,
    onOutcome: (CallOutcome, Throwable?) -> Unit,
    block: suspend () -> T,
): T {
    val result =
        try {
            block()
        } catch (e: ResilienceException) {
            onOutcome(CallOutcome.Ignored, e)
            throw e
        } catch (e: Throwable) {
            val outcome =
                when {
                    e.isOwnCancellation() -> CallOutcome.Ignored
                    e is CancellationException -> CallOutcome.Failure // inner timeout, caller still alive
                    isFailure(e) -> CallOutcome.Failure
                    else -> CallOutcome.Success
                }
            onOutcome(outcome, e)
            throw e
        }
    onOutcome(CallOutcome.Success, null)
    return result
}

package ultra.core.resilience

import kotlin.time.Duration

/**
 * Thrown by a resilience primitive itself, as opposed to by the guarded call. Primitives never count
 * these as the guarded dependency's failures (a full bulkhead says nothing about the server's health).
 */
public open class ResilienceException(
    message: String,
) : RuntimeException(message)

public class CircuitOpenException(
    public val name: String,
    /** How long until the breaker lets a probe through. */
    public val retryAfter: Duration,
) : ResilienceException("Circuit '$name' is open; next probe in $retryAfter")

public class BulkheadFullException(
    public val name: String,
    public val maxConcurrent: Int,
) : ResilienceException("Bulkhead '$name' is full ($maxConcurrent concurrent calls)")

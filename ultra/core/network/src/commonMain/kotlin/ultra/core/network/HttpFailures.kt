package ultra.core.network

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import kotlinx.io.IOException

/**
 * Tells "this instance is unhealthy" apart from "this particular request was bad", so a 404 for a
 * deleted video never trips the breaker of a perfectly good server. Plug it into
 * `CircuitBreakerConfig(isFailure = HttpFailures::isInstanceFailure)`.
 */
public object HttpFailures {
    /**
     * Unhealthy: timeouts, connection and other I/O errors, 5xx responses, and 408 / 429 (the server
     * is overloaded or rate-limiting us). Everything else, including other 4xx, is not the instance's fault.
     */
    public fun isInstanceFailure(error: Throwable): Boolean =
        when (error) {
            is ResponseException -> error.response.status.value.let { it >= 500 || it == 408 || it == 429 }
            is HttpRequestTimeoutException, is ConnectTimeoutException, is SocketTimeoutException -> true
            is IOException -> true
            else -> false
        }
}

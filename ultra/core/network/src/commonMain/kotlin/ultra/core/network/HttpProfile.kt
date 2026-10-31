package ultra.core.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.UserAgent
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Timeouts and behaviour for one kind of traffic. Short-lived metadata calls and long audio downloads
 * want very different limits, so callers ask the provider for a client by profile.
 */
public data class HttpProfile(
    /** Time allowed to establish the connection. */
    public val connectTimeout: Duration = 10.seconds,
    /** Longest silence tolerated between two chunks of the response. */
    public val readTimeout: Duration = 30.seconds,
    /** Limit for the whole call; [Duration.INFINITE] for none (streaming). */
    public val callTimeout: Duration = 60.seconds,
    public val userAgent: String? = null,
    public val followRedirects: Boolean = true,
    /** When true, non-2xx responses throw `ResponseException`, which is what failover reacts to. */
    public val expectSuccess: Boolean = true,
) {
    init {
        require(connectTimeout.isPositive()) { "connectTimeout must be positive: $connectTimeout" }
        require(readTimeout.isPositive()) { "readTimeout must be positive: $readTimeout" }
        require(callTimeout.isPositive()) { "callTimeout must be positive: $callTimeout" }
    }

    public companion object {
        public val Default: HttpProfile = HttpProfile()

        /** User-facing lookups: fail fast so failover can kick in. */
        public val Interactive: HttpProfile = HttpProfile(connectTimeout = 5.seconds, readTimeout = 10.seconds, callTimeout = 15.seconds)

        /** Long transfers such as audio: tolerate a slow trickle and put no cap on the whole call. */
        public val Streaming: HttpProfile = HttpProfile(connectTimeout = 10.seconds, readTimeout = 60.seconds, callTimeout = Duration.INFINITE)
    }
}

/**
 * Hands out Ktor clients, one per [HttpProfile], and owns them. Common code asks for a client and never
 * names an engine; implementations choose it (OkHttp on JVM and Android).
 */
public interface HttpClientProvider : AutoCloseable {
    /** The client for [profile]. The same profile always yields the same instance until [close]. */
    public fun client(profile: HttpProfile = HttpProfile.Default): HttpClient
}

/** Applies [profile]'s timeouts, user agent, redirect and error behaviour to a client under construction. */
public fun HttpClientConfig<*>.applyHttpProfile(profile: HttpProfile) {
    install(HttpTimeout) {
        connectTimeoutMillis = profile.connectTimeout.toTimeoutMillis()
        socketTimeoutMillis = profile.readTimeout.toTimeoutMillis()
        requestTimeoutMillis = profile.callTimeout.toTimeoutMillis()
    }
    profile.userAgent?.let { agent -> install(UserAgent) { this.agent = agent } }
    followRedirects = profile.followRedirects
    expectSuccess = profile.expectSuccess
}

private fun Duration.toTimeoutMillis(): Long = if (isInfinite()) HttpTimeoutConfig.INFINITE_TIMEOUT_MS else inWholeMilliseconds.coerceAtLeast(1)

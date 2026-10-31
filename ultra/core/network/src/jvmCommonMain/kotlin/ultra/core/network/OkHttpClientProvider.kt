package ultra.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [HttpClientProvider] on Ktor's OkHttp engine, built on top of an existing [base] client so ultra
 * traffic can share the app's connection pool, proxy, DNS and certificate settings.
 *
 * Each profile gets its own Ktor client over `base.newBuilder()` with a *private* [Dispatcher]: Ktor's
 * OkHttp engine shuts down the dispatcher of the client it is given when it closes, and that must never
 * be the one the rest of the app is using. The connection pool is still shared, and [close] never
 * touches [base].
 */
public class OkHttpClientProvider(
    private val base: OkHttpClient = OkHttpClient(),
) : HttpClientProvider {
    private val clients = ConcurrentHashMap<HttpProfile, HttpClient>()
    private val closed = AtomicBoolean(false)

    override fun client(profile: HttpProfile): HttpClient {
        check(!closed.get()) { "OkHttpClientProvider is closed" }
        return clients.computeIfAbsent(profile) { create(it) }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            clients.values.forEach { it.close() }
            clients.clear()
        }
    }

    private fun create(profile: HttpProfile): HttpClient {
        val okHttp = base.newBuilder().dispatcher(Dispatcher()).build()
        return HttpClient(OkHttp) {
            engine { preconfigured = okHttp }
            applyHttpProfile(profile)
        }
    }
}

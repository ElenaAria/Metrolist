package ultra.core.network

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Runs the real OkHttp engine against a throwaway local server, so engine behaviour is tested, not assumed. */
class OkHttpClientProviderTest {
    private lateinit var server: HttpServer
    private lateinit var base: String

    private fun reply(
        exchange: HttpExchange,
        status: Int,
        body: String,
    ) {
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    @BeforeTest
    fun startServer() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/echo-agent") { reply(it, 200, it.requestHeaders.getFirst("User-Agent").orEmpty()) }
        server.createContext("/boom") { reply(it, 503, "") }
        server.createContext("/missing") { reply(it, 404, "") }
        server.createContext("/slow") {
            Thread.sleep(2_000)
            runCatching { reply(it, 200, "late") } // the client has usually hung up by now
        }
        server.start()
        base = "http://${server.address.hostString}:${server.address.port}"
    }

    @AfterTest
    fun stopServer() {
        server.stop(0)
    }

    @Test
    fun `requests carry the profile's user agent`() =
        runBlocking {
            OkHttpClientProvider().use { provider ->
                val client = provider.client(HttpProfile(userAgent = "ultra-test/1.0"))

                assertEquals("ultra-test/1.0", client.get("$base/echo-agent").bodyAsText())
            }
        }

    @Test
    fun `one client per profile`() {
        OkHttpClientProvider().use { provider ->
            val interactive = provider.client(HttpProfile.Interactive)

            assertSame(interactive, provider.client(HttpProfile.Interactive))
            assertNotSame(interactive, provider.client(HttpProfile.Streaming))
        }
    }

    @Test
    fun `a stalled server trips the read timeout, which counts as the instance failing`() =
        runBlocking {
            OkHttpClientProvider().use { provider ->
                val client = provider.client(HttpProfile(connectTimeout = 1.seconds, readTimeout = 300.milliseconds, callTimeout = 5.seconds))

                val failure = runCatching { client.get("$base/slow").bodyAsText() }.exceptionOrNull()

                assertTrue(failure != null, "the call should have timed out")
                assertTrue(HttpFailures.isInstanceFailure(failure), "was ${failure::class.simpleName}: ${failure.message}")
            }
        }

    @Test
    fun `real 5xx counts against the instance and real 404 does not`() =
        runBlocking {
            OkHttpClientProvider().use { provider ->
                val client = provider.client()

                val serverError = assertFailsWith<io.ktor.client.plugins.ServerResponseException> { client.get("$base/boom") }
                val notFound = assertFailsWith<io.ktor.client.plugins.ClientRequestException> { client.get("$base/missing") }

                assertEquals(HttpStatusCode.ServiceUnavailable, serverError.response.status)
                assertTrue(HttpFailures.isInstanceFailure(serverError))
                assertFalse(HttpFailures.isInstanceFailure(notFound))
            }
        }

    @Test
    fun `closing the provider leaves the base client usable`() =
        runBlocking {
            val shared = OkHttpClient()
            val provider = OkHttpClientProvider(shared)
            provider.client().get("$base/echo-agent") // make sure an engine was really started

            provider.close()

            assertFalse(shared.dispatcher.executorService.isShutdown, "Ktor must not shut down the app's dispatcher")
            assertFailsWith<IllegalStateException> { provider.client() }
            provider.close() // closing twice is harmless
        }
}

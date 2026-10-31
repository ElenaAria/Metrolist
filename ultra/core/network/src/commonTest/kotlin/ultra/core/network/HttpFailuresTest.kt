package ultra.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class HttpFailuresTest {
    private suspend fun failureFor(status: HttpStatusCode): Throwable {
        val client = HttpClient(MockEngine { respond(content = "", status = status) }) { applyHttpProfile(HttpProfile.Default) }
        return assertFails { client.get("http://instance.example/") }
    }

    private suspend fun assertFails(block: suspend () -> Unit): Throwable {
        try {
            block()
        } catch (e: Throwable) {
            return e
        }
        throw AssertionError("expected the call to fail")
    }

    @Test
    fun `server errors, overload and rate limiting are the instance's fault`() =
        runTest {
            for (status in listOf(HttpStatusCode.InternalServerError, HttpStatusCode.BadGateway, HttpStatusCode.ServiceUnavailable, HttpStatusCode.RequestTimeout, HttpStatusCode.TooManyRequests)) {
                assertTrue(HttpFailures.isInstanceFailure(failureFor(status)), "${status.value} should count as a failure")
            }
        }

    @Test
    fun `a bad request is not the instance's fault`() =
        runTest {
            for (status in listOf(HttpStatusCode.BadRequest, HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden, HttpStatusCode.NotFound)) {
                assertFalse(HttpFailures.isInstanceFailure(failureFor(status)), "${status.value} should not count as a failure")
            }
        }

    @Test
    fun `timeouts and I-O errors are the instance's fault`() {
        assertTrue(HttpFailures.isInstanceFailure(HttpRequestTimeoutException("http://instance.example/", 1_000)))
        assertTrue(HttpFailures.isInstanceFailure(ConnectTimeoutException("connect timed out", null)))
        assertTrue(HttpFailures.isInstanceFailure(SocketTimeoutException("read timed out", null)))
        assertTrue(HttpFailures.isInstanceFailure(IOException("connection reset")))
    }

    @Test
    fun `programming and parsing errors are not blamed on the instance`() {
        assertFalse(HttpFailures.isInstanceFailure(IllegalStateException("bug")))
        assertFalse(HttpFailures.isInstanceFailure(IllegalArgumentException("bad input")))
    }

    @Test
    fun `an http profile with expectSuccess off lets error statuses through`() =
        runTest {
            val profile = HttpProfile(expectSuccess = false)
            val client = HttpClient(MockEngine { respond(content = "", status = HttpStatusCode.InternalServerError) }) { applyHttpProfile(profile) }

            assertEquals(HttpStatusCode.InternalServerError, client.get("http://instance.example/").status)
        }

    @Test
    fun `the profile's user agent is sent on every request`() =
        runTest {
            var seen: String? = null
            val client =
                HttpClient(MockEngine { request -> seen = request.headers[HttpHeaders.UserAgent]; respond("ok") }) {
                    applyHttpProfile(HttpProfile(userAgent = "ultra-test/1.0"))
                }

            client.get("http://instance.example/") { header("X-Other", "1") }

            assertEquals("ultra-test/1.0", seen)
        }

    @Test
    fun `profiles reject non-positive timeouts and expose sensible presets`() {
        assertFailsWith<IllegalArgumentException> { HttpProfile(connectTimeout = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { HttpProfile(readTimeout = (-1).seconds) }
        assertTrue(HttpProfile.Streaming.callTimeout.isInfinite())
        assertTrue(HttpProfile.Interactive.callTimeout < HttpProfile.Default.callTimeout)
        assertTrue(HttpProfile.Streaming.readTimeout > HttpProfile.Default.readTimeout)
    }
}

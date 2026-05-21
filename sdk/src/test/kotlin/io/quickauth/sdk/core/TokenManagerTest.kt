package io.quickauth.sdk.core

import com.google.gson.Gson
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

class TokenManagerTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test fun `getToken invokes onTokenExpiry on first call`() = runBlocking {
        val calls = AtomicInteger(0)
        val tm = TokenManager(
            config = Config(
                apiBaseUrl = "https://api.test",
                onTokenExpiry = {
                    calls.incrementAndGet()
                    NEVER_EXPIRES
                },
            ),
        )

        assertEquals(NEVER_EXPIRES, tm.getToken())
        assertEquals(1, calls.get())
    }

    @Test fun `getToken caches result across calls`() = runBlocking {
        val calls = AtomicInteger(0)
        val tm = TokenManager(
            config = Config(
                apiBaseUrl = "https://api.test",
                onTokenExpiry = {
                    calls.incrementAndGet()
                    NEVER_EXPIRES
                },
            ),
        )

        repeat(5) { tm.getToken() }
        assertEquals(1, calls.get())
    }

    @Test fun `concurrent callers single-flight to one provider invocation`() {
        val calls = AtomicInteger(0)
        val tm = TokenManager(
            config = Config(
                apiBaseUrl = "https://api.test",
                onTokenExpiry = {
                    calls.incrementAndGet()
                    delay(50) // give peers time to pile on
                    NEVER_EXPIRES
                },
            ),
        )

        runBlocking {
            coroutineScope {
                val results = (1..5).map { async { tm.getToken() } }.awaitAll()
                results.forEach { assertEquals(NEVER_EXPIRES, it) }
            }
        }
        assertEquals("provider must be called exactly once for concurrent first-time requests", 1, calls.get())
    }

    @Test fun `invalidate forces a new fetch`() = runBlocking {
        val calls = AtomicInteger(0)
        val tm = TokenManager(
            config = Config(
                apiBaseUrl = "https://api.test",
                onTokenExpiry = {
                    calls.incrementAndGet()
                    NEVER_EXPIRES
                },
            ),
        )

        tm.getToken()
        tm.invalidate()
        tm.getToken()

        assertEquals(2, calls.get())
    }

    @Test fun `expiring-soon token triggers refresh`() = runBlocking {
        val nowSec = System.currentTimeMillis() / 1000L
        // A token that expires in 10 seconds — well inside the 30-second leeway.
        val expiring = jwtWithExp(nowSec + 10)
        val fresh = jwtWithExp(nowSec + 600)
        val responses = mutableListOf(fresh)
        val calls = AtomicInteger(0)
        val tm = TokenManager(
            config = Config(
                apiBaseUrl = "https://api.test",
                onTokenExpiry = {
                    calls.incrementAndGet()
                    responses.removeAt(0)
                },
            ),
            initialToken = expiring,
        )

        val token = tm.getToken()
        assertEquals(fresh, token)
        assertEquals(1, calls.get())
    }

    @Test fun `non-expiring token is reused`() = runBlocking {
        val nowSec = System.currentTimeMillis() / 1000L
        val long = jwtWithExp(nowSec + 600)
        val calls = AtomicInteger(0)
        val tm = TokenManager(
            config = Config(
                apiBaseUrl = "https://api.test",
                onTokenExpiry = {
                    calls.incrementAndGet()
                    "should-not-be-called"
                },
            ),
            initialToken = long,
        )

        assertEquals(long, tm.getToken())
        assertEquals(0, calls.get())
    }

    @Test fun `decodeExpiry parses exp claim`() {
        val nowSec = System.currentTimeMillis() / 1000L
        val jwt = jwtWithExp(nowSec + 120)
        assertEquals(nowSec + 120, TokenManager.decodeExpiry(jwt))
    }

    @Test fun `decodeExpiry returns null for malformed input`() {
        assertNull(TokenManager.decodeExpiry("not-a-jwt"))
        assertNull(TokenManager.decodeExpiry(""))
    }

    @Test fun `unsafe direct mode mints from POST v1 sdk session`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"sessionToken":"$NEVER_EXPIRES","expiresIn":600}"""),
        )
        var providerCalled = false
        val tm = TokenManager(
            config = Config(
                apiBaseUrl = server.url("/").toString().trimEnd('/'),
                onTokenExpiry = { providerCalled = true; "never" },
                unsafeDirectClientId = "client_abc",
                unsafeDirectClientSecret = "secret_xyz",
            ),
        )

        val token = tm.getToken()
        val recorded = server.takeRequest()

        assertEquals(NEVER_EXPIRES, token)
        assertEquals("POST", recorded.method)
        assertEquals("/v1/sdk/session", recorded.path)
        assertEquals("client_abc", recorded.getHeader("X-QuickAuth-Client-Id"))
        assertEquals("secret_xyz", recorded.getHeader("X-QuickAuth-Client-Secret"))
        assertNotNull(recorded.getHeader("User-Agent"))
        assertTrue("onTokenExpiry must NOT be called in unsafe direct mode", !providerCalled)
    }

    private companion object {
        // A 3-segment JWT-shaped string with no `exp` claim → TokenManager treats as "never refresh".
        const val NEVER_EXPIRES = "eyJhbGciOiJIUzI1NiJ9.eyJpc3MiOiJ0ZXN0In0.sig"

        fun jwtWithExp(expSeconds: Long): String {
            val header = Base64.getUrlEncoder().withoutPadding().encodeToString(
                """{"alg":"HS256","typ":"JWT"}""".toByteArray(),
            )
            val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                Gson().toJson(mapOf("exp" to expSeconds)).toByteArray(),
            )
            return "$header.$payload.sig"
        }
    }
}

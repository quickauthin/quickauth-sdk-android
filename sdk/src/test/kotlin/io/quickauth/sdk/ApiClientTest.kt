package io.quickauth.sdk

import io.quickauth.sdk.core.ApiClient
import io.quickauth.sdk.core.ApiException
import io.quickauth.sdk.core.Config
import io.quickauth.sdk.core.ConsentNotGrantedException
import io.quickauth.sdk.core.TokenManager
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ApiClientTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun configFor(provider: suspend () -> String = { TOKEN_A }): Config = Config(
        apiBaseUrl = server.url("/").toString().trimEnd('/'),
        onTokenExpiry = provider,
    )

    private fun client(
        config: Config = configFor(),
        consentProvider: (String) -> Boolean = { true },
        tokenManager: TokenManager? = null,
    ): ApiClient = ApiClient(
        config = config,
        consentProvider = consentProvider,
        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build(),
        tokenManager = tokenManager,
    )

    @Test fun `posts to expected URL with bearer header and idempotency key`() = runTest {
        server.enqueue(MockResponse().setBody("""{"sessionId":"abc","expiresIn":300}""").setResponseCode(200))

        val resp = client().postJson(
            "/v1/sdk/auth/initiate",
            mapOf("phone" to "+919876543210", "channel" to "auto"),
            Map::class.java,
        )

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/sdk/auth/initiate", recorded.path)
        assertEquals("Bearer $TOKEN_A", recorded.getHeader("Authorization"))
        // The legacy X-QuickAuth-Public-Key header is gone with the publicKey-based auth model.
        assertNull(recorded.getHeader("X-QuickAuth-Public-Key"))
        assertNotNull("Idempotency-Key must be present", recorded.getHeader("Idempotency-Key"))
        assertTrue(
            "User-Agent must identify the SDK",
            recorded.getHeader("User-Agent")?.startsWith("quickauth-sdk-android/") == true,
        )
        assertEquals("abc", (resp as Map<*, *>)["sessionId"])
    }

    @Test fun `invokes onTokenExpiry on first call`() = runTest {
        val calls = AtomicInteger(0)
        server.enqueue(MockResponse().setBody("""{"ok":true}""").setResponseCode(200))

        val cfg = configFor {
            calls.incrementAndGet()
            TOKEN_A
        }
        client(cfg).postJson("/v1/sdk/auth/initiate", emptyMap<String, Any>(), Map::class.java)

        assertEquals(1, calls.get())
        assertEquals("Bearer $TOKEN_A", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun `401 invalidates cached token and retries once with fresh token`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"expired"}"""))
        server.enqueue(MockResponse().setBody("""{"ok":true}""").setResponseCode(200))

        val sequence = listOf(TOKEN_A, TOKEN_B).iterator()
        val calls = AtomicInteger(0)
        val cfg = configFor {
            calls.incrementAndGet()
            sequence.next()
        }
        val resp = client(cfg).postJson("/v1/sdk/auth/initiate", emptyMap<String, Any>(), Map::class.java)

        assertEquals(true, (resp as Map<*, *>)["ok"])
        assertEquals(2, server.requestCount)
        assertEquals(2, calls.get()) // once for first attempt, again after invalidate
        assertEquals("Bearer $TOKEN_A", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer $TOKEN_B", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun `retries on 5xx and returns successful response`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(502))
        server.enqueue(MockResponse().setBody("""{"ok":true}""").setResponseCode(200))

        val resp = client().postJson("/v1/sdk/auth/initiate", emptyMap<String, Any>(), Map::class.java)

        assertEquals(true, (resp as Map<*, *>)["ok"])
        assertEquals(3, server.requestCount)
    }

    @Test fun `does not retry on 4xx other than 401`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"bad phone"}"""))

        try {
            client().postJson("/v1/sdk/auth/initiate", emptyMap<String, Any>(), Map::class.java)
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(400, e.code)
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun `consent provider rejects PII calls`() = runTest {
        val gated = client(consentProvider = { false })
        try {
            gated.postJson("/v1/sdk/attribution/launch", emptyMap<String, Any>(), Map::class.java)
            fail("expected ConsentNotGrantedException")
        } catch (e: ConsentNotGrantedException) {
            // pass
        }
        assertEquals(0, server.requestCount)
    }

    private companion object {
        // Tokens with no `exp` claim — TokenManager treats these as "never expires" and only
        // refetches on 401 or after explicit invalidate().  Header form is irrelevant for the
        // base64 decoder; we just need 3 dot-separated segments.
        const val TOKEN_A = "eyJhbGciOiJIUzI1NiJ9.eyJpc3MiOiJ0ZXN0In0.sigA"
        const val TOKEN_B = "eyJhbGciOiJIUzI1NiJ9.eyJpc3MiOiJ0ZXN0LTIifQ.sigB"
    }
}

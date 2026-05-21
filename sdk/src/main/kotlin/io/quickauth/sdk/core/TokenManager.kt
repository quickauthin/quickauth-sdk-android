package io.quickauth.sdk.core

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Thread-safe ephemeral-token cache used by [ApiClient].
 *
 *  * Calls [Config.onTokenExpiry] (or directly hits `/v1/sdk/session` in unsafe mode) when:
 *      - no token cached yet
 *      - cached token expires in <[REFRESH_LEEWAY_SECONDS]s
 *  * Single-flight: if 5 coroutines call [getToken] concurrently with no cached token,
 *    the provider is invoked exactly once and all 5 await the same [Deferred].
 *  * [invalidate] forces the next call to refetch — used by the 401-retry path.
 *
 * The class deliberately avoids any Android dependency so it's trivially unit-testable on
 * a plain JVM.
 */
internal class TokenManager(
    private val config: Config,
    initialToken: String? = config.initialToken,
    private val unsafeHttpClient: OkHttpClient? = null,
    private val unsafeBaseUrl: String = config.apiBaseUrl,
) {

    private val mutex = Mutex()

    @Volatile
    private var cached: CachedToken? = initialToken?.let { CachedToken(it, decodeExpiry(it)) }

    @Volatile
    private var inflight: Deferred<String>? = null

    private val gson: Gson = Gson()

    /**
     * Return a valid bearer token.  Refreshes if missing, expired, or expiring within
     * [REFRESH_LEEWAY_SECONDS].
     */
    suspend fun getToken(): String {
        cached?.let { c ->
            if (c.expiresAtSeconds == null || !isExpiringSoon(c.expiresAtSeconds)) {
                return c.token
            }
        }
        return refresh()
    }

    /** Drop the cached token; the next [getToken] call will fetch a new one. */
    fun invalidate() {
        cached = null
        inflight = null
    }

    /**
     * Fetch a new token.  Multiple concurrent callers join the same in-flight [Deferred] —
     * only one network/provider call happens.  The mutex is held only long enough to
     * observe-or-claim [inflight]; the slow `await()` happens outside the critical section
     * so other coroutines can pile onto the same [Deferred].
     */
    private suspend fun refresh(): String = coroutineScope {
        val deferred: Deferred<String> = mutex.withLock {
            // Double-check under the lock: another coroutine may have just refreshed.
            cached?.let { c ->
                if (c.expiresAtSeconds == null || !isExpiringSoon(c.expiresAtSeconds)) {
                    return@coroutineScope c.token
                }
            }
            inflight ?: async(Dispatchers.IO) {
                try {
                    val token = fetchTokenOnce()
                    cached = CachedToken(token, decodeExpiry(token))
                    token
                } finally {
                    // Clear under the lock so a subsequent caller starts a fresh attempt.
                    mutex.withLock { inflight = null }
                }
            }.also { inflight = it }
        }
        deferred.await()
    }

    private suspend fun fetchTokenOnce(): String {
        return if (config.isUnsafeDirectMode) {
            mintFromQuickAuth()
        } else {
            config.onTokenExpiry()
        }
    }

    /**
     * UNSAFE escape hatch: hits `POST /v1/sdk/session` directly with the embedded
     * `client_id`/`client_secret`.  Only enabled when both `unsafeDirectClientId` and
     * `unsafeDirectClientSecret` are set on the [Config].
     */
    private fun mintFromQuickAuth(): String {
        val client = unsafeHttpClient ?: OkHttpClient()
        val url = "${unsafeBaseUrl.trimEnd('/')}/v1/sdk/session"
        val body = "{}".toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder()
            .url(url)
            .post(body)
            .header("X-QuickAuth-Client-Id", config.unsafeDirectClientId!!)
            .header("X-QuickAuth-Client-Secret", config.unsafeDirectClientSecret!!)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("User-Agent", config.userAgent)
            .build()
        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw ApiException(resp.code, "Failed to mint sessionToken", raw)
            }
            return try {
                val parsed = gson.fromJson(raw, SessionResponse::class.java)
                parsed.sessionToken
                    ?: throw ApiException(resp.code, "Missing sessionToken in /v1/sdk/session response", raw)
            } catch (e: JsonSyntaxException) {
                throw ApiException(resp.code, "Malformed /v1/sdk/session response: ${e.message}", raw)
            }
        }
    }

    private fun isExpiringSoon(expiresAtSeconds: Long): Boolean {
        val now = System.currentTimeMillis() / 1000L
        return expiresAtSeconds - now <= REFRESH_LEEWAY_SECONDS
    }

    private data class CachedToken(val token: String, val expiresAtSeconds: Long?)

    private data class SessionResponse(val sessionToken: String?, val expiresIn: Int?)

    internal companion object {
        /** Refresh window — refetch when the token has <30s of life left. */
        const val REFRESH_LEEWAY_SECONDS: Long = 30L

        /**
         * Decode the `exp` claim from a JWT.  Base64-URL decodes the middle segment, parses
         * the JSON, and returns the `exp` value in **seconds since epoch**.  Returns null if
         * the token is malformed or missing `exp` — callers treat that as "never expires"
         * and only refetch on 401.
         */
        fun decodeExpiry(jwt: String): Long? {
            return try {
                val segments = jwt.split('.')
                if (segments.size < 2) return null
                val payload = decodeBase64Url(segments[1])
                val json = String(payload, Charsets.UTF_8)
                val map = Gson().fromJson(json, Map::class.java)
                when (val exp = map?.get("exp")) {
                    is Number -> exp.toLong()
                    is String -> exp.toLongOrNull()
                    else -> null
                }
            } catch (_: Throwable) {
                null
            }
        }

        /**
         * Base64-URL decoder that works across our minSdk-21 floor.  `java.util.Base64` is
         * only API-26+, so for Android API 21–25 we fall back to `android.util.Base64` via
         * reflection (avoids a hard test-time dep on the Android stubs jar).
         */
        private fun decodeBase64Url(s: String): ByteArray {
            val padded = padBase64(s).replace('-', '+').replace('_', '/')
            // Try java.util.Base64 first — available on JVM (unit tests) and Android 26+.
            return try {
                java.util.Base64.getDecoder().decode(padded)
            } catch (_: NoClassDefFoundError) {
                decodeViaAndroidBase64(padded)
            } catch (_: NoSuchMethodError) {
                decodeViaAndroidBase64(padded)
            }
        }

        private fun decodeViaAndroidBase64(padded: String): ByteArray {
            val androidBase64 = Class.forName("android.util.Base64")
            val decode = androidBase64.getMethod("decode", String::class.java, Int::class.javaPrimitiveType)
            return decode.invoke(null, padded, 0 /* DEFAULT */) as ByteArray
        }

        private fun padBase64(s: String): String {
            val rem = s.length % 4
            return if (rem == 0) s else s + "=".repeat(4 - rem)
        }
    }
}


package io.quickauth.sdk.core

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Thin OkHttp-backed JSON client for the QuickAuth REST API.
 *
 * Features baked in:
 *  * `Authorization: Bearer <sessionToken>` — token sourced from [TokenManager], which
 *    transparently refreshes via [Config.onTokenExpiry] (or the `/v1/sdk/session` endpoint
 *    in unsafe-direct mode).
 *  * Auto-injected `Idempotency-Key` (UUIDv4) on every POST.
 *  * 3-attempt exponential-backoff retry on 5xx and connection errors.
 *  * On 401, [TokenManager.invalidate] is called and the request is retried exactly once
 *    with a freshly-minted token.
 *  * Consent check via [consentProvider] before sending — drops the request silently if
 *    [Consent.allowsRequest] returns `false`.
 *
 * The class is `open` so tests can subclass and inject a fake [OkHttpClient]; or callers can
 * pass [okHttpClient] directly via the constructor.
 */
open class ApiClient @JvmOverloads constructor(
    private val config: Config,
    /**
     * Per-request consent gate. Receives the request path and returns `true` if the
     * SDK is allowed to send.  Default: allow everything (matches existing tests + the
     * intent that auth flows should always be reachable).
     */
    private val consentProvider: (path: String) -> Boolean = { true },
    okHttpClient: OkHttpClient? = null,
    tokenManager: TokenManager? = null,
) {

    private val gson: Gson = Gson()

    private val http: OkHttpClient = okHttpClient ?: OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    internal val tokens: TokenManager = tokenManager ?: TokenManager(config)

    /**
     * POST [body] (any object Gson can serialise) to [path] and decode the JSON response into [T].
     *
     * @throws ApiException if the server returns a non-2xx after [MAX_ATTEMPTS] tries
     * @throws ConsentNotGrantedException if the consent gate refuses the call
     */
    open suspend fun <T> postJson(path: String, body: Any, clazz: Class<T>): T = withContext(Dispatchers.IO) {
        if (!consentProvider(path)) {
            throw ConsentNotGrantedException("Consent not granted for $path")
        }
        val url = "${config.apiBaseUrl.trimEnd('/')}$path"
        val payload = gson.toJson(body).toRequestBody(JSON_MEDIA)
        val idempotencyKey = UUID.randomUUID().toString()

        // First attempt with the currently-cached (or freshly-minted) token.
        val firstToken = tokens.getToken()
        val firstRequest = buildRequest(url, payload, idempotencyKey, firstToken)
        try {
            return@withContext executeWithRetry(firstRequest, clazz)
        } catch (e: ApiException) {
            if (e.code != 401) throw e
            // 401: invalidate cached token, mint a new one, try exactly once more.
            tokens.invalidate()
            val freshToken = tokens.getToken()
            val retryRequest = buildRequest(url, payload, idempotencyKey, freshToken)
            return@withContext executeWithRetry(retryRequest, clazz)
        }
    }

    private fun buildRequest(
        url: String,
        payload: okhttp3.RequestBody,
        idempotencyKey: String,
        bearerToken: String,
    ): Request = Request.Builder()
        .url(url)
        .post(payload)
        .header("Authorization", "Bearer $bearerToken")
        .header("Idempotency-Key", idempotencyKey)
        .header("User-Agent", config.userAgent)
        .header("Content-Type", "application/json")
        .header("Accept", "application/json")
        .build()

    private fun <T> executeWithRetry(request: Request, clazz: Class<T>): T {
        var lastError: Throwable? = null
        var attempt = 0
        while (attempt < MAX_ATTEMPTS) {
            attempt += 1
            try {
                http.newCall(request).execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    when {
                        response.isSuccessful -> {
                            return try {
                                gson.fromJson(raw, clazz)
                            } catch (e: JsonSyntaxException) {
                                throw ApiException(response.code, "Malformed JSON: ${e.message}", raw)
                            }
                        }
                        response.code == 401 -> {
                            // Surface immediately so the postJson wrapper can invalidate + retry once.
                            throw ApiException(401, "Unauthorized", raw)
                        }
                        response.code in 500..599 -> {
                            lastError = ApiException(response.code, "Server error", raw)
                            // fall through to retry
                        }
                        else -> {
                            // Other 4xx — don't retry, surface immediately.
                            throw ApiException(response.code, "HTTP ${response.code}", raw)
                        }
                    }
                }
            } catch (e: IOException) {
                lastError = e
            }
            // Exponential backoff: 200ms, 600ms, 1.4s …
            val sleep = (200L * (1L shl (attempt - 1))).coerceAtMost(2_000L)
            try { Thread.sleep(sleep) } catch (_: InterruptedException) { /* ignore */ }
        }
        throw lastError ?: ApiException(0, "Unknown failure", null)
    }

    companion object {
        const val MAX_ATTEMPTS = 3
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}

/** Thrown when the QuickAuth API returns a non-2xx response. */
class ApiException(
    val code: Int,
    message: String,
    val responseBody: String?,
) : RuntimeException("$message (code=$code)")

/** Thrown when the SDK refuses to send a request because the user hasn't granted consent. */
class ConsentNotGrantedException(message: String) : RuntimeException(message)

package io.quickauth.sdk

import android.content.Context
import android.util.Log
import io.quickauth.sdk.attribution.AttributionService
import io.quickauth.sdk.auth.OtpService
import io.quickauth.sdk.auth.SmsRetriever
import io.quickauth.sdk.core.ApiClient
import io.quickauth.sdk.core.Config
import io.quickauth.sdk.core.Consent
import io.quickauth.sdk.core.Storage
import io.quickauth.sdk.core.TokenManager
import io.quickauth.sdk.core.TokenProvider

/**
 * Top-level facade for the QuickAuth Android SDK.
 *
 * The SDK is initialised once per process via [init] and then accessed through the
 * [auth], [attribution] and [consent] sub-APIs.  Calling any of those properties before
 * [init] throws [IllegalStateException].
 *
 * ```kotlin
 * // Zero-backend quick start — the publishable key is safe to ship in the APK.
 * QuickAuth.init(context, publishableKey = "pk_live_…")
 *
 * // Or the extra-hardened flow, where your backend mints 10-minute session JWTs:
 * QuickAuth.init(context) {
 *     myApi.fetch("/api/quickauth-token").sessionToken
 * }
 *
 * val session = QuickAuth.auth.startOTP("+919876543210")
 * val result  = QuickAuth.auth.verifyOTP(session.sessionId, "123456")
 * ```
 */
object QuickAuth {

    private const val TAG = "QuickAuth"

    @Volatile
    private var state: State? = null

    /** True once [init] has run successfully. */
    val isInitialized: Boolean get() = state != null

    /**
     * Initialise the SDK with a token provider.  Convenience overload — equivalent to
     * `init(context, Config(onTokenExpiry = onTokenExpiry))`.
     *
     * The [onTokenExpiry] lambda is called the first time the SDK needs a bearer token and
     * again ~30s before each token expires.  It must return a `sessionToken` (10-min JWT)
     * minted by your backend via QuickAuth's `POST /v1/sdk/session` endpoint.
     */
    @JvmStatic
    fun init(context: Context, onTokenExpiry: TokenProvider) {
        init(context, Config(onTokenExpiry = onTokenExpiry))
    }

    /**
     * Initialise the SDK with a publishable key — the zero-backend quick start.  Equivalent
     * to `init(context, Config(publishableKey = publishableKey))`.
     *
     * The `pk_live_…` / `pk_test_…` key is designed to ship inside your APK: the backend
     * scopes it to OTP initiate/verify, app-locks it to the package names you register, and
     * rate-limits it.  No token endpoint is required.
     */
    @JvmStatic
    fun init(context: Context, publishableKey: String) {
        init(context, Config(publishableKey = publishableKey))
    }

    /**
     * Initialise the SDK with a fully-formed [Config].  Use this overload when you need to
     * override the API base URL, supply an [Config.initialToken], or enable the unsafe
     * direct-client-credentials escape hatch.
     *
     * @throws IllegalArgumentException if neither or both auth modes are configured — see
     *         [Config.publishableKey] and [Config.onTokenExpiry].
     */
    @JvmStatic
    fun init(context: Context, config: Config) {
        if (config.isUnsafeDirectMode) {
            Log.w(
                TAG,
                "⚠️ UNSAFE mode: client_secret embedded; for trusted-enterprise only",
            )
        }
        val appCtx = context.applicationContext
        val storage = Storage(appCtx)
        storage.apiBaseUrl = config.apiBaseUrl

        val consentImpl = Consent(storage)
        val tokenManager = TokenManager(config)
        val apiClient = ApiClient(
            config = config,
            consentProvider = { path -> consentImpl.allowsRequest(path) },
            tokenManager = tokenManager,
            applicationId = safeApplicationId(appCtx),
        )
        val smsRetriever = SmsRetriever(appCtx)
        val otpService = OtpService(apiClient, smsRetriever, storage) { config }
        val attributionService = AttributionService(appCtx, apiClient)

        state = State(
            context = appCtx,
            config = config,
            storage = storage,
            consent = consentImpl,
            api = apiClient,
            auth = otpService,
            attribution = attributionService,
        )
    }

    /** OTP / WhatsApp login surface. Throws if the SDK is not initialised. */
    val auth: OtpService get() = require().auth

    /** Marketing attribution surface. */
    val attribution: AttributionService get() = require().attribution

    /** DPDP/GDPR consent gate. Until set to `true`, the SDK refuses to send PII. */
    val consent: Consent get() = require().consent

    /**
     * Compute the 11-character SMS Retriever app-hash for the currently-installed signing
     * certificate.  This is a developer-convenience wrapper that you can call from your app
     * during testing — print the result and embed it in your OTP message templates.
     */
    @JvmStatic
    fun smsRetrieverAppHash(context: Context): String =
        SmsRetriever.computeAppHashForInstalledApp(context)

    /**
     * Read the host app's package name for the `X-QuickAuth-Package` app-lock header.
     *
     * Best effort by design: a mocked or otherwise exotic [Context] can throw here, and no
     * app-identity header is worth aborting SDK init over. Returning null just omits the
     * header — which the backend tolerates only while app-lock is disabled, since app-lock
     * fails closed once enabled with no registered apps.
     */
    private fun safeApplicationId(context: Context): String? =
        runCatching { context.packageName }.getOrNull()?.takeIf { it.isNotBlank() }

    /** For tests. */
    internal fun resetForTesting() {
        state = null
    }

    private fun require(): State =
        state ?: throw IllegalStateException(
            "QuickAuth not initialised. Call QuickAuth.init(context) { fetchSessionTokenFromMyBackend() } in Application.onCreate.",
        )

    internal data class State(
        val context: Context,
        val config: Config,
        val storage: Storage,
        val consent: Consent,
        val api: ApiClient,
        val auth: OtpService,
        val attribution: AttributionService,
    )
}

/**
 * The OTP delivery channel requested when starting a session.
 *
 *  * [AUTO]      — backend picks the cheapest available channel (typically WhatsApp first).
 *  * [SMS]       — force SMS (uses DLT-registered template in India).
 *  * [WHATSAPP]  — force WhatsApp (template + utility-conversation pricing applies).
 */
enum class OtpChannel { AUTO, SMS, WHATSAPP }

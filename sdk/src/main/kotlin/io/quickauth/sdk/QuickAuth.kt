package io.quickauth.sdk

import android.content.Context
import android.util.Log
import io.quickauth.sdk.attribution.AttributionService
import io.quickauth.sdk.auth.AuthEventHandler
import io.quickauth.sdk.auth.OtpService
import io.quickauth.sdk.auth.SmsRetriever
import io.quickauth.sdk.auth.WhatsAppLogin
import io.quickauth.sdk.auth.WhatsAppOtpRetriever
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
 * QuickAuth.init(context) {
 *     // Your backend exposes a server-to-server endpoint that mints a 10-min QuickAuth JWT
 *     myApi.fetch("/api/quickauth-token").sessionToken
 * }
 * QuickAuth.setAuthEventHandler { event -> /* drive your UI */ }
 * QuickAuth.auth.initiate("+919876543210", autoSubmit = true)
 * ```
 */
object QuickAuth {

    private const val TAG = "QuickAuth"

    @Volatile
    private var state: State? = null

    /** True once [init] has run successfully. */
    @JvmStatic
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
     * Initialise the SDK with a fully-formed [Config].  Use this overload when you need to
     * override the API base URL, supply an [Config.initialToken], or enable the unsafe
     * direct-client-credentials escape hatch.
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

        // The event handler is the one part of the config that can change after init, so the
        // OtpService reads it through a holder rather than closing over the value. Everything
        // else — base URL, token provider, timeouts — is captured once by ApiClient, which is
        // why setAuthEventHandler is the only mutator and not a general-purpose setConfig.
        val holder = ConfigHolder(config)

        val consentImpl = Consent(storage)
        val tokenManager = TokenManager(config)
        val apiClient = ApiClient(
            config = config,
            consentProvider = { path -> consentImpl.allowsRequest(path) },
            tokenManager = tokenManager,
        )
        val smsRetriever = SmsRetriever(appCtx)
        val whatsAppOtp = WhatsAppOtpRetriever(appCtx)
        val otpService = OtpService(
            api = apiClient,
            smsRetriever = smsRetriever,
            storage = storage,
            whatsApp = whatsAppOtp,
        ) { holder.config }
        val attributionService = AttributionService(appCtx, apiClient)

        state = State(
            context = appCtx,
            configHolder = holder,
            storage = storage,
            consent = consentImpl,
            api = apiClient,
            tokenManager = tokenManager,
            auth = otpService,
            attribution = attributionService,
            whatsapp = WhatsAppLogin(appCtx),
        )
    }

    /** OTP / WhatsApp login surface. Throws if the SDK is not initialised. */
    @JvmStatic
    val auth: OtpService get() = require().auth

    /** Marketing attribution surface. */
    @JvmStatic
    val attribution: AttributionService get() = require().attribution

    /** DPDP/GDPR consent gate. Until set to `true`, the SDK refuses to send PII. */
    @JvmStatic
    val consent: Consent get() = require().consent

    /** The active configuration, including whatever handler [setAuthEventHandler] last set. */
    @JvmStatic
    val config: Config get() = require().configHolder.config

    /** Bearer-token cache — exposed for tests and advanced flows. */
    @JvmStatic
    val tokenManager: TokenManager get() = require().tokenManager

    /**
     * Direct "login with WhatsApp" deep-link helper. Most apps should prefer
     * [OtpService.startWhatsAppLogin], which takes the Activity to launch from.
     *
     * This is the deep-link flow, not WhatsApp OTP auto-read — that needs no wiring at all.
     */
    @JvmStatic
    val whatsapp: WhatsAppLogin get() = require().whatsapp

    /**
     * Replace the auth event handler after [init].
     *
     * Useful when the handler belongs to a screen rather than to the application: an Activity
     * can install one in `onCreate` and drop it in `onDestroy` without leaking itself into a
     * process-lifetime config. Pass `null` to detach.
     *
     * Only the handler is mutable. The rest of the [Config] is captured by [ApiClient] and
     * [TokenManager] at init time, and pretending otherwise would give merchants a setter whose
     * effect depended on which subsystem happened to read the field again.
     */
    @JvmStatic
    fun setAuthEventHandler(handler: AuthEventHandler?) {
        val holder = require().configHolder
        holder.config = holder.config.copy(onAuthEvent = handler)
    }

    /**
     * Tear the SDK down: end any in-flight auth attempt, stop auto-read, and return
     * [isInitialized] to `false`.
     *
     * Matches the Flutter SDK's `QuickAuth.reset()`. This is the process-level teardown, used
     * by tests and by apps that re-initialise with different credentials. To end a *login
     * attempt* — and optionally forget the OneTap device trust — use
     * `QuickAuth.auth.reset(forgetDevice = true)` instead; that keeps the SDK usable.
     *
     * The persisted device token survives, so a re-[init] still gets OneTap.
     */
    @JvmStatic
    fun reset() {
        val current = state ?: return
        current.auth.reset()
        state = null
    }

    /**
     * Compute the 11-character SMS Retriever app-hash for the currently-installed signing
     * certificate.  This is a developer-convenience wrapper that you can call from your app
     * during testing — print the result and embed it in your OTP message templates.
     *
     * An app whose signing key has been rotated has more than one valid hash; see
     * [SmsRetriever.computeAppHashesForInstalledApp].
     */
    @JvmStatic
    fun smsRetrieverAppHash(context: Context): String =
        SmsRetriever.computeAppHashForInstalledApp(context)

    /** For tests. */
    internal fun resetForTesting() {
        state = null
    }

    private fun require(): State =
        state ?: throw IllegalStateException(
            "QuickAuth not initialised. Call QuickAuth.init(context) { fetchSessionTokenFromMyBackend() } in Application.onCreate.",
        )

    /** Mutable cell for the one config field that may change after init. */
    internal class ConfigHolder(@Volatile var config: Config)

    internal class State(
        val context: Context,
        val configHolder: ConfigHolder,
        val storage: Storage,
        val consent: Consent,
        val api: ApiClient,
        val tokenManager: TokenManager,
        val auth: OtpService,
        val attribution: AttributionService,
        val whatsapp: WhatsAppLogin,
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

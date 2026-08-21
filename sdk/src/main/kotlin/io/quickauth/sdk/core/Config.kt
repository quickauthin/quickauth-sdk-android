package io.quickauth.sdk.core

import io.quickauth.sdk.auth.AuthEventHandler

/**
 * Suspend lambda that returns a fresh ephemeral SDK session JWT.
 *
 * Customers expose a server-to-server endpoint (typically `GET /api/quickauth-token`) that
 * mints a 10-minute JWT by calling QuickAuth's `POST /v1/sdk/session` with their secret
 * `client_id` + `client_secret`.  The Android SDK calls this lambda whenever it needs a
 * new bearer token (first call, ~30s before expiry, after a 401).
 */
public typealias TokenProvider = suspend () -> String

/**
 * Immutable runtime configuration captured at [io.quickauth.sdk.QuickAuth.init] time.
 *
 * Two auth modes are supported and **exactly one** must be configured:
 *
 *  * **Publishable key** ([publishableKey]) — zero-backend quick start.  The key ships inside
 *    the APK and travels as `X-QuickAuth-Key`; no token endpoint is required.
 *  * **Session token** ([onTokenExpiry]) — the extra-hardened flow.  The customer's backend
 *    mints ephemeral JWTs, so the SDK never sees the long-lived `client_secret`.  This matches
 *    the Twilio Verify pattern used by our web + iOS SDKs.
 *
 * @property apiBaseUrl base URL for `*.quickauth.in` (no trailing slash).
 * @property onTokenExpiry suspend lambda invoked to fetch a fresh `sessionToken`.  Called
 *                          on first request and ~30s before the previous token expires.
 *                          Mutually exclusive with [publishableKey].
 * @property publishableKey `pk_live_…` / `pk_test_…` credential for the zero-backend mode.
 *                           Mutually exclusive with [onTokenExpiry].
 * @property initialToken optional bootstrap token — saves a network round-trip on the very
 *                         first call.  Must be a valid JWT minted within the last 10 minutes.
 *                         Ignored in publishable-key mode.
 * @property unsafeDirectClientId UNSAFE escape hatch — embeds `client_id` directly in the APK.
 *                                 Trusted-enterprise builds only; logs a warning on init.
 * @property unsafeDirectClientSecret pair to [unsafeDirectClientId]; both must be set together.
 * @property userAgent custom User-Agent appended to outgoing requests.
 */
public data class Config(
    val apiBaseUrl: String = DEFAULT_API_BASE_URL,
    val onTokenExpiry: TokenProvider? = null,
    /**
     * Publishable key (`pk_live_…` / `pk_test_…`) — the in-app-safe credential for the
     * zero-backend auth mode.
     *
     * Unlike the client **secret**, a publishable key is *designed* to ship inside the app:
     * on the backend it is scoped to OTP initiate/verify only, app-locked to the package
     * names you register, and rate-limited.  When set, the SDK sends it as `X-QuickAuth-Key`
     * on the `/v1/sdk/auth` endpoints and never asks for a session token.
     */
    val publishableKey: String? = null,
    val initialToken: String? = null,
    val unsafeDirectClientId: String? = null,
    val unsafeDirectClientSecret: String? = null,
    val userAgent: String = "quickauth-sdk-android/$SDK_VERSION",
    /**
     * Headless auth event handler. Invoked on the main thread with a typed
     * [io.quickauth.sdk.auth.AuthEvent] as the auth lifecycle progresses
     * (OTP sent, verified, failed, error). One handler per Config; assign
     * a new lambda to replace.
     */
    val onAuthEvent: AuthEventHandler? = null,
) {
    init {
        require(apiBaseUrl.startsWith("http")) { "apiBaseUrl must be an absolute URL" }
        require(
            (unsafeDirectClientId == null) == (unsafeDirectClientSecret == null),
        ) { "unsafeDirectClientId and unsafeDirectClientSecret must both be set or both be null" }

        // Guard the auth mode here rather than at the first request: a misconfigured SDK
        // would otherwise look healthy until the user taps "Send OTP" and gets a 401.
        val hasPublishableKey = !publishableKey.isNullOrBlank()
        val hasSessionMode =
            onTokenExpiry != null || (unsafeDirectClientId != null && unsafeDirectClientSecret != null)
        require(hasPublishableKey || hasSessionMode) {
            "QuickAuth needs an auth mode: pass publishableKey (recommended, zero-backend) " +
                "or onTokenExpiry (server-minted session tokens)."
        }
        require(!(hasPublishableKey && hasSessionMode)) {
            "Pass either publishableKey or onTokenExpiry — not both."
        }
    }

    /** True when the unsafe direct-client-credentials escape hatch is configured. */
    val isUnsafeDirectMode: Boolean
        get() = unsafeDirectClientId != null && unsafeDirectClientSecret != null

    /** True when the SDK authenticates with a publishable key instead of a session token. */
    val isPublishableKeyMode: Boolean
        get() = !publishableKey.isNullOrBlank()

    public companion object {
        public const val DEFAULT_API_BASE_URL: String = "https://api.quickauth.in"
        public const val SDK_VERSION: String = "1.1.0"
    }
}

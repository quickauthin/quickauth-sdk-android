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
 * The default integration mode uses ephemeral session JWTs minted by the customer's backend —
 * the SDK never sees the long-lived `client_secret`.  This matches the Twilio Verify pattern
 * used by our web + iOS SDKs.
 *
 * @property apiBaseUrl base URL for `*.quickauth.in` (no trailing slash).
 * @property onTokenExpiry suspend lambda invoked to fetch a fresh `sessionToken`.  Called
 *                          on first request and ~30s before the previous token expires.
 * @property initialToken optional bootstrap token — saves a network round-trip on the very
 *                         first call.  Must be a valid JWT minted within the last 10 minutes.
 * @property unsafeDirectClientId UNSAFE escape hatch — embeds `client_id` directly in the APK.
 *                                 Trusted-enterprise builds only; logs a warning on init.
 * @property unsafeDirectClientSecret pair to [unsafeDirectClientId]; both must be set together.
 * @property userAgent custom User-Agent appended to outgoing requests.
 */
public data class Config(
    val apiBaseUrl: String = DEFAULT_API_BASE_URL,
    val onTokenExpiry: TokenProvider,
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
    }

    /** True when the unsafe direct-client-credentials escape hatch is configured. */
    val isUnsafeDirectMode: Boolean
        get() = unsafeDirectClientId != null && unsafeDirectClientSecret != null

    public companion object {
        public const val DEFAULT_API_BASE_URL: String = "https://api.quickauth.in"
        public const val SDK_VERSION: String = "1.1.0"
    }
}

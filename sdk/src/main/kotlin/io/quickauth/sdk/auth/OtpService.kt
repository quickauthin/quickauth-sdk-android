package io.quickauth.sdk.auth

import io.quickauth.sdk.OtpChannel
import io.quickauth.sdk.core.ApiClient
import kotlinx.coroutines.flow.Flow

/**
 * Coroutine-friendly OTP service.
 *
 * Wraps the two REST endpoints that customer apps interact with:
 *
 *  * `POST /v1/sdk/auth/initiate`  → starts a session and triggers SMS / WhatsApp delivery
 *  * `POST /v1/sdk/auth/verify`    → exchanges a 6-digit code for a short-lived JWT
 *
 * Plus a one-call helper [observeOTP] that returns a flow of inbound OTP codes via
 * [SmsRetriever] (zero-permission Google Play API).
 */
class OtpService internal constructor(
    private val api: ApiClient,
    private val smsRetriever: SmsRetriever,
) {

    /**
     * Start an OTP session.
     *
     * @param phone E.164-formatted phone (e.g. `+919876543210`).
     * @param channel preferred delivery channel — see [OtpChannel].
     * @return a [Session] object holding the opaque `sessionId` returned by the backend.
     * @throws IllegalArgumentException if [phone] doesn't look like E.164.
     */
    suspend fun startOTP(phone: String, channel: OtpChannel = OtpChannel.AUTO): Session {
        require(isValidE164(phone)) {
            "phone must be in E.164 format (e.g. +919876543210), got '$phone'"
        }
        val resp = api.postJson(
            path = "/v1/sdk/auth/initiate",
            body = mapOf(
                "phone" to phone,
                "channel" to channel.name.lowercase(),
            ),
            clazz = InitiateResponse::class.java,
        )
        return Session(sessionId = resp.sessionId, expiresIn = resp.expiresIn)
    }

    /**
     * Verify the 6-digit code the user typed.
     *
     * @param sessionId the value returned from [startOTP].
     * @param code 4-8 digit numeric OTP.
     * @return [VerifyResult] holding the short-lived JWT — pass it to **your** backend so
     *         your backend can call `/v1/sdk/auth/introspect` to validate it.
     */
    suspend fun verifyOTP(sessionId: String, code: String): VerifyResult {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(code.matches(CODE_REGEX)) { "code must be 4-8 digits, got '$code'" }
        val resp = api.postJson(
            path = "/v1/sdk/auth/verify",
            body = mapOf(
                "sessionId" to sessionId,
                "code" to code,
            ),
            clazz = VerifyResponse::class.java,
        )
        return VerifyResult(jwt = resp.jwt, expiresIn = resp.expiresIn)
    }

    /**
     * Subscribe to inbound OTP codes.  Returns a cold [Flow] — collect it from a coroutine
     * scope tied to your screen, e.g. `lifecycleScope.launch { … }`.
     *
     * Behind the scenes this:
     *   1. Calls `SmsRetrieverClient.startSmsRetriever()` which arms a 5-minute window
     *   2. Registers a [android.content.BroadcastReceiver] for the `SMS_RETRIEVED` action
     *   3. Parses the 6-digit code out of the message body
     *   4. Falls back to the User Consent API if the sender hash isn't ours
     */
    fun observeOTP(): Flow<String> = smsRetriever.observe()

    /**
     * Convenience overload that simply forwards to [observeOTP] callbacks-style.
     */
    fun observeOTP(onCode: (String) -> Unit): SmsRetriever.Subscription =
        smsRetriever.observe(onCode)

    /**
     * Launch the WhatsApp deep-link login flow.  The user is sent to `wa.me/<businessNumber>`
     * with a pre-filled login token; once they reply, our backend marks the session verified
     * and your app receives the JWT via App Link return-to-app.
     *
     * Implementation lives in [WhatsAppLogin].
     */
    fun startWhatsAppLogin(activity: android.app.Activity, businessNumber: String) {
        WhatsAppLogin(activity).launch(businessNumber)
    }

    // --- wire models --------------------------------------------------------------------

    /** Response payload from `/v1/sdk/auth/initiate`. Internal so tests can construct it. */
    internal data class InitiateResponse(val sessionId: String, val expiresIn: Int)

    /** Response payload from `/v1/sdk/auth/verify`. */
    internal data class VerifyResponse(val jwt: String, val expiresIn: Int)

    /** Public session handle returned to callers of [startOTP]. */
    data class Session(val sessionId: String, val expiresIn: Int)

    /** Public verify result returned to callers of [verifyOTP]. */
    data class VerifyResult(val jwt: String, val expiresIn: Int)

    companion object {
        private val CODE_REGEX = Regex("^\\d{4,8}$")
        private val E164_REGEX = Regex("^\\+[1-9]\\d{6,14}$")

        /** Public so it can be reused by UI components for live validation. */
        fun isValidE164(phone: String): Boolean = E164_REGEX.matches(phone)
    }
}


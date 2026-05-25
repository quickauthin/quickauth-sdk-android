package io.quickauth.sdk.auth

import android.os.Handler
import android.os.Looper
import io.quickauth.sdk.OtpChannel
import io.quickauth.sdk.core.ApiClient
import io.quickauth.sdk.core.Config
import io.quickauth.sdk.core.Storage
import kotlinx.coroutines.flow.Flow

/**
 * Headless auth state machine exposed at `QuickAuth.auth`.
 *
 * Public API:
 *
 * ```kotlin
 * QuickAuth.auth.initiate("+919876543210")
 * QuickAuth.auth.submitOtp("123456")
 * QuickAuth.auth.reset(forgetDevice = true)
 * ```
 *
 * All outcomes flow via [Config.onAuthEvent]. The suspend methods throw
 * only when the request couldn't be dispatched (validation, transport).
 *
 * State machine (matches web + iOS):
 *
 * ```
 *   idle ──initiate()──► sending ──OTP_SENT───► awaitingOtp ──submitOtp()──► verifying
 *                              └──VERIFIED────► verified                              │
 *                              └──error───────► failed                                │
 *   verifying ──VERIFIED────► verified                                                │
 *   verifying ──OTP_FAILED──► awaitingOtp ◄────────────────────────────────────────┘
 *   any state ──reset()─────► idle
 * ```
 */
class OtpService internal constructor(
    private val api: ApiClient,
    private val smsRetriever: SmsRetriever,
    private val storage: Storage,
    private val configProvider: () -> Config,
) {

    // -- State machine ------------------------------------------------------

    private sealed class State {
        abstract val attemptId: Int?
        object Idle : State() { override val attemptId: Int? = null }
        data class Sending(override val attemptId: Int) : State()
        data class AwaitingOtp(override val attemptId: Int, val sessionId: String) : State()
        data class Verifying(override val attemptId: Int, val sessionId: String) : State()
        data class Verified(override val attemptId: Int, val requestId: String) : State()
        data class Failed(override val attemptId: Int) : State()
    }

    private val stateLock = Any()

    @Volatile private var state: State = State.Idle
    @Volatile private var attemptCounter: Int = 0

    private val mainHandler = Handler(Looper.getMainLooper())

    // -- Headless API -------------------------------------------------------

    /**
     * Begin an auth attempt. Emits [AuthEvent.OtpSent] (OTP delivered) or
     * [AuthEvent.Verified] (OneTap fired) via [Config.onAuthEvent]. Throws
     * only on validation / transport failure.
     */
    suspend fun initiate(phone: String, channel: OtpChannel = OtpChannel.AUTO) {
        require(isValidE164(phone)) {
            "phone must be in E.164 format (e.g. +919876543210), got '$phone'"
        }
        val attemptId = nextAttempt()
        setState(State.Sending(attemptId))

        val body = mutableMapOf<String, Any>(
            "phone" to phone,
            "channel" to channel.name.lowercase(),
        )
        storage.deviceToken?.let { body["deviceToken"] = it }

        val res: InitiateResponse = try {
            api.postJson("/v1/sdk/auth/initiate", body, InitiateResponse::class.java)
        } catch (e: Throwable) {
            if (currentAttempt() == attemptId) {
                setState(State.Failed(attemptId))
                emit(AuthEvent.Error(code = classify(e), message = e.message ?: "Request failed"))
            }
            throw e
        }

        if (currentAttempt() != attemptId) return

        if (!res.deviceToken.isNullOrBlank()) {
            storage.deviceToken = res.deviceToken
        }

        if (res.state == "VERIFIED") {
            setState(State.Verified(attemptId, res.sessionId))
            emit(AuthEvent.Verified(requestId = res.sessionId, message = null))
            return
        }

        setState(State.AwaitingOtp(attemptId, res.sessionId))
        emit(AuthEvent.OtpSent(sessionId = res.sessionId, channel = channel, expiresIn = res.expiresIn))
    }

    /**
     * Submit the user-entered OTP. Valid only after [AuthEvent.OtpSent].
     * Emits [AuthEvent.Verified] on success or [AuthEvent.OtpFailed] on
     * wrong code (state stays in `awaitingOtp` for retry).
     */
    suspend fun submitOtp(code: String) {
        require(code.matches(CODE_REGEX)) { "code must be 4-8 digits, got '$code'" }
        val (attemptId, sessionId) = synchronized(stateLock) {
            val current = state
            check(current is State.AwaitingOtp) {
                "submitOtp called in state ${current::class.simpleName} — must follow an OtpSent event"
            }
            current.attemptId to current.sessionId
        }
        setState(State.Verifying(attemptId, sessionId))

        val body = mutableMapOf<String, Any>(
            "sessionId" to sessionId,
            "code" to code,
        )
        storage.deviceToken?.let { body["deviceToken"] = it }

        val res: VerifyResponse = try {
            api.postJson("/v1/sdk/auth/verify", body, VerifyResponse::class.java)
        } catch (e: Throwable) {
            if (currentAttempt() == attemptId) {
                setState(State.Failed(attemptId))
                emit(AuthEvent.Error(code = classify(e), message = e.message ?: "Request failed"))
            }
            throw e
        }

        if (currentAttempt() != attemptId) return

        if (res.state == "VERIFIED" || (res.state == null && res.verified)) {
            setState(State.Verified(attemptId, res.requestId))
            emit(AuthEvent.Verified(requestId = res.requestId, message = res.message))
            return
        }

        // OTP_FAILED — stay in awaitingOtp so the user can retry.
        setState(State.AwaitingOtp(attemptId, sessionId))
        emit(AuthEvent.OtpFailed(message = res.message))
    }

    /**
     * Reset the state machine. Pass [forgetDevice] = `true` on user-
     * initiated sign-out to also drop the persistent device token, making
     * the next [initiate] act like a brand-new install (no OneTap).
     */
    fun reset(forgetDevice: Boolean = false) {
        synchronized(stateLock) {
            state = State.Idle
            attemptCounter++   // invalidate any in-flight attempt
        }
        if (forgetDevice) storage.deviceToken = null
    }

    /** Manually push an auto-read code into the event stream. */
    fun publishAutoReadCode(code: String) {
        emit(AuthEvent.OtpAutoRead(code))
    }

    // -- Auxiliary surface (unchanged from prior versions) -------------------

    /**
     * Cold [Flow] of inbound OTP codes via Google SMS Retriever. Codes
     * collected here are also surfaced as [AuthEvent.OtpAutoRead] events.
     */
    fun observeOTP(): Flow<String> = smsRetriever.observe()

    /** Callback-style overload. */
    fun observeOTP(onCode: (String) -> Unit): SmsRetriever.Subscription =
        smsRetriever.observe { code ->
            onCode(code)
            publishAutoReadCode(code)
        }

    /** Launch the WhatsApp deep-link login flow. */
    fun startWhatsAppLogin(activity: android.app.Activity, businessNumber: String) {
        WhatsAppLogin(activity).launch(businessNumber)
    }

    // -- Internals ----------------------------------------------------------

    private fun nextAttempt(): Int = synchronized(stateLock) {
        attemptCounter += 1
        attemptCounter
    }

    private fun currentAttempt(): Int? = synchronized(stateLock) { state.attemptId }

    private fun setState(newState: State) = synchronized(stateLock) { state = newState }

    private fun emit(event: AuthEvent) {
        val handler = configProvider().onAuthEvent ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            safeInvoke(handler, event)
        } else {
            mainHandler.post { safeInvoke(handler, event) }
        }
    }

    private fun safeInvoke(handler: AuthEventHandler, event: AuthEvent) {
        try {
            handler(event)
        } catch (t: Throwable) {
            android.util.Log.e("QuickAuth", "onAuthEvent handler threw", t)
        }
    }

    private fun classify(e: Throwable): String = when (e) {
        is io.quickauth.sdk.core.ApiException -> when {
            e.code == 429 -> "RATE_LIMITED"
            e.code >= 500 -> "SERVER_ERROR"
            e.code >= 400 -> "CLIENT_ERROR"
            else -> "HTTP_ERROR"
        }
        else -> e::class.simpleName ?: "UNKNOWN_ERROR"
    }

    // -- Wire DTOs ----------------------------------------------------------

    /** Response from `/v1/sdk/auth/initiate`. */
    internal data class InitiateResponse(
        val state: String?,
        val sessionId: String,
        val expiresIn: Int,
        val deviceToken: String?,
    )

    /** Response from `/v1/sdk/auth/verify`. */
    internal data class VerifyResponse(
        val state: String?,
        val verified: Boolean,
        val requestId: String,
        val message: String,
    )

    companion object {
        private val CODE_REGEX = Regex("^\\d{4,8}$")
        private val E164_REGEX = Regex("^\\+[1-9]\\d{6,14}$")

        fun isValidE164(phone: String): Boolean = E164_REGEX.matches(phone)
    }
}

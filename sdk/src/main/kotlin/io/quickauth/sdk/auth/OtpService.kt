package io.quickauth.sdk.auth

import android.os.Handler
import android.os.Looper
import io.quickauth.sdk.OtpChannel
import io.quickauth.sdk.core.ApiClient
import io.quickauth.sdk.core.Config
import io.quickauth.sdk.core.Storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
    /**
     * @param autoSubmit verify an auto-read code without the caller doing anything. Off by
     *                   default: an app that already submits from its own observeOTP callback
     *                   would otherwise submit twice, and the second fails against a code the
     *                   server has consumed — surfacing as an error after a success.
     */
    suspend fun initiate(
        phone: String,
        channel: OtpChannel = OtpChannel.AUTO,
        autoSubmit: Boolean = false,
    ) {
        require(isValidE164(phone)) {
            "phone must be in E.164 format (e.g. +919876543210), got '$phone'"
        }
        val attemptId = nextAttempt()
        setState(State.Sending(attemptId))

        // Drop any WhatsApp code held from an earlier attempt, then tell WhatsApp a new one is
        // coming. Both before the request, not after: the receiver holds a code so a zero-tap
        // arriving before the app was running is not lost, and delivering that against a
        // restarted request fails verification for reasons the user cannot see — while a
        // handshake that lands after the template is too late for the message already in
        // flight.
        WhatsAppOtpReceiver.clearPending()
        WhatsAppOtpHandshake.send(smsRetriever.context)

        activePhone = phone
        activeChannel = channel
        this.autoSubmit = autoSubmit
        autoSubmitted = false
        listenForAutoRead()

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
        stopAutoRead()
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

    /**
     * Codes read automatically, from whichever channel delivered them.
     *
     * Merges the two, because they are two delivery mechanisms for one thing and a caller
     * should not have to know which arrived. An OTP sent over SMS is parsed out of the message
     * body by SmsRetriever; a WhatsApp one-tap or zero-tap code is broadcast to the app by
     * WhatsApp and arrives already extracted. Listening to only one means a merchant on AUTO
     * gets auto-read for some users and not others, with nothing to explain the difference.
     */
    fun observeOTP(onCode: (String) -> Unit): SmsRetriever.Subscription {
        val deliver: (String) -> Unit = { code ->
            onCode(code)
            publishAutoReadCode(code)
        }
        WhatsAppOtpReceiver.setListener(deliver)
        val smsSub = smsRetriever.observe(deliver)
        // Detaching the WhatsApp listener alongside the SMS one, so a caller that stops
        // listening does not leave the receiver holding a reference to their callback.
        return SmsRetriever.Subscription {
            WhatsAppOtpReceiver.setListener(null)
            smsSub.cancel()
        }
    }

    /** Launch the WhatsApp deep-link login flow. */
    fun startWhatsAppLogin(activity: android.app.Activity, businessNumber: String) {
        WhatsAppLogin(activity).launch(businessNumber)
    }

    // -- Auto-read --------------------------------------------------------------

    /**
     * The phone and options of the live attempt, so [resendOtp] needs no arguments.
     *
     * A merchant should not have to hold the number themselves to resend to it — they already
     * gave it to us, and asking again is an opportunity to pass a different one, which would
     * start a second transaction and leave the user holding two codes.
     */
    @Volatile private var activePhone: String? = null
    @Volatile private var activeChannel: OtpChannel = OtpChannel.AUTO

    @Volatile private var autoSubmit = false

    /**
     * One auto-submit per attempt. Both sources can deliver — a merchant on AUTO may get the
     * SMS and the WhatsApp copy — and submitting the second would verify a code the server has
     * already consumed, surfacing as a spurious failure after a success.
     */
    @Volatile private var autoSubmitted = false

    private var autoReadSub: SmsRetriever.Subscription? = null

    /**
     * Where an auto-submitted verify runs.
     *
     * A code arrives on a binder thread with no coroutine context, and submitOtp is suspending,
     * so it needs a scope of its own. SupervisorJob so one failed verify cannot cancel the
     * scope and silently disable auto-submit for the rest of the process.
     */
    private val autoReadScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Subscribe on the caller's behalf, so a code is delivered whether or not they listen.
     *
     * Without this, auto-read only worked for a caller who happened to call observeOTP: the
     * WhatsApp receiver holds a code until something listens, so with nobody subscribed it was
     * received, held, and never delivered — and autoSubmit would do nothing at all, in exactly
     * the case where the caller was told they need not listen.
     *
     * Idempotent across attempts: a resend must not stack subscriptions, and the old one is
     * dropped first so a code from a previous attempt cannot arrive on it.
     */
    private fun listenForAutoRead() {
        autoReadSub?.cancel()
        autoReadSub = observeOTP { code -> maybeAutoSubmit(code) }
    }

    private fun maybeAutoSubmit(code: String) {
        if (!autoSubmit || autoSubmitted) return
        autoSubmitted = true
        autoReadScope.launch {
            try {
                submitOtp(code)
            } catch (t: Throwable) {
                // Already surfaced through the event stream; a throw here has nowhere to go.
            }
        }
    }

    /**
     * Send the code again, to the number the current attempt is already for.
     *
     * Within the merchant's expiry window the server returns the SAME code and pushes the
     * expiry forward, so a user who missed the first message gets that message again rather
     * than a second code to choose between. Past the window it issues a fresh one.
     *
     * Takes no phone number deliberately: the merchant already gave us one, and asking again
     * is an opportunity to pass a different one by accident — which would start a separate
     * transaction and leave the user holding two codes, only one of which works.
     *
     * Carries the original attempt's channel and autoSubmit setting, so a resend behaves like
     * the request it repeats rather than silently reverting to defaults. It also re-sends the
     * WhatsApp handshake, which Meta expires after ten minutes — a user who waits before
     * tapping resend would otherwise get a message their app can no longer auto-read.
     *
     * @throws IllegalStateException if there is no attempt to resend. That is a programming
     *         error rather than a runtime condition: a resend button should only exist once a
     *         code has been sent.
     */
    suspend fun resendOtp() {
        val phone = activePhone
            ?: throw IllegalStateException("resendOtp: nothing to resend — call initiate() first.")
        initiate(phone, activeChannel, autoSubmit)
    }

    /** Stop listening for auto-read codes. Safe to call twice. */
    private fun stopAutoRead() {
        autoReadSub?.cancel()
        autoReadSub = null
        autoSubmit = false
        // Nothing left to resend to: a reset ends the attempt, and resending afterwards would
        // message someone who is no longer mid-login.
        activePhone = null
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

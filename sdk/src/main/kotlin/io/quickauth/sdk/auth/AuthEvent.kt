package io.quickauth.sdk.auth

import io.quickauth.sdk.OtpChannel

/**
 * Typed lifecycle events emitted by the headless auth state machine.
 *
 * Subscribe once via [io.quickauth.sdk.core.Config.onAuthEvent] and switch
 * on the subtype to drive UI:
 *
 * ```
 * Config(
 *     onTokenExpiry = ::fetchToken,
 *     onAuthEvent = { event ->
 *         when (event) {
 *             is AuthEvent.OtpSent     -> showOtpInput()
 *             is AuthEvent.OtpAutoRead -> prefillInput(event.code)
 *             is AuthEvent.Verified    -> finishLogin(event.requestId)
 *             is AuthEvent.OtpFailed   -> showError(event.message)
 *             is AuthEvent.Error       -> showError(event.message)
 *         }
 *     }
 * )
 * ```
 *
 * Each [io.quickauth.sdk.auth.OtpService.initiate] call produces at most
 * one terminal event ([Verified] / [OtpFailed] / [Error]) for that attempt.
 * Calling [io.quickauth.sdk.auth.OtpService.initiate] again resets the
 * state machine.
 */
sealed class AuthEvent {

    /**
     * Backend dispatched an OTP. Render the input UI; the user will type a
     * code and the merchant will call [io.quickauth.sdk.auth.OtpService.submitOtp].
     */
    data class OtpSent(
        val sessionId: String,
        val channel: OtpChannel,
        val expiresIn: Int,
    ) : AuthEvent()

    /**
     * SMS auto-read fired (via Google SMS Retriever). The SDK does NOT
     * auto-submit — the merchant decides whether to forward to `submitOtp`.
     */
    data class OtpAutoRead(val code: String) : AuthEvent()

    /**
     * User is authenticated. Covers both fresh OTP success and silent
     * device-trust re-auth (no OTP was sent). Forward [requestId] to the
     * merchant backend for server-to-server confirmation.
     */
    data class Verified(
        val requestId: String,
        val message: String? = null,
    ) : AuthEvent()

    /**
     * Submitted code was rejected. SDK remains in awaiting-OTP state so the
     * user can retry.
     */
    data class OtpFailed(val message: String) : AuthEvent()

    /**
     * Transport / rate-limit / unexpected failure. Final for this attempt.
     */
    data class Error(val code: String, val message: String) : AuthEvent()
}

/** One callback for the entire auth lifecycle. */
typealias AuthEventHandler = (AuthEvent) -> Unit

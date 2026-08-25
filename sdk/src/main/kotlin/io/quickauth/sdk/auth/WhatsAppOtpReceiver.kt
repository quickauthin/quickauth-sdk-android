package io.quickauth.sdk.auth

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives a WhatsApp zero-tap or one-tap authentication code.
 *
 * WhatsApp does not deliver these over SMS. It broadcasts the code to the app named in the
 * template's `supported_apps`, matched on package name and the 11-character signing hash, so
 * [SmsRetriever] never sees it — the two are different delivery channels that happen to share
 * the same app hash.
 *
 * Declared in the manifest rather than registered at runtime. Zero-tap's promise is that the
 * user does nothing, which means the code can arrive while the app is backgrounded or not
 * running at all, and a runtime receiver only exists once the app already does — the case that
 * needs it least. That in turn means the code can arrive with nothing listening, so it is held
 * here and flushed the moment something does.
 *
 * Exported with no permission guard, matching Meta's documented declaration. Guarding it would
 * be worse than useless: Android silently drops a broadcast aimed at a receiver whose
 * permission the sender does not hold, so a guessed permission name produces a receiver that
 * never fires, with nothing thrown and nothing logged.
 *
 * Safety comes from WhatsApp's side — it only broadcasts to an app whose package name and
 * signing hash match the approved template, which an attacker cannot satisfy without the
 * signing key. What is left to us is not trusting the payload blindly.
 */
class WhatsAppOtpReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != ACTION_OTP_RETRIEVED) return

        val code = intent.getStringExtra(EXTRA_CODE)?.trim()
        if (code.isNullOrEmpty()) {
            Log.w(TAG, "WhatsApp OTP broadcast carried no code")
            return
        }

        // Shape check, not a security boundary — WhatsApp's package + signing-hash match is
        // that. This only stops an obviously wrong payload becoming a code the app tries to
        // verify, which would surface to the user as a failure they cannot explain.
        if (!PLAUSIBLE_CODE.matches(code)) {
            Log.w(TAG, "WhatsApp OTP broadcast carried an implausible code; ignoring")
            return
        }

        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
        Log.d(TAG, "WhatsApp OTP received (${code.length} chars, requestId=${requestId != null})")
        deliver(code)
    }

    companion object {
        internal const val TAG = "QuickAuthWaOtp"

        /**
         * WhatsApp's broadcast contract. Both zero-tap and one-tap arrive this way — one-tap
         * is the same delivery with a user tap in front of it — so one receiver serves both.
         *
         * A wrong value here fails in the worst way available: the broadcast is never matched,
         * nothing throws, and the OTP silently does not arrive. Verify against Meta's current
         * documentation when upgrading.
         */
        const val ACTION_OTP_RETRIEVED = "com.whatsapp.otp.OTP_RETRIEVED"
        const val EXTRA_CODE = "code"

        /** Meta's handshake id, present when the app initiated one. */
        const val EXTRA_REQUEST_ID = "request_id"

        /**
         * A code is digits, four to ten of them. Deliberately loose — Meta lets a merchant
         * choose the length, and rejecting a valid code because it is longer than expected
         * would break auto-read for exactly the merchants who configured it.
         */
        private val PLAUSIBLE_CODE = Regex("^[0-9]{4,10}$")

        /**
         * The code, waiting for a listener.
         *
         * Held rather than dropped because the receiver can fire before anything is listening.
         * Single-slot: a newer code always replaces an older one, which is what a user
         * requesting a second OTP expects, and it cannot grow without bound.
         */
        @Volatile
        private var pending: String? = null

        @Volatile
        private var listener: ((String) -> Unit)? = null

        /** Deliver now if something is listening, otherwise hold it. */
        @Synchronized
        fun deliver(code: String) {
            val target = listener
            if (target != null) target(code) else pending = code
        }

        /**
         * Attach a sink, and hand it anything that arrived while nothing was listening.
         * Taking the pending code rather than copying it means a code is delivered once — a
         * re-listen should not replay a code the user already used.
         */
        @Synchronized
        fun setListener(target: ((String) -> Unit)?) {
            listener = target
            if (target == null) return
            val held = pending
            pending = null
            if (held != null) target(held)
        }

        /** Drop anything held. Called when a fresh OTP request starts. */
        @Synchronized
        fun clearPending() {
            pending = null
        }
    }
}

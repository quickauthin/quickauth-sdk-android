package io.quickauth.sdk.auth

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Receives a WhatsApp zero-tap or one-tap authentication code.
 *
 * WhatsApp does not deliver these over SMS. It broadcasts to the app named in the template's
 * `supported_apps`, matched on package name and the 11-character signing hash, so Google's
 * [com.google.android.gms.auth.api.phone.SmsRetriever] never sees the message. Before this
 * receiver existed the code was dropped on the floor: a merchant sending on
 * [io.quickauth.sdk.OtpChannel.WHATSAPP] got no auto-read at all, and one sending on
 * [io.quickauth.sdk.OtpChannel.AUTO] got it for the users the backend happened to route over
 * SMS and not for the rest, with nothing to explain the difference.
 *
 * Declared in the SDK's `AndroidManifest.xml` rather than registered at runtime. Zero-tap's
 * promise is that the user does nothing, which means the code can arrive while the app is
 * backgrounded or not running at all — a runtime receiver only exists once the app already
 * does, which is the case that needs it least. Being a library manifest entry, it merges into
 * every host app automatically; merchants declare nothing.
 *
 * The receiver is exported with no permission guard, matching Meta's documented declaration.
 * Guarding it would be worse than useless: Android silently drops a broadcast aimed at a
 * receiver whose permission the sender does not hold, so a guessed permission name produces a
 * receiver that never fires, with nothing thrown and nothing logged.
 *
 * Safety comes from WhatsApp's side. It only broadcasts to an app whose package name and
 * 11-character signing hash match the approved template, which an attacker cannot satisfy
 * without the signing key. What is left to us is not trusting the payload blindly: a code that
 * is not plausibly a code is dropped.
 *
 * That in turn means the code can arrive with nothing listening. It is held here and flushed
 * the moment something subscribes, so a cold start does not lose it.
 */
class WhatsAppOtpReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != ACTION_OTP_RETRIEVED) return

        // A code that is absent or empty is worth ignoring quietly rather than surfacing as a
        // blank OTP the user cannot explain.
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
        private const val TAG = "QuickAuthWaOtp"

        /**
         * WhatsApp's broadcast contract. Both zero-tap and one-tap arrive this way — one-tap is
         * the same delivery with a user tap in front of it — so one receiver serves both.
         *
         * Kept as constants because a wrong value here fails in the worst way available: the
         * broadcast is simply never matched, nothing throws, and the OTP silently does not
         * arrive. The action is duplicated in `AndroidManifest.xml`, which cannot reference a
         * Kotlin constant; [ACTION_OTP_RETRIEVED] and the manifest `<action>` must be changed
         * together. Verify against Meta's current documentation when upgrading.
         */
        const val ACTION_OTP_RETRIEVED: String = "com.whatsapp.otp.OTP_RETRIEVED"

        /** The code itself — already extracted by WhatsApp, unlike the SMS path. */
        const val EXTRA_CODE: String = "code"

        /** Meta's handshake id, present when the app initiated one. */
        const val EXTRA_REQUEST_ID: String = "request_id"

        /**
         * A code is digits, four to ten of them. Deliberately looser than the SDK's own 4–8
         * verify-side rule: Meta lets a merchant choose the length, and rejecting a valid code
         * here because it is longer than expected would break auto-read for exactly the
         * merchants who configured it.
         */
        private val PLAUSIBLE_CODE = Regex("^[0-9]{4,10}$")

        /**
         * The code, waiting for a listener.
         *
         * Held rather than dropped because the receiver can fire before anything in the SDK is
         * listening — that is the entire point of zero-tap. Single-slot: a newer code always
         * replaces an older one, which is what a user requesting a second OTP expects, and it
         * cannot grow without bound.
         */
        @Volatile
        private var pending: String? = null

        /**
         * Every live subscriber.
         *
         * A list rather than the single slot the Flutter plugin uses, because there the Dart
         * event channel multiplexes one native listener to many Dart listeners and here there
         * is no such layer. Two subscribers are the normal case, not an edge one:
         * [OtpService.initiate] arms auto-read on the caller's behalf, and a merchant's OTP
         * field may collect [OtpService.observeOTP] at the same time. With a single slot the
         * second subscription would silently evict the first, and the first's teardown would
         * then null out the second — auto-read working or not depending on the order two
         * unrelated pieces of UI happened to start in.
         */
        private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

        /** Deliver now to everything listening, otherwise hold it. */
        @Synchronized
        @JvmStatic
        fun deliver(code: String) {
            val targets = listeners.toList()
            if (targets.isEmpty()) {
                pending = code
                return
            }
            // A throwing subscriber must not stop the others from being told.
            targets.forEach { target ->
                try {
                    target(code)
                } catch (t: Throwable) {
                    Log.w(TAG, "WhatsApp OTP listener threw", t)
                }
            }
        }

        /**
         * Attach a subscriber, and hand it anything that arrived while nothing was listening.
         *
         * Taking the pending code rather than copying it means a code is delivered once — a
         * re-subscribe after a screen rotation should not replay a code the user already used.
         */
        @Synchronized
        internal fun addListener(target: (String) -> Unit) {
            listeners.add(target)
            val held = pending ?: return
            pending = null
            try {
                target(held)
            } catch (t: Throwable) {
                Log.w(TAG, "WhatsApp OTP listener threw on flush", t)
            }
        }

        @Synchronized
        internal fun removeListener(target: (String) -> Unit) {
            listeners.remove(target)
        }

        /**
         * Drop anything held. Called when a fresh OTP request starts, because a code that
         * arrived after the user gave up on a previous attempt would otherwise be delivered
         * against the new request and fail verification for reasons they cannot see.
         */
        @Synchronized
        @JvmStatic
        fun clearPending() {
            pending = null
        }

        /** Test seam — drops held state and every subscriber. */
        @Synchronized
        internal fun resetForTesting() {
            pending = null
            listeners.clear()
        }
    }
}

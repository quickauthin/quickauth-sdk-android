package io.quickauth.sdk.auth

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID

/**
 * The app's half of WhatsApp's zero-tap / one-tap authentication.
 *
 * Four things have to be true together for a WhatsApp code to auto-fill, and with any one of
 * them missing the symptom is identical — the message arrives, the code does not fill, nothing
 * errors:
 *
 *  1. a manifest-declared [WhatsAppOtpReceiver], so the broadcast lands even when the app is
 *     backgrounded;
 *  2. the `OTP_REQUESTED` handshake below, sent **before** each OTP request;
 *  3. `<queries>` for `com.whatsapp` and `com.whatsapp.w4b` in the manifest, so Android 11+
 *     actually delivers that handshake;
 *  4. [OtpService.observeOTP] merging this source with SMS rather than picking one.
 *
 * All four ship in the SDK. Merchants declare nothing.
 *
 * The [context] is nullable so [OtpService] can be constructed without one in unit tests; a
 * null context makes every method a no-op returning an empty stream, which is the same shape as
 * a device with no WhatsApp installed.
 */
class WhatsAppOtpRetriever(private val context: Context?) {

    /**
     * Codes as WhatsApp delivers them — already the code, not a message to parse.
     *
     * Cold: subscribing attaches to [WhatsAppOtpReceiver], which immediately flushes anything
     * that arrived while nothing was listening. Cancelling detaches. The manifest receiver
     * itself is always live regardless, which is what lets a zero-tap code survive the app not
     * running.
     */
    fun observe(): Flow<String> = callbackFlow {
        val listener: (String) -> Unit = { code -> trySend(code) }
        WhatsAppOtpReceiver.addListener(listener)
        awaitClose { WhatsAppOtpReceiver.removeListener(listener) }
    }

    /**
     * Tell WhatsApp a code is about to be requested, and that this app may receive it.
     *
     * Zero-tap does not work without this, and nothing says so. Meta requires the app to
     * broadcast a handshake BEFORE the template is sent: "When a user in your app requests a
     * password or code to be delivered to their WhatsApp number, first initiate the handshake,
     * then call our API to send the authentication template message."
     *
     * Without it WhatsApp receives the message and shows it, and simply never broadcasts the
     * code. Every other check can pass — template approved, package matching, signing hash
     * matching, receiver declared and firing — and the OTP still does not auto-fill, with no
     * error anywhere to explain it.
     *
     * The [PendingIntent] in `_ci_` is how WhatsApp identifies the caller. It carries no action
     * and is never sent; WhatsApp reads the creator's identity off it, which is why it must be
     * immutable — a mutable one would let another app fill it in.
     *
     * Broadcast to both WhatsApp and WhatsApp Business, because the user's code arrives on
     * whichever they have. Sending to a package that is not installed is a no-op rather than an
     * error, so there is nothing to check first.
     *
     * The handshake expires after ten minutes, which is why it goes per OTP request rather than
     * once at startup — and why [OtpService.resendOtp] re-sends it.
     *
     * Never throws: a missing handshake costs auto-read, not the login.
     *
     * @return the handshake's request id, or `null` when there was no context or the broadcast
     *         failed. WhatsApp echoes it back on the delivered code.
     */
    fun sendHandshake(): String? {
        val ctx = context ?: return null
        return try {
            val requestId = UUID.randomUUID().toString()
            // No action and FLAG_IMMUTABLE: this is an identity token, not something to fire.
            // FLAG_IMMUTABLE only exists from API 23; on 21–22 the bit is simply not set, which
            // is the platform's own pre-23 behaviour rather than a downgrade we chose.
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val identity = PendingIntent.getBroadcast(ctx, 0, Intent(), flags)

            // Which WhatsApp packages this app can actually see. On API 30+ an invisible
            // package swallows the broadcast silently, so reporting it is the difference
            // between diagnosing that in one log line and chasing the template for a day.
            val visible = WHATSAPP_PACKAGES.filter { pkg ->
                try {
                    ctx.packageManager.getPackageInfo(pkg, 0)
                    true
                } catch (_: PackageManager.NameNotFoundException) {
                    false
                }
            }

            for (pkg in WHATSAPP_PACKAGES) {
                val intent = Intent(ACTION_OTP_REQUESTED)
                    .setPackage(pkg)
                    .putExtra(EXTRA_CALLER_IDENTITY, identity)
                    .putExtra(WhatsAppOtpReceiver.EXTRA_REQUEST_ID, requestId)
                ctx.sendBroadcast(intent)
            }

            if (visible.isEmpty()) {
                // Either WhatsApp is not installed, or <queries> is missing from the merged
                // manifest. Both mean the handshake reached nobody and zero-tap cannot work.
                Log.w(
                    TAG,
                    "WhatsApp OTP handshake sent but NO WhatsApp package is visible — " +
                        "not installed, or <queries> missing from the merged manifest",
                )
            } else {
                Log.d(TAG, "WhatsApp OTP handshake sent to $visible (requestId=$requestId)")
            }
            requestId
        } catch (t: Throwable) {
            // Never fail the OTP request over this. A missing handshake costs auto-read, not
            // the login — the user can still read the code and type it.
            Log.w(TAG, "WhatsApp OTP handshake failed: ${t.message}")
            null
        }
    }

    /**
     * Discard any code the receiver is holding from an earlier attempt.
     *
     * Without this, a code that arrived after the user gave up on a previous attempt would be
     * delivered against the new request and fail verification for reasons they cannot see.
     */
    fun clearPending() {
        if (context == null) return
        WhatsAppOtpReceiver.clearPending()
    }

    companion object {
        /**
         * Shared with [WhatsAppOtpReceiver] so the handshake and the delivery log under one
         * tag. They used to log under two, so filtering on either showed half the exchange and
         * a silent handshake read as a handshake that never happened.
         */
        private const val TAG = "QuickAuthWaOtp"

        /** Meta's handshake action, broadcast to WhatsApp before the template is sent. */
        const val ACTION_OTP_REQUESTED: String = "com.whatsapp.otp.OTP_REQUESTED"

        /** Meta's name for the caller-identity PendingIntent. */
        private const val EXTRA_CALLER_IDENTITY = "_ci_"

        /**
         * Consumer WhatsApp and WhatsApp Business — the code arrives on whichever is installed.
         * Mirrored by `<queries>` in the SDK manifest; both lists must change together.
         */
        val WHATSAPP_PACKAGES: List<String> = listOf("com.whatsapp", "com.whatsapp.w4b")
    }
}

package io.quickauth.sdk.auth

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import java.util.UUID

/**
 * Tells WhatsApp an app is about to request a code, and may receive it.
 *
 * Zero-tap does not work without this, and nothing says so. Meta requires the app to broadcast
 * a handshake BEFORE the authentication template is sent — "first initiate the handshake, then
 * call our API to send the authentication template message". Without it WhatsApp receives the
 * message, shows it, and never broadcasts the code.
 *
 * That is what makes its absence expensive to diagnose: the template can be approved, the
 * package can match, the signing hash can match, the receiver can be registered and
 * demonstrably firing, and the OTP still does not auto-fill, with no error anywhere.
 */
internal object WhatsAppOtpHandshake {

    private const val ACTION_OTP_REQUESTED = "com.whatsapp.otp.OTP_REQUESTED"

    /** Consumer WhatsApp and WhatsApp Business — the code arrives on whichever is installed. */
    private val WHATSAPP_PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")

    /**
     * Broadcast the handshake.
     *
     * Sent per OTP request rather than once at startup: Meta expires it after ten minutes, so
     * one at startup would leave every later request silently unable to auto-read.
     *
     * @return the request id WhatsApp echoes back with the code, or null if nothing was sent
     */
    fun send(context: Context): String? = try {
        val requestId = UUID.randomUUID().toString()

        // Identity token, not something to fire: no action, and immutable so another app
        // cannot fill it in. WhatsApp reads the creator's identity off it.
        val identity = PendingIntent.getBroadcast(
            context, 0, Intent(),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Which WhatsApp packages this app can actually see. On API 30+ an invisible package
        // swallows the broadcast silently, so reporting it is the difference between
        // diagnosing that in one log line and chasing the template for a day.
        val visible = WHATSAPP_PACKAGES.filter { pkg ->
            try {
                context.packageManager.getPackageInfo(pkg, 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }

        for (pkg in WHATSAPP_PACKAGES) {
            val intent = Intent(ACTION_OTP_REQUESTED).setPackage(pkg)
            intent.putExtra("_ci_", identity)
            intent.putExtra("request_id", requestId)
            context.sendBroadcast(intent)
        }

        if (visible.isEmpty()) {
            // Either WhatsApp is not installed, or <queries> did not reach the merged
            // manifest. Both mean the handshake reached nobody and zero-tap cannot work.
            Log.w(WhatsAppOtpReceiver.TAG, "WhatsApp OTP handshake sent but NO WhatsApp package "
                    + "is visible — not installed, or <queries> missing from the merged manifest")
        } else {
            Log.d(WhatsAppOtpReceiver.TAG,
                "WhatsApp OTP handshake sent to $visible (requestId=$requestId)")
        }
        requestId
    } catch (t: Throwable) {
        // Never fail the OTP request over this. A missing handshake costs auto-read, not the
        // login — the user can still read the code and type it.
        Log.w(WhatsAppOtpReceiver.TAG, "WhatsApp OTP handshake failed: ${t.message}")
        null
    }
}

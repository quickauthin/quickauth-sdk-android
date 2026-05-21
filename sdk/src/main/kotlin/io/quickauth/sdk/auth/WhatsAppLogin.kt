package io.quickauth.sdk.auth

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import io.quickauth.sdk.QuickAuth
import java.util.UUID

/**
 * "Login with WhatsApp" deep-link flow.
 *
 * The classic problem with email-magic-links on mobile is that opening WhatsApp dumps the user
 * into a separate app and the original tab/intent is forgotten.  We solve this with **App Links**:
 *
 * 1. SDK generates a session token and opens `https://wa.me/<businessNumber>?text=<token>`.
 * 2. User taps "send" — WhatsApp delivers the message to our verified business number.
 * 3. Our backend marks the session verified and replies with a single message containing
 *    `https://link.quickauth.in/return/<token>`.
 * 4. That URL is registered as an App Link for the customer's app, so tapping it brings the
 *    user back into their own app — and our SDK reads the JWT off the deep-link.
 *
 * The Activity that registers the App Link receives the resumed intent and forwards it to
 * [QuickAuth.attribution.captureLaunch] which extracts the `qa_clid` + JWT.
 */
class WhatsAppLogin(private val activity: Activity) {

    /**
     * Build the `wa.me` URI and start the activity.  Falls back to the WhatsApp Business
     * package if the consumer WhatsApp isn't installed; surfaces an [ActivityNotFoundException]
     * to the caller if neither is present.
     */
    fun launch(businessNumber: String, prefilledMessage: String? = null) {
        require(businessNumber.matches(Regex("^\\+?[1-9]\\d{6,14}$"))) {
            "businessNumber must look like '+919574980048'"
        }
        val token = UUID.randomUUID().toString()
        val text = prefilledMessage ?: "Login token: $token"
        val uri = Uri.Builder()
            .scheme("https")
            .authority("wa.me")
            .appendPath(businessNumber.removePrefix("+"))
            .appendQueryParameter("text", text)
            .build()
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // Try WhatsApp Business as a fallback (same protocol, different package).
            val biz = Intent(intent).setPackage("com.whatsapp.w4b")
            activity.startActivity(biz)
        }
    }
}

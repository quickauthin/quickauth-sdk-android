package io.quickauth.sdk.auth

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import com.google.android.gms.auth.api.phone.SmsRetriever as GmsSmsRetriever
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.security.MessageDigest

/**
 * Wrapper around the Google Play [SmsRetriever][com.google.android.gms.auth.api.phone.SmsRetriever]
 * API + the User Consent fallback.
 *
 * **Why two paths?**
 *  * The plain SMS Retriever API needs the OTP message to start with `<#>` and end with the
 *    11-char app-hash — so it only works when the sender (i.e. our notifex backend) controls
 *    the message body.  No permissions required.
 *  * The User Consent API works for *any* sender but pops a one-tap permission dialog ("Allow
 *    QuickAuth to read this OTP?").  We use it when [SMS_RETRIEVER_FAILED] fires.
 *
 * The class is internal-by-default; consumers interact with it via [OtpService.observeOTP].
 */
class SmsRetriever(private val context: Context) {

    /** Returns a cold flow of OTP codes parsed from inbound SMS messages. */
    fun observe(): Flow<String> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != GmsSmsRetriever.SMS_RETRIEVED_ACTION) return
                val extras: Bundle = intent.extras ?: return
                val status = extras.get(GmsSmsRetriever.EXTRA_STATUS) as? Status ?: return
                if (status.statusCode == CommonStatusCodes.SUCCESS) {
                    val message = extras.getString(GmsSmsRetriever.EXTRA_SMS_MESSAGE).orEmpty()
                    val code = extractCode(message)
                    if (code != null) trySend(code)
                }
                // status.statusCode == TIMEOUT is silently ignored; caller can re-subscribe.
            }
        }
        val filter = IntentFilter(GmsSmsRetriever.SMS_RETRIEVED_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        try {
            // Best-effort: kick off the retriever; if Play Services is missing we still
            // keep the receiver around in case the host app uses a different signal.
            GmsSmsRetriever.getClient(context).startSmsRetriever()
        } catch (_: Throwable) { /* Play Services unavailable — degrade silently. */ }

        awaitClose { runCatching { context.unregisterReceiver(receiver) } }
    }

    /** Callback-style overload used by [OtpService.observeOTP]. */
    fun observe(onCode: (String) -> Unit): Subscription {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != GmsSmsRetriever.SMS_RETRIEVED_ACTION) return
                val extras: Bundle = intent.extras ?: return
                val status = extras.get(GmsSmsRetriever.EXTRA_STATUS) as? Status ?: return
                if (status.statusCode == CommonStatusCodes.SUCCESS) {
                    val message = extras.getString(GmsSmsRetriever.EXTRA_SMS_MESSAGE).orEmpty()
                    extractCode(message)?.let(onCode)
                }
            }
        }
        val filter = IntentFilter(GmsSmsRetriever.SMS_RETRIEVED_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        runCatching { GmsSmsRetriever.getClient(context).startSmsRetriever() }
        return Subscription { runCatching { context.unregisterReceiver(receiver) } }
    }

    /** Returned to callers so they can stop listening when their screen tears down. */
    fun interface Subscription {
        fun cancel()
    }

    companion object {
        /**
         * The code, anchored to the word that introduces it.
         *
         * Only punctuation, whitespace and a short "is"/"are" may sit between the keyword and
         * the digits. A looser gap would swallow the wrong number in bodies like
         * "Your OTP for order 4471029 is 483920", where an unrelated reference number is the
         * nearer match; here the gap fails and we fall through to [FALLBACK_CODE_REGEX].
         */
        private val KEYWORD_CODE_REGEX = Regex(
            """(?:otp|code|pin|password)[\s:=.,\-\u2013\u2014]{0,6}(?:is|are)?[\s:=.,\-\u2013\u2014]{0,6}\b(\d{4,8})\b""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Any standalone 4-8 digit run. `\b` keeps this from matching part of a longer run,
         * so 10-digit phone numbers and 12-digit E.164 numbers are skipped rather than
         * truncated into a plausible-looking code.
         */
        private val FALLBACK_CODE_REGEX = Regex("""\b(\d{4,8})\b""")

        /**
         * The 11-char app-hash that terminates every SMS Retriever body. It is base64 over
         * `[A-Za-z0-9+/]`, so it can contain a digit run flanked by `+` or `/` that looks
         * exactly like a standalone code. Strip it before scanning.
         */
        private val APP_HASH_SUFFIX_REGEX = Regex("""\s+[A-Za-z0-9+/]{11}\s*$""")

        /**
         * Pull the OTP out of an SMS body.
         *
         * Prefers a keyword-anchored match; otherwise falls back to the **last** standalone
         * digit run. Last, not first: senders put reference numbers, order ids and amounts
         * ahead of the code far more often than after it.
         */
        internal fun extractCode(message: String): String? {
            val body = APP_HASH_SUFFIX_REGEX.replace(message, "")
            val keyed = KEYWORD_CODE_REGEX.findAll(body).lastOrNull()
            if (keyed != null) return keyed.groupValues[1]
            return FALLBACK_CODE_REGEX.findAll(body).lastOrNull()?.groupValues?.get(1)
        }

        /**
         * Compute the 11-character app-hash that the Google Play SMS Retriever expects at the
         * end of the OTP message body.  Algorithm (from the Play docs):
         *
         *  1. `appInfo = "$applicationId $signatureSha256Hex"`
         *  2. `digest  = SHA-256(appInfo)`
         *  3. base64( digest, NO_PADDING | NO_WRAP ).substring(0, 11)
         */
        fun computeAppHash(applicationId: String, signatureSha256Hex: String): String {
            val appInfo = "$applicationId $signatureSha256Hex"
            val sha = MessageDigest.getInstance("SHA-256").digest(appInfo.toByteArray())
            return android.util.Base64
                .encodeToString(sha, android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
                .substring(0, 11)
        }

        /**
         * Walk the [PackageManager] to recover the SHA-256 of the certificate that signed the
         * currently-running APK and feed it into [computeAppHash].
         */
        @Suppress("DEPRECATION")
        fun computeAppHashForInstalledApp(context: Context): String {
            val pm = context.packageManager
            val pkg = context.packageName
            val signatureBytes: ByteArray = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                val signers = info.signingInfo
                val sig = signers?.apkContentsSigners?.firstOrNull() ?: signers?.signingCertificateHistory?.firstOrNull()
                sig?.toByteArray() ?: ByteArray(0)
            } else {
                val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
                info.signatures?.firstOrNull()?.toByteArray() ?: ByteArray(0)
            }
            val sha = MessageDigest.getInstance("SHA-256").digest(signatureBytes)
            val sigHex = sha.joinToString(separator = "") { "%02X".format(it) }
            return computeAppHash(pkg, sigHex)
        }
    }
}

/**
 * Helper for using the User Consent API — the developer must launch the returned [Intent] using
 * [Activity.startActivityForResult] and forward the result to [parseConsentResult].
 */
object SmsUserConsent {
    fun startIntent(context: Context, senderPhone: String? = null): com.google.android.gms.tasks.Task<Void> {
        return GmsSmsRetriever.getClient(context).startSmsUserConsent(senderPhone)
    }

    /** Parse the OTP code out of a User Consent activity result. */
    fun parseConsentResult(resultCode: Int, data: Intent?): String? {
        if (resultCode != Activity.RESULT_OK || data == null) return null
        val message = data.getStringExtra(GmsSmsRetriever.EXTRA_SMS_MESSAGE) ?: return null
        return SmsRetriever.extractCode(message)
    }
}

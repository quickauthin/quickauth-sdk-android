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
         * Keyword-anchored code, e.g. "your OTP is 483920" or "code: 4821".
         *
         * Only punctuation, whitespace and a short "is"/"are" may sit between the keyword and
         * the digits. A looser gap swallows the wrong number in bodies like
         * "Your OTP for order 4471029 is 483920", where an unrelated reference number is the
         * nearer match — there the gap fails and we fall through to [FALLBACK_CODE].
         */
        private val KEYWORD_CODE = Regex(
            "(?:otp|code|pin|password)[\\s:=.,\\-\\u2013\\u2014]{0,6}(?:is|are)?" +
                "[\\s:=.,\\-\\u2013\\u2014]{0,6}\\b(\\d{4,8})\\b",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Any standalone 4–8 digit run. The word boundaries keep this off part of a longer run,
         * so 10-digit mobile numbers and 12-digit E.164 numbers are skipped rather than
         * truncated into something that looks like a plausible code.
         */
        private val FALLBACK_CODE = Regex("\\b(\\d{4,8})\\b")

        /**
         * The 11-char app hash that terminates every SMS Retriever body. It is base64 over
         * `[A-Za-z0-9+/]`, so it can contain a digit run flanked by `+` or `/` that reads
         * exactly like a standalone code. Strip it before scanning.
         */
        private val APP_HASH_SUFFIX = Regex("\\s+[A-Za-z0-9+/]{11}\\s*$")

        /**
         * Pull the OTP out of an SMS body.
         *
         * Was `find` over a bare `\b(\d{4,8})\b`, which returned the FIRST digit run in the
         * message — so "Your OTP for order 4471029 is 483920" auto-filled the order number and
         * the user watched the wrong code appear in the field, then watched it be rejected.
         * Amounts and reference numbers cause the same thing.
         *
         * Prefers a keyword-anchored match; otherwise takes the **last** standalone run. Last,
         * not first: senders put reference numbers, order ids and amounts ahead of the code far
         * more often than after it. Matches the Flutter SDK's extraction exactly.
         */
        internal fun extractCode(message: String): String? {
            val stripped = message.replace(APP_HASH_SUFFIX, "")
            KEYWORD_CODE.findAll(stripped).lastOrNull()?.let { return it.groupValues[1] }
            return FALLBACK_CODE.findAll(stripped).lastOrNull()?.groupValues?.get(1)
        }

        /** Google's algorithm truncates the digest to 9 bytes before base64-encoding it. */
        private const val NUM_HASHED_BYTES = 9

        /** …and keeps the first 11 characters of that. */
        private const val NUM_BASE64_CHARS = 11

        /**
         * Compute the 11-character app-hash that the Google Play SMS Retriever expects at the
         * end of the OTP message body.
         *
         * Google's documented algorithm (`AppSignatureHelper` in the SMS Retriever sample):
         *
         *  1. `appInfo = "$applicationId $signature"`, where `signature` is
         *     [android.content.pm.Signature.toCharsString] — the lower-case hex of the signing
         *     **certificate's own DER bytes**.
         *  2. `digest = SHA-256(appInfo)`, truncated to the first 9 bytes.
         *  3. `base64(digest, NO_PADDING or NO_WRAP).substring(0, 11)`.
         *
         * The second parameter used to be documented and named as the SHA-256 hex of the
         * certificate, and [computeAppHashForInstalledApp] duly hashed the certificate and
         * passed the digest in. That produces a well-formed 11-character string that is not the
         * hash Google computes, so the platform never matched an inbound message and SMS
         * auto-read could not have worked — silently, since a non-matching hash means the
         * broadcast simply never fires.
         *
         * @param signature the certificate's `toCharsString()` value, NOT its SHA-256.
         */
        fun computeAppHash(applicationId: String, signature: String): String {
            val appInfo = "$applicationId $signature"
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(appInfo.toByteArray(Charsets.UTF_8))
                .copyOfRange(0, NUM_HASHED_BYTES)
            return android.util.Base64
                .encodeToString(digest, android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
                .substring(0, NUM_BASE64_CHARS)
        }

        /**
         * The app-hash for the certificate that signed the currently-running APK.
         *
         * Returns the first signer's hash. Apps that have rotated their signing key (v3) have
         * more than one; [computeAppHashesForInstalledApp] returns them all, and every one of
         * them has to be registered with the OTP sender or messages to devices holding the
         * other certificate will not be delivered.
         */
        fun computeAppHashForInstalledApp(context: Context): String =
            computeAppHashesForInstalledApp(context).firstOrNull().orEmpty()

        /** Every app-hash valid for this install — one per signing certificate. */
        @Suppress("DEPRECATION")
        fun computeAppHashesForInstalledApp(context: Context): List<String> {
            val pm = context.packageManager
            val pkg = context.packageName
            val signatures: Array<out android.content.pm.Signature> = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                    val signers = info.signingInfo
                    signers?.apkContentsSigners
                        ?: signers?.signingCertificateHistory
                        ?: emptyArray()
                } else {
                    pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures ?: emptyArray()
                }
            } catch (_: Throwable) {
                emptyArray()
            }
            // toCharsString(), not a digest of the bytes: see computeAppHash.
            return signatures.map { computeAppHash(pkg, it.toCharsString()) }
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

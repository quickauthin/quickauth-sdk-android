package io.quickauth.sdk.attribution

import android.content.Context
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import com.android.installreferrer.api.ReferrerDetails
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Coroutine-friendly wrapper around the Play [InstallReferrerClient].
 *
 * We pull the install referrer **once** (first launch only) and parse it into a
 * `Map<String, String>`.  The map is forwarded to `/v1/sdk/attribution/launch` so the backend
 * can stitch the install to the originating WhatsApp campaign.
 *
 * The Play client is one-shot — you must call [InstallReferrerClient.endConnection] when done.
 */
internal class InstallReferrer(private val context: Context) {

    /** Returns the raw `referrerUrl`, or `null` if Play Store isn't available / install not found. */
    suspend fun fetch(): ReferrerDetails? = suspendCancellableCoroutine { cont ->
        val client = InstallReferrerClient.newBuilder(context).build()
        cont.invokeOnCancellation { runCatching { client.endConnection() } }
        client.startConnection(object : InstallReferrerStateListener {
            override fun onInstallReferrerSetupFinished(responseCode: Int) {
                try {
                    val result = if (responseCode == InstallReferrerClient.InstallReferrerResponse.OK) {
                        client.installReferrer
                    } else null
                    if (cont.isActive) cont.resume(result)
                } finally {
                    runCatching { client.endConnection() }
                }
            }
            override fun onInstallReferrerServiceDisconnected() {
                if (cont.isActive) cont.resume(null)
            }
        })
    }

    companion object {
        /**
         * Parse a `play_referrer` style URL-encoded string into a flat key/value map.
         * Inputs look like `utm_source=whatsapp&qa_clid=abcd1234&utm_campaign=diwali`.
         */
        fun parse(referrer: String?): Map<String, String> {
            if (referrer.isNullOrBlank()) return emptyMap()
            return referrer.split('&')
                .mapNotNull { pair ->
                    val idx = pair.indexOf('=')
                    if (idx <= 0) null
                    else pair.substring(0, idx) to pair.substring(idx + 1)
                }
                .toMap()
        }
    }
}

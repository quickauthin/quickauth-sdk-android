package io.quickauth.sdk.attribution

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.quickauth.sdk.core.ApiClient
import io.quickauth.sdk.core.Storage

/**
 * Marketing attribution surface.
 *
 *  * [captureLaunch] — call from `Activity.onCreate` / `onNewIntent` to collect the deep-link
 *    payload + Install Referrer + device fingerprint and POST them to the backend.  Returns
 *    the matched campaign metadata so the host app can personalise its onboarding screen.
 *  * [trackConversion] — fire-and-forget conversion event (`signup`, `purchase`, …).
 */
class AttributionService internal constructor(
    private val context: Context,
    private val api: ApiClient,
    private val storage: Storage = Storage(context),
) {

    /**
     * Capture an attribution launch.  Pulls:
     *   * `qa_clid`, `utm_*` params from the deep-link [Intent]
     *   * Install referrer (only on first launch)
     *   * [DeviceInfo] + SHA-256 [Fingerprint]
     *
     * @return [LaunchAttribution] with the backend-matched campaign IDs (or `matched=false`).
     */
    suspend fun captureLaunch(intent: Intent?): LaunchAttribution {
        val urlParams = intent?.data?.let(::extractParams).orEmpty()
        val qaClid = urlParams["qa_clid"] ?: storage.qaClid
        if (qaClid != null) storage.qaClid = qaClid

        // Pull install referrer on first launch, then mark it so we don't ping Play again.
        val installReferrer = if (!storage.hasReportedInstall) {
            runCatching { InstallReferrer(context).fetch() }
                .getOrNull()
                ?.installReferrer
                ?.let(InstallReferrer::parse)
                .orEmpty()
                .also { storage.hasReportedInstall = true }
        } else emptyMap()

        val deviceInfo = DeviceInfo.collect(context)
        val fingerprint = Fingerprint.compute(deviceInfo)

        val payload = mapOf(
            "qa_clid" to qaClid,
            "fingerprint" to fingerprint,
            "deviceInfo" to deviceInfo,
            "urlParams" to urlParams,
            "installReferrer" to installReferrer,
        )
        return runCatching {
            api.postJson("/v1/sdk/attribution/launch", payload, LaunchAttribution::class.java)
        }.getOrElse { LaunchAttribution(matched = false) }
    }

    /**
     * Track a downstream conversion (signup, purchase, etc.).  Errors are swallowed —
     * attribution is best-effort and must not break the host app.
     */
    suspend fun trackConversion(
        event: String,
        value: Double = 0.0,
        currency: String = "INR",
        metadata: Map<String, Any?> = emptyMap(),
    ) {
        require(event.isNotBlank()) { "event must not be blank" }
        val body = mapOf(
            "event" to event,
            "value" to value,
            "currency" to currency,
            "qa_clid" to storage.qaClid,
            "metadata" to metadata,
        )
        runCatching { api.postJson("/v1/sdk/attribution/conversion", body, Map::class.java) }
    }

    /**
     * Pull `qa_clid` + UTM params + arbitrary query string out of an incoming deep-link.
     * Internal-but-public so tests can hit it directly without an [Intent].
     */
    internal fun extractParams(uri: Uri): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (name in uri.queryParameterNames.orEmpty()) {
            uri.getQueryParameter(name)?.let { out[name] = it }
        }
        return out
    }

    /** Result returned by the backend when launch attribution succeeds. */
    data class LaunchAttribution(
        val matched: Boolean = false,
        val campaignId: String? = null,
        val templateId: String? = null,
        val variantId: String? = null,
    )
}

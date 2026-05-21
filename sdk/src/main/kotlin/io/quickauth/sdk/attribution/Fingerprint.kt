package io.quickauth.sdk.attribution

import java.security.MessageDigest

/**
 * Probabilistic device fingerprint.
 *
 * We **never** read hardware identifiers (MAC, IMEI, ANDROID_ID) — those are gated behind
 * permissions on modern Android and explicitly disallowed by Play Store policy for ads use.
 * Instead we hash a tuple of stable device attributes:
 *
 *   `(osVersion, manufacturer, model, locale, timezone, screen, density)`
 *
 * The hash is **deterministic** for the same physical device and acts as a fuzzy join key
 * server-side when the `qa_clid` is missing.
 */
object Fingerprint {

    /** Compute a SHA-256 hex digest of the canonical fingerprint string. */
    fun compute(info: DeviceInfo): String {
        val canonical = listOf(
            info.osName,
            info.osVersion,
            info.manufacturer.lowercase(),
            info.model.lowercase(),
            info.locale,
            info.timezone,
            "${info.screenWidth}x${info.screenHeight}",
            "%.2f".format(info.screenDensity),
        ).joinToString("|")
        val bytes = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

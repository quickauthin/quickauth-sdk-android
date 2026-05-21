package io.quickauth.sdk.attribution

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.util.DisplayMetrics
import java.util.Locale
import java.util.TimeZone

/**
 * Plain-old data class describing the device.  Sent (with consent) to
 * `/v1/sdk/attribution/launch` and used by the matching algorithm to dedupe events from the
 * same device when the `qa_clid` is missing (e.g. user pasted the URL into a different browser).
 *
 * Nothing here is uniquely identifying on its own — it's the [Fingerprint] hash that turns this
 * into a probabilistic ID.
 */
data class DeviceInfo(
    val osName: String,
    val osVersion: String,
    val manufacturer: String,
    val model: String,
    val locale: String,
    val timezone: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val screenDensity: Float,
    val sdkVersion: Int,
    val isTablet: Boolean,
) {
    companion object {
        fun collect(context: Context): DeviceInfo {
            val metrics: DisplayMetrics = context.resources.displayMetrics
            val config = context.resources.configuration
            val isTablet = (config.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK) >=
                Configuration.SCREENLAYOUT_SIZE_LARGE
            return DeviceInfo(
                osName = "Android",
                osVersion = Build.VERSION.RELEASE.orEmpty(),
                manufacturer = Build.MANUFACTURER.orEmpty(),
                model = Build.MODEL.orEmpty(),
                locale = Locale.getDefault().toLanguageTag(),
                timezone = TimeZone.getDefault().id,
                screenWidth = metrics.widthPixels,
                screenHeight = metrics.heightPixels,
                screenDensity = metrics.density,
                sdkVersion = Build.VERSION.SDK_INT,
                isTablet = isTablet,
            )
        }
    }
}

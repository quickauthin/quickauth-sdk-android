package io.quickauth.sdk.core

import android.content.Context
import android.content.SharedPreferences

/**
 * Tiny typed wrapper over [SharedPreferences] used to persist non-sensitive SDK state
 * (publishable key, base URL, consent flag, last-seen `qa_clid`).  The publishable key is
 * **not** secret — it's safe to ship inside the APK — so we don't bother with EncryptedSharedPreferences.
 *
 * Keep this class boringly small; it's mocked heavily in unit tests.
 */
class Storage(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var publicKey: String?
        get() = prefs.getString(KEY_PUBLIC_KEY, null)
        set(value) = prefs.edit().putString(KEY_PUBLIC_KEY, value).apply()

    var apiBaseUrl: String?
        get() = prefs.getString(KEY_BASE_URL, null)
        set(value) = prefs.edit().putString(KEY_BASE_URL, value).apply()

    var consentGranted: Boolean
        get() = prefs.getBoolean(KEY_CONSENT, false)
        set(value) = prefs.edit().putBoolean(KEY_CONSENT, value).apply()

    var qaClid: String?
        get() = prefs.getString(KEY_QA_CLID, null)
        set(value) = prefs.edit().putString(KEY_QA_CLID, value).apply()

    /** First-launch sentinel — used by [io.quickauth.sdk.attribution.AttributionService]. */
    var hasReportedInstall: Boolean
        get() = prefs.getBoolean(KEY_INSTALL_REPORTED, false)
        set(value) = prefs.edit().putBoolean(KEY_INSTALL_REPORTED, value).apply()

    /**
     * Persistent device token for OneTap (silent re-auth). Server-minted on
     * the first /initiate, replayed on every subsequent call. Not
     * encrypted — the value is opaque and only useful in combination with
     * a valid SDK session JWT, which never lives on disk.
     */
    var deviceToken: String?
        get() = prefs.getString(KEY_DEVICE_TOKEN, null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_DEVICE_TOKEN) else putString(KEY_DEVICE_TOKEN, value)
            }.apply()
        }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        const val PREFS_NAME = "io.quickauth.sdk.prefs"
        private const val KEY_PUBLIC_KEY = "public_key"
        private const val KEY_BASE_URL = "api_base_url"
        private const val KEY_CONSENT = "consent_granted"
        private const val KEY_QA_CLID = "qa_clid"
        private const val KEY_INSTALL_REPORTED = "install_reported"
        private const val KEY_DEVICE_TOKEN = "device_token"
    }
}

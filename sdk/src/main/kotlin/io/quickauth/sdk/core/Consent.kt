package io.quickauth.sdk.core

/**
 * DPDP (India) / GDPR (EU) consent gate.
 *
 * Until [set] is called with `true`, [ApiClient] will refuse to send any request that contains
 * personal data (phone number, fingerprint, attribution).  The OTP flow itself is technically
 * "necessary for service" so we still allow it once consent is granted; a stricter policy can
 * subclass and override [allowsRequest].
 */
open class Consent(private val storage: Storage) {

    fun set(granted: Boolean) {
        storage.consentGranted = granted
    }

    fun get(): Boolean = storage.consentGranted

    /**
     * Hook used by [ApiClient] before each call.  Returns `true` if the SDK is allowed to
     * actually hit the network for the supplied [path].  Default behaviour: gate everything
     * except the `auth/...` endpoints, which are required for the user to log in at all.
     */
    open fun allowsRequest(path: String): Boolean {
        if (path.contains("/auth/")) return true
        return get()
    }
}

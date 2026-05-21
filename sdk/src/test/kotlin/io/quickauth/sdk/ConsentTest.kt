package io.quickauth.sdk

import androidx.test.core.app.ApplicationProvider
import io.quickauth.sdk.core.Consent
import io.quickauth.sdk.core.Storage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConsentTest {

    @Test fun `default is false`() {
        val storage = Storage(ApplicationProvider.getApplicationContext())
        storage.clear()
        val consent = Consent(storage)
        assertFalse(consent.get())
    }

    @Test fun `set then get round-trips through SharedPreferences`() {
        val storage = Storage(ApplicationProvider.getApplicationContext())
        storage.clear()
        val consent = Consent(storage)
        consent.set(true)
        assertTrue(consent.get())
        consent.set(false)
        assertFalse(consent.get())
    }

    @Test fun `auth paths are always allowed regardless of consent`() {
        val storage = Storage(ApplicationProvider.getApplicationContext())
        storage.clear()
        val consent = Consent(storage)
        assertTrue(consent.allowsRequest("/v1/sdk/auth/initiate"))
        assertTrue(consent.allowsRequest("/v1/sdk/auth/verify"))
    }

    @Test fun `attribution paths require consent`() {
        val storage = Storage(ApplicationProvider.getApplicationContext())
        storage.clear()
        val consent = Consent(storage)
        consent.set(false)
        assertFalse(consent.allowsRequest("/v1/sdk/attribution/launch"))
        consent.set(true)
        assertTrue(consent.allowsRequest("/v1/sdk/attribution/launch"))
    }
}

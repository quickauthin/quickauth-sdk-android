package io.quickauth.sdk

import io.quickauth.sdk.attribution.DeviceInfo
import io.quickauth.sdk.attribution.Fingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FingerprintTest {

    private val pixel7 = DeviceInfo(
        osName = "Android",
        osVersion = "14",
        manufacturer = "Google",
        model = "Pixel 7",
        locale = "en-IN",
        timezone = "Asia/Kolkata",
        screenWidth = 1080,
        screenHeight = 2400,
        screenDensity = 2.625f,
        sdkVersion = 34,
        isTablet = false,
    )

    @Test fun `same input produces same hash`() {
        val a = Fingerprint.compute(pixel7)
        val b = Fingerprint.compute(pixel7.copy())
        assertEquals(a, b)
    }

    @Test fun `different model produces different hash`() {
        val a = Fingerprint.compute(pixel7)
        val b = Fingerprint.compute(pixel7.copy(model = "Pixel 8"))
        assertNotEquals(a, b)
    }

    @Test fun `manufacturer casing does not affect hash`() {
        val a = Fingerprint.compute(pixel7)
        val b = Fingerprint.compute(pixel7.copy(manufacturer = "GOOGLE"))
        assertEquals(a, b)
    }

    @Test fun `hash is 64-char SHA-256 hex`() {
        val h = Fingerprint.compute(pixel7)
        assertEquals(64, h.length)
        assertEquals(true, h.all { it in '0'..'9' || it in 'a'..'f' })
    }
}

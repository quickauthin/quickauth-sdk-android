package io.quickauth.sdk

import android.os.Looper
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.quickauth.sdk.auth.AuthEvent
import io.quickauth.sdk.auth.AuthEventHandler
import io.quickauth.sdk.auth.OtpService
import io.quickauth.sdk.auth.SmsRetriever
import io.quickauth.sdk.core.ApiClient
import io.quickauth.sdk.core.ApiException
import io.quickauth.sdk.core.Config
import io.quickauth.sdk.core.Storage
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Robolectric is used so `Handler(Looper.getMainLooper())` resolves to a
 * real main looper and `shadowOf(looper).idle()` can flush event delivery
 * synchronously.
 */
@RunWith(RobolectricTestRunner::class)
class OtpServiceTest {

    private val api = mockk<ApiClient>(relaxed = true)
    private val sms = mockk<SmsRetriever>(relaxed = true)
    private var storedDeviceToken: String? = null
    private val storage = mockk<Storage>(relaxed = true).also {
        every { it.deviceToken } answers { storedDeviceToken }
        every { it.deviceToken = any() } answers { storedDeviceToken = arg(0) }
    }
    private val events = mutableListOf<AuthEvent>()
    private val handler: AuthEventHandler = { events.add(it) }
    private val config = Config(onTokenExpiry = { "tkn" }, onAuthEvent = handler)
    private val service = OtpService(api, sms, storage) { config }

    @Before fun setUp() {
        events.clear()
        storedDeviceToken = null
    }

    @After fun tearDown() {
        // Drain the main looper so emit() invocations land before the next test.
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun `accepts E164 phone numbers`() {
        assertTrue(OtpService.isValidE164("+919876543210"))
        assertTrue(OtpService.isValidE164("+14155551234"))
        assertTrue(OtpService.isValidE164("+447911123456"))
    }

    @Test fun `rejects non-E164 phone numbers`() {
        assertFalse(OtpService.isValidE164("9876543210"))
        assertFalse(OtpService.isValidE164("+0123"))
        assertFalse(OtpService.isValidE164("+91 98765 432"))
        assertFalse(OtpService.isValidE164(""))
    }

    @Test fun `initiate throws on invalid phone`() = runTest {
        try {
            service.initiate("9876543210")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("E.164"))
        }
    }

    @Test fun `initiate emits OtpSent on backend OTP_SENT state`() = runTest {
        coEvery {
            api.postJson(any(), any(), OtpService.InitiateResponse::class.java)
        } returns OtpService.InitiateResponse(
            state = "OTP_SENT",
            sessionId = "sess_123",
            expiresIn = 300,
            deviceToken = "dtok_new",
        )

        service.initiate("+919876543210", OtpChannel.WHATSAPP)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, events.size)
        val ev = events[0]
        assertTrue(ev is AuthEvent.OtpSent)
        ev as AuthEvent.OtpSent
        assertEquals("sess_123", ev.sessionId)
        assertEquals(OtpChannel.WHATSAPP, ev.channel)
        assertEquals(300, ev.expiresIn)
        // Device token persisted for next call.
        assertEquals("dtok_new", storedDeviceToken)
    }

    @Test fun `initiate emits Verified directly when backend reports OneTap`() = runTest {
        coEvery {
            api.postJson(any(), any(), OtpService.InitiateResponse::class.java)
        } returns OtpService.InitiateResponse(
            state = "VERIFIED",
            sessionId = "req_verified",
            expiresIn = 300,
            deviceToken = "dtok_v",
        )

        service.initiate("+919876543210")
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, events.size)
        val ev = events[0] as AuthEvent.Verified
        assertEquals("req_verified", ev.requestId)
    }

    @Test fun `initiate replays stored device token on subsequent calls`() = runTest {
        storedDeviceToken = "dtok_existing"
        val bodySlot = slot<Map<String, Any>>()
        coEvery {
            api.postJson(any(), capture(bodySlot), OtpService.InitiateResponse::class.java)
        } returns OtpService.InitiateResponse(
            state = "OTP_SENT",
            sessionId = "sess_1",
            expiresIn = 300,
            deviceToken = "dtok_existing",
        )

        service.initiate("+919876543210")

        assertEquals("dtok_existing", bodySlot.captured["deviceToken"])
    }

    @Test fun `submitOtp emits Verified on success and forwards device token`() = runTest {
        coEvery {
            api.postJson(any(), any(), OtpService.InitiateResponse::class.java)
        } returns OtpService.InitiateResponse(
            state = "OTP_SENT",
            sessionId = "sess_1",
            expiresIn = 300,
            deviceToken = "dtok_v",
        )
        val verifyBody = slot<Map<String, Any>>()
        coEvery {
            api.postJson(eq("/v1/sdk/auth/verify"), capture(verifyBody), OtpService.VerifyResponse::class.java)
        } returns OtpService.VerifyResponse(
            state = "VERIFIED",
            verified = true,
            requestId = "req_abc",
            message = "Verified successfully",
        )

        service.initiate("+919876543210")
        service.submitOtp("123456")
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf("OtpSent", "Verified"), events.map { it::class.simpleName })
        assertEquals("sess_1", verifyBody.captured["sessionId"])
        assertEquals("123456", verifyBody.captured["code"])
        assertEquals("dtok_v", verifyBody.captured["deviceToken"])
    }

    @Test fun `submitOtp emits OtpFailed on wrong code and remains retry-able`() = runTest {
        coEvery {
            api.postJson(any(), any(), OtpService.InitiateResponse::class.java)
        } returns OtpService.InitiateResponse(
            state = "OTP_SENT",
            sessionId = "sess_1",
            expiresIn = 300,
            deviceToken = "dtok_v",
        )
        coEvery {
            api.postJson(eq("/v1/sdk/auth/verify"), any(), OtpService.VerifyResponse::class.java)
        } returnsMany listOf(
            OtpService.VerifyResponse(
                state = "OTP_FAILED",
                verified = false,
                requestId = "sess_1",
                message = "Invalid OTP. 2 attempt(s) remaining.",
            ),
            OtpService.VerifyResponse(
                state = "VERIFIED",
                verified = true,
                requestId = "req_abc",
                message = "Verified successfully",
            ),
        )

        service.initiate("+919876543210")
        service.submitOtp("000000")
        service.submitOtp("123456")
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf("OtpSent", "OtpFailed", "Verified"), events.map { it::class.simpleName })
    }

    @Test fun `submitOtp before initiate throws`() = runTest {
        try {
            service.submitOtp("123456")
            fail("expected IllegalStateException")
        } catch (_: IllegalStateException) { /* expected */ }
    }

    @Test fun `submitOtp rejects bad code`() = runTest {
        try {
            service.submitOtp("abc")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("digits"))
        }
    }

    @Test fun `reset with forgetDevice clears the device token`() = runTest {
        storedDeviceToken = "dtok_zap"

        service.reset(forgetDevice = true)

        assertNull(storedDeviceToken)
    }

    @Test fun `reset without forgetDevice keeps the device token`() = runTest {
        storedDeviceToken = "dtok_keep"

        service.reset()

        assertEquals("dtok_keep", storedDeviceToken)
    }

    @Test fun `initiate emits Error and rethrows on transport failure`() = runTest {
        coEvery {
            api.postJson(any(), any(), OtpService.InitiateResponse::class.java)
        } throws ApiException(500, "boom", "{}")

        try {
            service.initiate("+919876543210")
            fail("expected ApiException")
        } catch (_: ApiException) { /* expected */ }
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, events.size)
        val err = events[0] as AuthEvent.Error
        assertEquals("SERVER_ERROR", err.code)
    }

    @Test fun `publishAutoReadCode surfaces OtpAutoRead event`() {
        service.publishAutoReadCode("987654")
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, events.size)
        val ev = events[0] as AuthEvent.OtpAutoRead
        assertEquals("987654", ev.code)
    }
}

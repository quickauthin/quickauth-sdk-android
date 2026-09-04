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
import io.quickauth.sdk.auth.WhatsAppOtpRetriever
import io.quickauth.sdk.core.ApiClient
import io.quickauth.sdk.core.ApiException
import io.quickauth.sdk.core.Config
import io.quickauth.sdk.core.Storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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

    // ======================================================================
    // Flutter-parity surface: resendOtp, self-armed auto-read, autoSubmit,
    // and the WhatsApp handshake.
    // ======================================================================

    private val smsCodes = MutableSharedFlow<String>(extraBufferCapacity = 8)
    private val waCodes = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Ordered log of the side effects whose sequence actually matters. */
    private val calls = mutableListOf<String>()

    private val whatsApp = mockk<WhatsAppOtpRetriever>().also {
        every { it.observe() } returns waCodes
        every { it.clearPending() } answers { calls.add("clearPending") }
        every { it.sendHandshake() } answers { calls.add("handshake"); "req-1" }
    }

    /** Bodies of every `/v1/sdk/auth/initiate` POST, in order. */
    private val initiateBodies = mutableListOf<Map<String, Any>>()

    /**
     * A service whose auto-read subscription and auto-submit run on the test's own scheduler,
     * so a code emitted below is handled before the assertion instead of on a background
     * thread the test would have to poll for.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.parityService(): OtpService {
        every { sms.observe() } returns smsCodes
        return OtpService(
            api,
            sms,
            storage,
            whatsApp,
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        ) { config }
    }

    private fun stubInitiate(sessionId: String = "sess_1") {
        coEvery {
            api.postJson(eq("/v1/sdk/auth/initiate"), capture(initiateBodies), OtpService.InitiateResponse::class.java)
        } answers {
            calls.add("initiate")
            OtpService.InitiateResponse(
                state = "OTP_SENT",
                sessionId = sessionId,
                expiresIn = 300,
                deviceToken = null,
            )
        }
    }

    private fun stubVerify() {
        coEvery {
            api.postJson(eq("/v1/sdk/auth/verify"), any(), OtpService.VerifyResponse::class.java)
        } answers {
            calls.add("verify")
            OtpService.VerifyResponse(
                state = "VERIFIED",
                verified = true,
                requestId = "req_abc",
                message = "Verified successfully",
            )
        }
    }

    // -- resendOtp ----------------------------------------------------------

    @Test fun `resendOtp replays the phone, channel and autoSubmit of the live attempt`() = runTest {
        stubInitiate()
        val svc = parityService()

        svc.initiate("+919876543210", OtpChannel.WHATSAPP, autoSubmit = true)
        svc.resendOtp()

        assertEquals(2, initiateBodies.size)
        assertEquals("+919876543210", initiateBodies[1]["phone"])
        assertEquals("whatsapp", initiateBodies[1]["channel"])
        // autoSubmit is not on the wire — it is proven by the resend still auto-submitting.
        stubVerify()
        waCodes.emit("445566")
        advanceUntilIdle()
        assertTrue(calls.contains("verify"))
    }

    @Test fun `resendOtp re-sends the WhatsApp handshake, which Meta expires after ten minutes`() = runTest {
        stubInitiate()
        val svc = parityService()

        svc.initiate("+919876543210")
        svc.resendOtp()

        assertEquals(2, calls.count { it == "handshake" })
    }

    @Test fun `resendOtp before any attempt throws`() = runTest {
        val svc = parityService()
        try {
            svc.resendOtp()
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("nothing to resend"))
        }
    }

    @Test fun `resendOtp after reset throws — the attempt is over`() = runTest {
        stubInitiate()
        val svc = parityService()

        svc.initiate("+919876543210")
        svc.reset()

        try {
            svc.resendOtp()
            fail("expected IllegalStateException")
        } catch (_: IllegalStateException) { /* expected */ }
    }

    // -- initiate arms auto-read itself -------------------------------------

    @Test fun `initiate arms auto-read without the caller ever collecting observeOTP`() = runTest {
        // The most-missed piece: a merchant told they need not subscribe got nothing at all,
        // because the code was received, held, and never delivered.
        stubInitiate()
        val svc = parityService()

        svc.initiate("+919876543210")
        smsCodes.emit("483920")
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf("OtpSent", "OtpAutoRead"), events.map { it::class.simpleName })
        assertEquals("483920", (events[1] as AuthEvent.OtpAutoRead).code)
    }

    @Test fun `auto-read merges WhatsApp as well as SMS`() = runTest {
        // Listening to only SMS — all that was possible before — means a merchant on AUTO gets
        // auto-read for some users and not others, with nothing to explain the difference.
        stubInitiate()
        val svc = parityService()

        svc.initiate("+919876543210", OtpChannel.AUTO)
        waCodes.emit("112233")
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf("OtpSent", "OtpAutoRead"), events.map { it::class.simpleName })
        assertEquals("112233", (events[1] as AuthEvent.OtpAutoRead).code)
    }

    @Test fun `a resend does not stack auto-read subscriptions`() = runTest {
        stubInitiate()
        val svc = parityService()

        svc.initiate("+919876543210")
        svc.resendOtp()
        smsCodes.emit("483920")
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()

        // One OtpAutoRead, not one per attempt ever started.
        assertEquals(1, events.count { it is AuthEvent.OtpAutoRead })
    }

    @Test fun `reset stops auto-read`() = runTest {
        stubInitiate()
        val svc = parityService()

        svc.initiate("+919876543210")
        svc.reset()
        events.clear()
        smsCodes.emit("483920")
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(events.isEmpty())
    }

    // -- autoSubmit + one-shot latch ----------------------------------------

    @Test fun `autoSubmit is off by default`() = runTest {
        stubInitiate()
        stubVerify()
        val svc = parityService()

        svc.initiate("+919876543210")
        smsCodes.emit("483920")
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse("verify must not be called unless autoSubmit was asked for", calls.contains("verify"))
        assertEquals(listOf("OtpSent", "OtpAutoRead"), events.map { it::class.simpleName })
    }

    @Test fun `autoSubmit verifies the auto-read code`() = runTest {
        stubInitiate()
        stubVerify()
        val svc = parityService()

        svc.initiate("+919876543210", autoSubmit = true)
        smsCodes.emit("483920")
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            listOf("OtpSent", "OtpAutoRead", "Verified"),
            events.map { it::class.simpleName },
        )
    }

    @Test fun `the one-shot latch stops the SMS and WhatsApp copies both submitting`() = runTest {
        // A merchant on AUTO can get the same code twice. The second submit would verify a code
        // the server has already consumed, surfacing as a spurious failure right after success.
        stubInitiate()
        stubVerify()
        val svc = parityService()

        svc.initiate("+919876543210", OtpChannel.AUTO, autoSubmit = true)
        smsCodes.emit("483920")
        waCodes.emit("483920")
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, calls.count { it == "verify" })
        // Both codes still surface as auto-read; only the submit is latched.
        assertEquals(2, events.count { it is AuthEvent.OtpAutoRead })
        assertEquals(1, events.count { it is AuthEvent.Verified })
    }

    @Test fun `the latch is per attempt, so a resend can auto-submit again`() = runTest {
        stubInitiate()
        stubVerify()
        val svc = parityService()

        svc.initiate("+919876543210", autoSubmit = true)
        smsCodes.emit("483920")
        advanceUntilIdle()
        svc.resendOtp()
        smsCodes.emit("112233")
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(2, calls.count { it == "verify" })
    }

    @Test fun `publishAutoReadCode honours the same latch`() = runTest {
        stubInitiate()
        stubVerify()
        val svc = parityService()

        svc.initiate("+919876543210", autoSubmit = true)
        svc.publishAutoReadCode("483920")
        svc.publishAutoReadCode("483920")
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, calls.count { it == "verify" })
    }

    // -- WhatsApp handshake ordering ----------------------------------------

    @Test fun `the handshake goes out before the OTP is requested`() = runTest {
        // WhatsApp checks for a live handshake when it receives the template. One sent
        // afterwards is too late for the message already in flight, and the failure is
        // invisible: the message shows, the code is never broadcast, nothing errors.
        stubInitiate()
        val svc = parityService()

        svc.initiate("+919876543210")

        assertEquals(listOf("clearPending", "handshake", "initiate"), calls)
    }

    @Test fun `a code held from an abandoned attempt is dropped before the new one starts`() = runTest {
        stubInitiate()
        val svc = parityService()

        svc.initiate("+919876543210")

        verify { whatsApp.clearPending() }
        assertTrue(calls.indexOf("clearPending") < calls.indexOf("initiate"))
    }

    @Test fun `a WhatsApp failure does not stop the OTP being sent`() = runTest {
        // A missing handshake costs auto-read, not the login.
        every { whatsApp.sendHandshake() } returns null
        stubInitiate()
        val svc = parityService()

        svc.initiate("+919876543210")
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf("OtpSent"), events.map { it::class.simpleName })
    }
}

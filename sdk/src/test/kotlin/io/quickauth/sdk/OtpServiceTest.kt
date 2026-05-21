package io.quickauth.sdk

import io.mockk.coEvery
import io.mockk.mockk
import io.quickauth.sdk.auth.OtpService
import io.quickauth.sdk.auth.SmsRetriever
import io.quickauth.sdk.core.ApiClient
import io.quickauth.sdk.core.ApiException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OtpServiceTest {

    private val api = mockk<ApiClient>(relaxed = true)
    private val sms = mockk<SmsRetriever>(relaxed = true)
    private val service = OtpService(api, sms)

    @Test fun `accepts E164 phone numbers`() {
        assertTrue(OtpService.isValidE164("+919876543210"))
        assertTrue(OtpService.isValidE164("+14155551234"))
        assertTrue(OtpService.isValidE164("+447911123456"))
    }

    @Test fun `rejects non-E164 phone numbers`() {
        assertFalse(OtpService.isValidE164("9876543210"))    // missing +
        assertFalse(OtpService.isValidE164("+0123"))         // leading zero
        assertFalse(OtpService.isValidE164("+91 98765 432")) // spaces
        assertFalse(OtpService.isValidE164(""))
    }

    @Test fun `startOTP throws on invalid phone`() = runTest {
        try {
            service.startOTP("9876543210")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("E.164"))
        }
    }

    @Test fun `startOTP happy path returns session`() = runTest {
        coEvery {
            api.postJson(any(), any(), OtpService.InitiateResponse::class.java)
        } returns OtpService.InitiateResponse(sessionId = "sess_123", expiresIn = 300)

        val s = service.startOTP("+919876543210", OtpChannel.WHATSAPP)

        assertEquals("sess_123", s.sessionId)
        assertEquals(300, s.expiresIn)
    }

    @Test fun `verifyOTP rejects bad code`() = runTest {
        try {
            service.verifyOTP("sess_123", "abc")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("digits"))
        }
    }

    @Test fun `verifyOTP propagates 4xx as ApiException`() = runTest {
        coEvery {
            api.postJson(any(), any(), OtpService.VerifyResponse::class.java)
        } throws ApiException(400, "bad code", "{}")

        try {
            service.verifyOTP("sess_123", "999999")
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(400, e.code)
        }
    }
}

package io.quickauth.sdk

import android.content.Intent
import io.quickauth.sdk.auth.WhatsAppOtpReceiver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class WhatsAppOtpReceiverTest {

    private val receiver = WhatsAppOtpReceiver()
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun setUp() = WhatsAppOtpReceiver.resetForTesting()

    @After fun tearDown() = WhatsAppOtpReceiver.resetForTesting()

    private fun broadcast(
        code: String?,
        action: String = WhatsAppOtpReceiver.ACTION_OTP_RETRIEVED,
        requestId: String? = "req-1",
    ) {
        val intent = Intent(action)
        if (code != null) intent.putExtra(WhatsAppOtpReceiver.EXTRA_CODE, code)
        if (requestId != null) intent.putExtra(WhatsAppOtpReceiver.EXTRA_REQUEST_ID, requestId)
        receiver.onReceive(context, intent)
    }

    @Test fun `delivers a code to a live listener`() {
        val seen = mutableListOf<String>()
        WhatsAppOtpReceiver.addListener { seen.add(it) }

        broadcast("483920")

        assertEquals(listOf("483920"), seen)
    }

    @Test fun `holds a code that arrives before anything is listening and flushes it on subscribe`() {
        // The zero-tap case this whole path exists for: the broadcast can land before the app
        // is running, let alone before an OTP screen has subscribed.
        broadcast("112233")

        val seen = mutableListOf<String>()
        WhatsAppOtpReceiver.addListener { seen.add(it) }

        assertEquals(listOf("112233"), seen)
    }

    @Test fun `a held code is delivered once, not replayed to every later subscriber`() {
        broadcast("112233")

        val first = mutableListOf<String>()
        val second = mutableListOf<String>()
        WhatsAppOtpReceiver.addListener { first.add(it) }
        WhatsAppOtpReceiver.addListener { second.add(it) }

        assertEquals(listOf("112233"), first)
        assertTrue(second.isEmpty())
    }

    @Test fun `fans out to every listener`() {
        // Two subscribers is the normal case: initiate() arms auto-read for the caller while
        // the merchant's OTP field may also be collecting observeOTP().
        val service = mutableListOf<String>()
        val merchant = mutableListOf<String>()
        WhatsAppOtpReceiver.addListener { service.add(it) }
        WhatsAppOtpReceiver.addListener { merchant.add(it) }

        broadcast("445566")

        assertEquals(listOf("445566"), service)
        assertEquals(listOf("445566"), merchant)
    }

    @Test fun `a throwing listener does not stop the others`() {
        val survivor = mutableListOf<String>()
        WhatsAppOtpReceiver.addListener { error("merchant bug") }
        WhatsAppOtpReceiver.addListener { survivor.add(it) }

        broadcast("445566")

        assertEquals(listOf("445566"), survivor)
    }

    @Test fun `removing a listener stops delivery to it`() {
        val seen = mutableListOf<String>()
        val listener: (String) -> Unit = { seen.add(it) }
        WhatsAppOtpReceiver.addListener(listener)
        WhatsAppOtpReceiver.removeListener(listener)

        broadcast("445566")

        assertTrue(seen.isEmpty())
    }

    @Test fun `clearPending drops a code held from an abandoned attempt`() {
        broadcast("112233")
        WhatsAppOtpReceiver.clearPending()

        val seen = mutableListOf<String>()
        WhatsAppOtpReceiver.addListener { seen.add(it) }

        assertTrue(seen.isEmpty())
    }

    @Test fun `a newer held code replaces an older one`() {
        broadcast("111111")
        broadcast("222222")

        val seen = mutableListOf<String>()
        WhatsAppOtpReceiver.addListener { seen.add(it) }

        assertEquals(listOf("222222"), seen)
    }

    @Test fun `ignores a broadcast with no code`() {
        val seen = mutableListOf<String>()
        WhatsAppOtpReceiver.addListener { seen.add(it) }

        broadcast(null)
        broadcast("")
        broadcast("   ")

        assertTrue(seen.isEmpty())
    }

    @Test fun `ignores an implausible code`() {
        val seen = mutableListOf<String>()
        WhatsAppOtpReceiver.addListener { seen.add(it) }

        broadcast("abc123")
        broadcast("12")
        broadcast("12345678901")

        assertTrue(seen.isEmpty())
    }

    @Test fun `accepts the full length range Meta allows`() {
        val seen = mutableListOf<String>()
        WhatsAppOtpReceiver.addListener { seen.add(it) }

        broadcast("1234")
        broadcast("1234567890")

        assertEquals(listOf("1234", "1234567890"), seen)
    }

    @Test fun `trims whitespace off the code`() {
        // A padded code fails verification for no visible reason.
        val seen = mutableListOf<String>()
        WhatsAppOtpReceiver.addListener { seen.add(it) }

        broadcast(" 483920\n")

        assertEquals(listOf("483920"), seen)
    }

    @Test fun `ignores a broadcast for a different action`() {
        val seen = mutableListOf<String>()
        WhatsAppOtpReceiver.addListener { seen.add(it) }

        broadcast("483920", action = "com.whatsapp.otp.SOMETHING_ELSE")

        assertTrue(seen.isEmpty())
    }

    @Test fun `delivers even without a handshake request id`() {
        // The request_id is passed through by Meta's handshake; a code without one is still a
        // code, and dropping it would break every merchant whose template predates the
        // handshake.
        val seen = mutableListOf<String>()
        WhatsAppOtpReceiver.addListener { seen.add(it) }

        broadcast("483920", requestId = null)

        assertEquals(listOf("483920"), seen)
    }
}

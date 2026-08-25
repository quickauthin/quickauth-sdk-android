package io.quickauth.sdk.auth

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * How a WhatsApp code reaches a listener that may not exist yet.
 *
 * Zero-tap's promise is that the user does nothing, so the code can arrive while the app is
 * backgrounded or not running. Dropping it in that window would make the feature work only for
 * users who already had the app open — the case that needs it least.
 */
class WhatsAppOtpReceiverTest {

    @After
    fun tearDown() {
        WhatsAppOtpReceiver.setListener(null)
        WhatsAppOtpReceiver.clearPending()
    }

    @Test
    fun `delivers straight through when something is listening`() {
        var seen: String? = null
        WhatsAppOtpReceiver.setListener { seen = it }

        WhatsAppOtpReceiver.deliver("483920")

        assertEquals("483920", seen)
    }

    @Test
    fun `holds a code that arrives before anything listens, then flushes it`() {
        WhatsAppOtpReceiver.deliver("112233")

        var seen: String? = null
        WhatsAppOtpReceiver.setListener { seen = it }

        assertEquals("112233", seen)
    }

    @Test
    fun `a held code is delivered once, not replayed on every re-listen`() {
        WhatsAppOtpReceiver.deliver("445566")

        var first: String? = null
        WhatsAppOtpReceiver.setListener { first = it }
        WhatsAppOtpReceiver.setListener(null)

        var second: String? = null
        WhatsAppOtpReceiver.setListener { second = it }

        assertEquals("445566", first)
        // A re-listen after a restart must not resurrect a code the user already spent.
        assertNull(second)
    }

    @Test
    fun `a newer code replaces an older held one`() {
        // What a user requesting a second OTP expects, and it keeps the slot bounded.
        WhatsAppOtpReceiver.deliver("111111")
        WhatsAppOtpReceiver.deliver("222222")

        var seen: String? = null
        WhatsAppOtpReceiver.setListener { seen = it }

        assertEquals("222222", seen)
    }

    @Test
    fun `clearPending drops a code from an abandoned attempt`() {
        // initiate() calls this: delivering a code held from an earlier request against a new
        // one fails verification for reasons the user cannot see.
        WhatsAppOtpReceiver.deliver("999999")
        WhatsAppOtpReceiver.clearPending()

        var seen: String? = null
        WhatsAppOtpReceiver.setListener { seen = it }

        assertNull(seen)
    }
}

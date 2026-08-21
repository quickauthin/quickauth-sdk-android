package io.quickauth.sdk.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [SmsRetriever.extractCode].
 *
 * The bug these guard against: picking the *first* 4-8 digit run auto-filled order numbers,
 * ticket ids and amounts instead of the OTP, because Indian senders overwhelmingly put the
 * reference number before the code.
 */
class SmsRetrieverTest {

    private fun extract(message: String): String? = SmsRetriever.extractCode(message)

    // --- The regression that motivated the fix -------------------------------------------

    @Test fun `prefers the keyword-anchored code over a leading order number`() {
        assertEquals("483920", extract("Your OTP for order 4471029 is 483920"))
    }

    @Test fun `ignores a trailing reference number after the code`() {
        assertEquals("483920", extract("Your OTP is 483920. Ref 88123456 - do not share."))
    }

    @Test fun `picks the last code when several numbers precede it`() {
        assertEquals(
            "112233",
            extract("Ticket 5551234 for booking 909090 confirmed. Enter 112233 to check in."),
        )
    }

    // --- Ordinary bodies -----------------------------------------------------------------

    @Test fun `extracts a plain OTP message`() {
        assertEquals("483920", extract("Your OTP is 483920"))
    }

    @Test fun `extracts codes introduced by each supported keyword`() {
        assertEquals("483920", extract("Your verification code is 483920"))
        assertEquals("4321", extract("Use PIN 4321 to continue"))
        assertEquals("135790", extract("One time password: 135790"))
        assertEquals("246813", extract("OTP-246813 is valid for 10 minutes"))
    }

    @Test fun `extracts a code that precedes the keyword`() {
        assertEquals("483920", extract("483920 is your QuickAuth verification code."))
    }

    @Test fun `honours the code length bounds`() {
        assertEquals("1234", extract("Your OTP is 1234"))
        assertEquals("12345678", extract("Your OTP is 12345678"))
        assertEquals("008421", extract("Your OTP is 008421")) // leading zeros survive
    }

    // --- Numbers that must never be mistaken for the code --------------------------------

    @Test fun `ignores the 11-char SMS Retriever app hash`() {
        // The hash is base64, so a digit run flanked by '+' or '/' looks exactly like a
        // standalone code to a naive scanner.
        assertEquals("483920", extract("<#> Your QuickAuth code is 483920\naB3/1234+xy"))
    }

    @Test fun `ignores the app hash even with no keyword to anchor on`() {
        assertEquals("483920", extract("<#> 483920\naB3/1234+xy"))
    }

    @Test fun `ignores a phone number in the body`() {
        assertEquals(
            "483920",
            extract("Call 9876543210 if this wasn't you. Your OTP is 483920"),
        )
    }

    @Test fun `ignores an E164 phone number in the body`() {
        assertEquals(
            "483920",
            extract("Login requested for +919876543210. Code: 483920"),
        )
    }

    @Test fun `ignores a phone number when there is no keyword`() {
        assertNull(extract("Reach us on 9876543210 anytime."))
    }

    // --- Nothing to extract --------------------------------------------------------------

    @Test fun `returns null when the body has no digits`() {
        assertNull(extract("Welcome to QuickAuth!"))
    }

    @Test fun `returns null for digit runs that are too short`() {
        assertNull(extract("Only 123 seats left"))
    }

    @Test fun `returns null for an empty body`() {
        assertNull(extract(""))
    }
}

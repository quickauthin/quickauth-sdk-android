package io.quickauth.sdk

import io.quickauth.sdk.auth.SmsRetriever
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.MessageDigest

/**
 * Robolectric because [SmsRetriever.computeAppHash] uses `android.util.Base64`, which is a stub
 * that throws on a bare JVM.
 */
@RunWith(RobolectricTestRunner::class)
class SmsRetrieverTest {

    // -- extractCode --------------------------------------------------------

    @Test fun `takes the keyword-anchored code, not the order number in front of it`() {
        // The bug this exists for: extraction used to return the FIRST digit run, so the user
        // watched the order number appear in the OTP field and then be rejected.
        assertEquals(
            "483920",
            SmsRetriever.extractCode("Your OTP for order 4471029 is 483920"),
        )
    }

    @Test fun `takes the code after an amount`() {
        assertEquals(
            "778213",
            SmsRetriever.extractCode("Paying 2499 to Acme. Confirm with code 778213"),
        )
    }

    @Test fun `handles the common keyword shapes`() {
        assertEquals("4821", SmsRetriever.extractCode("code: 4821"))
        assertEquals("483920", SmsRetriever.extractCode("Your OTP is 483920"))
        assertEquals("112233", SmsRetriever.extractCode("PIN - 112233"))
        assertEquals("998877", SmsRetriever.extractCode("your password is 998877"))
        assertEquals("123456", SmsRetriever.extractCode("OTP 123456"))
    }

    @Test fun `keyword matching is case-insensitive`() {
        assertEquals("246810", SmsRetriever.extractCode("YOUR OTP IS 246810"))
    }

    @Test fun `falls back to the LAST standalone run when no keyword anchors it`() {
        // Senders put reference numbers and amounts ahead of the code far more often than after.
        assertEquals("9182", SmsRetriever.extractCode("Ref 5566 — use 9182 to continue"))
    }

    @Test fun `skips digit runs longer than a code`() {
        // A 10-digit mobile number must not be truncated into something code-shaped.
        assertNull(SmsRetriever.extractCode("Call us on 9876543210"))
        assertEquals(
            "445566",
            SmsRetriever.extractCode("Call 9876543210 or verify with 445566"),
        )
    }

    @Test fun `ignores the trailing app hash`() {
        // The 11-char hash is base64 over [A-Za-z0-9+/], so it can end in a digit run flanked by
        // + or / that reads exactly like a standalone code.
        assertEquals(
            "483920",
            SmsRetriever.extractCode("<#> Your OTP is 483920\nFA+9qCX9VSu"),
        )
        assertEquals(
            "483920",
            SmsRetriever.extractCode("<#> Your login code 483920\nab/12345/xy"),
        )
    }

    @Test fun `returns null when there is nothing code-shaped`() {
        assertNull(SmsRetriever.extractCode("Welcome to Acme!"))
        assertNull(SmsRetriever.extractCode(""))
    }

    // -- computeAppHash -----------------------------------------------------

    /**
     * Google's `AppSignatureHelper`, transcribed. The point of restating it here rather than
     * asserting a frozen string is that the frozen string is what the old implementation would
     * have produced too — a well-formed 11 characters that simply are not the hash the platform
     * computes, which is why SMS auto-read never fired and never errored.
     */
    private fun googleReferenceHash(pkg: String, signature: String): String {
        val appInfo = "$pkg $signature"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(appInfo.toByteArray(Charsets.UTF_8))
            .copyOfRange(0, 9)
        return android.util.Base64
            .encodeToString(digest, android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
            .substring(0, 11)
    }

    /** A `Signature.toCharsString()` value: lower-case hex of the certificate's DER bytes. */
    private val certCharsString =
        "308201dd30820146020101300d06092a864886f70d010105050030373116301406035504030c0d416e64726f69642044656275"

    @Test fun `matches Google's documented algorithm`() {
        assertEquals(
            googleReferenceHash("io.quickauth.sample", certCharsString),
            SmsRetriever.computeAppHash("io.quickauth.sample", certCharsString),
        )
    }

    @Test fun `hashes the certificate itself, not the SHA-256 of the certificate`() {
        // The old implementation fed in hex(SHA-256(certBytes)) instead of the certificate's own
        // toCharsString(). Both produce 11 plausible characters; only one of them is the value
        // the platform matches against, so the difference showed up as "auto-read just doesn't
        // work" with nothing logged anywhere.
        val certBytes = certCharsString.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val oldWrongInput = MessageDigest.getInstance("SHA-256")
            .digest(certBytes)
            .joinToString("") { "%02X".format(it) }

        val correct = SmsRetriever.computeAppHash("io.quickauth.sample", certCharsString)
        val wrong = SmsRetriever.computeAppHash("io.quickauth.sample", oldWrongInput)

        assertNotEquals(correct, wrong)
        assertEquals(googleReferenceHash("io.quickauth.sample", certCharsString), correct)
    }

    @Test fun `is 11 base64 characters`() {
        val hash = SmsRetriever.computeAppHash("io.quickauth.sample", certCharsString)
        assertEquals(11, hash.length)
        assertTrue(hash, hash.matches(Regex("^[A-Za-z0-9+/]{11}$")))
    }

    @Test fun `depends on the package name`() {
        assertNotEquals(
            SmsRetriever.computeAppHash("io.quickauth.sample", certCharsString),
            SmsRetriever.computeAppHash("io.quickauth.other", certCharsString),
        )
    }
}

package io.quickauth.sdk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Auth-mode selection rules — exactly one of publishableKey / onTokenExpiry. */
class ConfigTest {

    @Test fun `publishable key alone selects publishable-key mode`() {
        val config = Config(publishableKey = "pk_live_abc")
        assertTrue(config.isPublishableKeyMode)
        assertFalse(config.isUnsafeDirectMode)
        assertEquals("pk_live_abc", config.publishableKey)
    }

    @Test fun `token provider alone stays in session mode`() {
        assertFalse(Config(onTokenExpiry = { "tkn" }).isPublishableKeyMode)
    }

    @Test fun `unsafe direct credentials alone stay in session mode`() {
        val config = Config(
            unsafeDirectClientId = "cid",
            unsafeDirectClientSecret = "csecret",
        )
        assertTrue(config.isUnsafeDirectMode)
        assertFalse(config.isPublishableKeyMode)
    }

    @Test fun `rejects a config with no auth mode at all`() {
        assertRejected { Config() }
    }

    @Test fun `rejects a blank publishable key as no auth mode`() {
        assertRejected { Config(publishableKey = "   ") }
    }

    @Test fun `rejects both auth modes at once`() {
        assertRejected { Config(publishableKey = "pk_live_abc", onTokenExpiry = { "tkn" }) }
    }

    @Test fun `rejects a publishable key combined with unsafe direct credentials`() {
        assertRejected {
            Config(
                publishableKey = "pk_live_abc",
                unsafeDirectClientId = "cid",
                unsafeDirectClientSecret = "csecret",
            )
        }
    }

    @Test fun `still rejects a half-configured unsafe direct pair`() {
        assertRejected { Config(onTokenExpiry = { "tkn" }, unsafeDirectClientId = "cid") }
    }

    @Test fun `still rejects a relative base url`() {
        assertRejected { Config(apiBaseUrl = "api.quickauth.in", publishableKey = "pk_live_abc") }
    }

    private fun assertRejected(build: () -> Config) {
        try {
            build()
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue("error message must explain the problem", e.message?.isNotBlank() == true)
        }
    }
}

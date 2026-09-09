package com.phonecontrol.assistant.developer

import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DhdTaskDisplayBackendTest {
    @Test
    fun `recognizes native missing-session errors including wrapped failures`() {
        assertTrue(isNativeDisplaySessionMissing(IOException("DHD display session is not active.")))
        assertTrue(
            isNativeDisplaySessionMissing(
                IllegalStateException(
                    "preview attach failed",
                    IOException("The virtual display session is not active."),
                ),
            ),
        )
    }

    @Test
    fun `does not classify unrelated preview failures as missing sessions`() {
        assertFalse(isNativeDisplaySessionMissing(IOException("The AVC stream timed out.")))
    }
}

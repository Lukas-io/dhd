package com.phonecontrol.assistant.developer

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun `recognizes the target app task on the requested display`() {
        val dump = """
            Display #0 (activities from top to bottom):
              mResumedActivity: ActivityRecord{a com.example.other/.Main}
            Display #7 (activities from top to bottom):
              Task{42 #42 type=standard A=com.example.target}
                ActivityRecord{b com.example.target/.MainActivity}
        """.trimIndent()

        assertEquals(
            true,
            parseDisplayTaskPresence(dump, displayId = 7, packageName = "com.example.target"),
        )
    }

    @Test
    fun `reports a listed display without the target app as missing`() {
        val dump = """
            Display #7 (activities from top to bottom):
              Task{42 #42 type=standard A=com.android.launcher}
                ActivityRecord{b com.android.launcher/.Launcher}
        """.trimIndent()

        assertEquals(
            false,
            parseDisplayTaskPresence(dump, displayId = 7, packageName = "com.example.target"),
        )
    }

    @Test
    fun `treats an unrecognizable display section as unknown`() {
        assertNull(
            parseDisplayTaskPresence(
                "Display #2 (activities from top to bottom):",
                displayId = 7,
                packageName = "com.example.target",
            ),
        )
    }

    @Test
    fun `does not match a package prefix from another app`() {
        val dump = """
            Display #7 (activities from top to bottom):
              ActivityRecord{b com.example.target.child/.MainActivity}
        """.trimIndent()

        assertEquals(
            false,
            parseDisplayTaskPresence(dump, displayId = 7, packageName = "com.example.target"),
        )
    }
}

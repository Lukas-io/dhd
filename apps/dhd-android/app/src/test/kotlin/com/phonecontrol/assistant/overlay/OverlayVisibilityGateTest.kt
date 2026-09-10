package com.phonecontrol.assistant.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayVisibilityGateTest {
    @Test
    fun `visibility remains hidden until every owner releases`() {
        val gate = OverlayVisibilityGate()
        val observation = gate.acquire(OverlayHideReason.OBSERVATION)
        val activity = gate.acquire(OverlayHideReason.DHD_ACTIVITY)

        assertTrue(gate.hidden.value)
        observation.close()
        assertTrue(gate.hidden.value)
        activity.close()
        assertFalse(gate.hidden.value)
        activity.close()
        assertFalse(gate.hidden.value)
    }

    @Test
    fun `bubble position is clamped inside display and bottom inset`() {
        assertEquals(
            BubblePosition(x = 12, y = 12),
            clampBubblePosition(
                x = -100,
                y = -100,
                displayWidth = 1080,
                displayHeight = 2400,
                bubbleWidth = 64,
                bubbleHeight = 64,
                bottomInset = 120,
            ),
        )
        assertEquals(
            BubblePosition(x = 12, y = 112),
            clampBubblePosition(
                x = 0,
                y = 0,
                displayWidth = 1080,
                displayHeight = 2400,
                bubbleWidth = 64,
                bubbleHeight = 64,
                topInset = 100,
                bottomInset = 120,
            ),
        )
        assertEquals(
            BubblePosition(x = 1004, y = 2204),
            clampBubblePosition(
                x = 5000,
                y = 5000,
                displayWidth = 1080,
                displayHeight = 2400,
                bubbleWidth = 64,
                bubbleHeight = 64,
                bottomInset = 120,
            ),
        )
    }
}

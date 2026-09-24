package com.phonecontrol.assistant.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class PointerEventsTest {
    @Test
    fun `swipe event preserves coordinates and display geometry`() {
        val event = TaskPointerEvent.Swipe(
            sequence = 7L,
            sessionId = "session-1",
            startX = 120,
            startY = 900,
            endX = 120,
            endY = 420,
            durationMs = 400L,
            displayWidth = 720,
            displayHeight = 1560,
        )

        assertEquals(120, event.startX)
        assertEquals(900, event.startY)
        assertEquals(120, event.endX)
        assertEquals(420, event.endY)
        assertEquals(400L, event.durationMs)
        assertEquals(720, event.displayWidth)
        assertEquals(1560, event.displayHeight)
    }
}

package com.phonecontrol.assistant.overlay

import com.phonecontrol.assistant.session.SessionState
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

    @Test
    fun `active updates preserve an explicit bubble collapse`() {
        val first = runningState("Opening the app")
        val next = first.copy(currentPurpose = "Tapping the search field")

        assertEquals(
            OverlayPanelMode.BUBBLE,
            nextOverlayPanelMode(OverlayPanelMode.BUBBLE, first, next),
        )
    }

    @Test
    fun `attention expands once but later updates preserve collapse`() {
        val working = runningState("Opening the app")
        val attention = working.copy(
            currentPurpose = "Needs your attention",
            attentionReason = "Please confirm the visible prompt.",
        )
        val attentionUpdate = attention.copy(attentionReason = "The prompt is still waiting.")

        assertEquals(
            OverlayPanelMode.ATTENTION,
            nextOverlayPanelMode(OverlayPanelMode.WORKING, working, attention),
        )
        assertEquals(
            OverlayPanelMode.BUBBLE,
            nextOverlayPanelMode(OverlayPanelMode.BUBBLE, attention, attentionUpdate),
        )
    }

    @Test
    fun `bubble expansion opens active work and never an idle composer`() {
        assertEquals(
            OverlayPanelMode.WORKING,
            overlayPanelModeForUserExpand(runningState("Working")),
        )
        assertEquals(
            OverlayPanelMode.ATTENTION,
            overlayPanelModeForUserExpand(runningState("Needs your attention")),
        )
        assertEquals(
            OverlayPanelMode.COMPOSER,
            overlayPanelModeForUserExpand(SessionState.Idle),
        )
    }

    @Test
    fun `full screen glow is hidden as soon as composer leaves idle state`() {
        assertTrue(shouldShowOverlayGlow(OverlayPanelMode.COMPOSER, SessionState.Idle, hidden = false))
        assertFalse(shouldShowOverlayGlow(OverlayPanelMode.BUBBLE, SessionState.Idle, hidden = false))
        assertFalse(shouldShowOverlayGlow(OverlayPanelMode.COMPOSER, SessionState.Idle, hidden = true))
        assertFalse(shouldShowOverlayGlow(OverlayPanelMode.COMPOSER, runningState("Working"), hidden = false))
    }

    @Test
    fun `horizontal swipe places bubble on the matching display edge`() {
        val current = BubblePosition(x = 420, y = 600)

        assertEquals(
            BubblePosition(x = 12, y = 600),
            bubblePositionForHorizontalSwipe(
                direction = OverlaySwipeDirection.LEFT,
                currentPosition = current,
                displayWidth = 1080,
                displayHeight = 2400,
                bubbleWidth = 64,
                bubbleHeight = 64,
            ),
        )
        assertEquals(
            BubblePosition(x = 1004, y = 600),
            bubblePositionForHorizontalSwipe(
                direction = OverlaySwipeDirection.RIGHT,
                currentPosition = current,
                displayWidth = 1080,
                displayHeight = 2400,
                bubbleWidth = 64,
                bubbleHeight = 64,
            ),
        )
    }

    @Test
    fun `active terminal transition shows result even after collapse`() {
        val running = runningState("Working")
        val stopped = SessionState.Stopped(
            sessionId = running.sessionId,
            reason = "Stopped from the notification.",
        )

        assertEquals(
            OverlayPanelMode.RESULT,
            nextOverlayPanelMode(OverlayPanelMode.BUBBLE, running, stopped),
        )
    }

    private fun runningState(purpose: String): SessionState.Running =
        SessionState.Running(
            sessionId = "overlay-test-session",
            request = "Test request",
            currentPurpose = purpose,
            startedAtEpochMs = 0L,
        )
}

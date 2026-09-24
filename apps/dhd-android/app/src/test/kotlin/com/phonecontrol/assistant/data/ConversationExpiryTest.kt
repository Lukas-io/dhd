package com.phonecontrol.assistant.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationExpiryTest {
    @Test
    fun `conversation expires at the three hour boundary`() {
        val lastActivity = 1_000L

        assertFalse(
            hasDhdConversationExpired(
                lastActivityEpochMs = lastActivity,
                nowEpochMs = lastActivity + DHD_THREAD_INACTIVITY_MS - 1,
            ),
        )
        assertTrue(
            hasDhdConversationExpired(
                lastActivityEpochMs = lastActivity,
                nowEpochMs = lastActivity + DHD_THREAD_INACTIVITY_MS,
            ),
        )
    }
}

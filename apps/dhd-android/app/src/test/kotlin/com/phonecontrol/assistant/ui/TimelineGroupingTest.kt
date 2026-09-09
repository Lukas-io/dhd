package com.phonecontrol.assistant.ui

import com.phonecontrol.assistant.data.TimelineItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineGroupingTest {

    @Test
    fun `continuation activity is folded into the original task`() {
        val originalRunId = "original-run"
        val continuationRunId = "continuation-run"
        val timeline = listOf(
            TimelineItem.Message(
                id = "user-message",
                runId = originalRunId,
                role = "user",
                text = "Play the song",
                timestampEpochMs = 1_000L,
            ),
            activity("original-activity", originalRunId, 2_000L),
            activity("continued-activity", continuationRunId, 3_000L),
        )

        val groups = groupTimeline(timeline)

        assertEquals(1, groups.size)
        assertEquals(setOf(originalRunId, continuationRunId), groups.single().runIds)
        assertEquals(2, groups.single().activities.size)
    }

    @Test
    fun `continuation is attached before its first event arrives`() {
        val originalRunId = "original-run"
        val continuationRunId = "continuation-run"
        val timeline = listOf(
            TimelineItem.Message(
                id = "user-message",
                runId = originalRunId,
                role = "user",
                text = "Play the song",
                timestampEpochMs = 1_000L,
            ),
        )

        val groups = groupTimeline(timeline, continuationRunId)

        assertEquals(1, groups.size)
        assertTrue(continuationRunId in groups.single().runIds)
    }

    @Test
    fun `a new user request remains a separate task`() {
        val firstRunId = "first-run"
        val secondRunId = "second-run"
        val timeline = listOf(
            TimelineItem.Message("first-user", firstRunId, "user", "First", 1_000L),
            TimelineItem.Message("second-user", secondRunId, "user", "Second", 2_000L),
        )

        val groups = groupTimeline(timeline)

        assertEquals(2, groups.size)
        assertEquals(setOf(firstRunId), groups[0].runIds)
        assertEquals(setOf(secondRunId), groups[1].runIds)
    }

    @Test
    fun `elapsed seconds resume from prior active work`() {
        assertEquals(
            13L,
            accumulatedElapsedSeconds(
                startedAtEpochMs = 10_000L,
                elapsedBeforeStartMs = 12_000L,
                nowEpochMs = 11_500L,
            ),
        )
    }

    private fun activity(id: String, runId: String, timestampEpochMs: Long) =
        TimelineItem.Activity(
            id = id,
            runId = runId,
            purpose = "Opening the app",
            targetDescription = null,
            toolName = "dhd_open_app",
            actionType = "OPEN_APP",
            status = "completed",
            message = "Opening the app",
            createdAtEpochMs = timestampEpochMs,
            timestampEpochMs = timestampEpochMs,
        )
}

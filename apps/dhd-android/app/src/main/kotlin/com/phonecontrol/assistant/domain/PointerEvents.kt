package com.phonecontrol.assistant.domain

/**
 * A gesture that the task-display preview can render as visual feedback.
 *
 * These events are presentation metadata only. They never grant the preview
 * an input path and are emitted only after the corresponding phone command
 * has completed successfully.
 */
sealed interface TaskPointerEvent {
    val sequence: Long
    val sessionId: String
    val displayWidth: Int
    val displayHeight: Int

    data class Click(
        override val sequence: Long,
        override val sessionId: String,
        val x: Int,
        val y: Int,
        override val displayWidth: Int,
        override val displayHeight: Int,
    ) : TaskPointerEvent

    data class Swipe(
        override val sequence: Long,
        override val sessionId: String,
        val startX: Int,
        val startY: Int,
        val endX: Int,
        val endY: Int,
        val durationMs: Long,
        override val displayWidth: Int,
        override val displayHeight: Int,
    ) : TaskPointerEvent
}

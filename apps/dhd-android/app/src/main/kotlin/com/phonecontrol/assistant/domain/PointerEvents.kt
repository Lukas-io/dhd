package com.phonecontrol.assistant.domain

/**
 * A gesture that the task-display preview can render as visual feedback.
 *
 * These events are presentation metadata only. They never grant the preview
 * an input path. Click movement is emitted after transport preflight and
 * before dispatch, while the pressed phase is emitted at the input boundary.
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
        val phase: ClickPhase = ClickPhase.PRESSED,
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

    /**
     * A presentation-only calibration point shown when a task display first
     * becomes available. It is not an input action and must not be replayed.
     */
    data class Calibration(
        override val sequence: Long,
        override val sessionId: String,
        val x: Int,
        val y: Int,
        override val displayWidth: Int,
        override val displayHeight: Int,
    ) : TaskPointerEvent
}

enum class ClickPhase {
    MOVING,
    PRESSED,
}

const val TASK_CLICK_MOVE_DURATION_MS = 120L

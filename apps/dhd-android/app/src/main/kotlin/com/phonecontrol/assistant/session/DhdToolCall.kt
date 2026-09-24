package com.phonecontrol.assistant.session

/** A safe, presentation-only description of one DHD dynamic tool call. */
data class DhdToolCall(
    val id: String,
    val sessionId: String,
    val toolName: String,
    val purpose: String,
    val status: DhdToolCallStatus,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long? = null,
)

enum class DhdToolCallStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    ATTENTION,
}

fun defaultDhdToolPurpose(toolName: String): String = when (toolName) {
    "dhd_list_allowed_apps" -> "Checking which apps DHD can use"
    "dhd_browse_app" -> "Browsing installed apps"
    "dhd_set_app_display_layout" -> "Adjusting the app's task-display layout"
    "dhd_get_foreground_app" -> "Checking which app is on screen"
    "dhd_observe" -> "Inspecting the current screen"
    "dhd_open_app" -> "Opening an app"
    "dhd_execute" -> "Performing a phone interaction"
    "dhd_execute_sequence" -> "Performing a validated series of interactions"
    "dhd_request_attention" -> "Waiting for your attention"
    else -> "Working with the phone"
}

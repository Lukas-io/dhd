package com.phonecontrol.assistant

/** The order in which first-run system permissions are requested. */
internal enum class FirstRunPermissionStep {
    NOTIFICATIONS,
    OVERLAY,
}

internal fun nextFirstRunPermissionStep(
    sdkInt: Int,
    notificationGranted: Boolean,
    overlayGranted: Boolean,
): FirstRunPermissionStep? = when {
    sdkInt >= 33 && !notificationGranted -> FirstRunPermissionStep.NOTIFICATIONS
    !overlayGranted -> FirstRunPermissionStep.OVERLAY
    else -> null
}

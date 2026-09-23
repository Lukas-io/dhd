package com.phonecontrol.assistant

/** The ordered, user-facing permission steps in DHD's first-run setup. */
enum class PermissionSetupStep {
    NOTIFICATIONS,
    OVERLAY,
    APP_ACCESS,
    COMPLETE,
}

internal fun nextPermissionSetupStep(
    sdkInt: Int,
    notificationGranted: Boolean,
    overlayGranted: Boolean,
    notificationStepHandled: Boolean = false,
): PermissionSetupStep = when {
    sdkInt >= 33 && !notificationGranted && !notificationStepHandled -> PermissionSetupStep.NOTIFICATIONS
    !overlayGranted -> PermissionSetupStep.OVERLAY
    else -> PermissionSetupStep.COMPLETE
}

internal fun firstRunPermissionSetupStep(
    onboardingCompleted: Boolean,
    sdkInt: Int,
    notificationGranted: Boolean,
    overlayGranted: Boolean,
    notificationStepHandled: Boolean = false,
): PermissionSetupStep? {
    if (onboardingCompleted) return null
    val nextStep = nextPermissionSetupStep(
        sdkInt = sdkInt,
        notificationGranted = notificationGranted,
        overlayGranted = overlayGranted,
        notificationStepHandled = notificationStepHandled,
    )
    return if (nextStep == PermissionSetupStep.COMPLETE) PermissionSetupStep.APP_ACCESS else nextStep
}

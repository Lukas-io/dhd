package com.phonecontrol.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionSetupTest {
    @Test
    fun asksForNotificationsBeforeOverlayAccess() {
        assertEquals(
            PermissionSetupStep.NOTIFICATIONS,
            nextPermissionSetupStep(
                sdkInt = 35,
                notificationGranted = false,
                overlayGranted = false,
            ),
        )
    }

    @Test
    fun proceedsToOverlayAfterTheNotificationPromptIsHandled() {
        assertEquals(
            PermissionSetupStep.OVERLAY,
            nextPermissionSetupStep(
                sdkInt = 35,
                notificationGranted = true,
                overlayGranted = false,
            ),
        )
        assertEquals(
            PermissionSetupStep.OVERLAY,
            nextPermissionSetupStep(
                sdkInt = 35,
                notificationGranted = false,
                overlayGranted = false,
                notificationStepHandled = true,
            ),
        )
    }

    @Test
    fun skipsNotificationPermissionBeforeAndroid13() {
        assertEquals(
            PermissionSetupStep.OVERLAY,
            nextPermissionSetupStep(
                sdkInt = 32,
                notificationGranted = false,
                overlayGranted = false,
            ),
        )
    }

    @Test
    fun completesWhenBothPermissionsAreGranted() {
        assertEquals(
            PermissionSetupStep.COMPLETE,
            nextPermissionSetupStep(
                sdkInt = 35,
                notificationGranted = true,
                overlayGranted = true,
            ),
        )
    }

    @Test
    fun resumesAnIncompleteFirstRunAtItsOutstandingStep() {
        assertEquals(
            PermissionSetupStep.OVERLAY,
            firstRunPermissionSetupStep(
                onboardingCompleted = false,
                sdkInt = 35,
                notificationGranted = false,
                overlayGranted = false,
                notificationStepHandled = true,
            ),
        )
    }

    @Test
    fun doesNotShowFirstRunSetupAgainAfterItIsCompleted() {
        assertEquals(
            null,
            firstRunPermissionSetupStep(
                onboardingCompleted = true,
                sdkInt = 35,
                notificationGranted = false,
                overlayGranted = false,
            ),
        )
    }

    @Test
    fun showsAppAccessAfterAllRequiredPermissionsAreHandled() {
        assertEquals(
            PermissionSetupStep.APP_ACCESS,
            firstRunPermissionSetupStep(
                onboardingCompleted = false,
                sdkInt = 35,
                notificationGranted = false,
                overlayGranted = true,
                notificationStepHandled = true,
            ),
        )
    }
}

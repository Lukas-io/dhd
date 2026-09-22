package com.phonecontrol.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PermissionSetupTest {
    @Test
    fun requestsNotificationsBeforeOverlay() {
        assertEquals(
            FirstRunPermissionStep.NOTIFICATIONS,
            nextFirstRunPermissionStep(
                sdkInt = 35,
                notificationGranted = false,
                overlayGranted = false,
            ),
        )
    }

    @Test
    fun requestsOverlayAfterNotificationDecision() {
        assertEquals(
            FirstRunPermissionStep.OVERLAY,
            nextFirstRunPermissionStep(
                sdkInt = 35,
                notificationGranted = true,
                overlayGranted = false,
            ),
        )
        assertEquals(
            FirstRunPermissionStep.NOTIFICATIONS,
            nextFirstRunPermissionStep(
                sdkInt = 35,
                notificationGranted = false,
                overlayGranted = true,
            ),
        )
    }

    @Test
    fun skipsNotificationRuntimePermissionBeforeAndroid13() {
        assertEquals(
            FirstRunPermissionStep.OVERLAY,
            nextFirstRunPermissionStep(
                sdkInt = 32,
                notificationGranted = false,
                overlayGranted = false,
            ),
        )
    }

    @Test
    fun completesWhenBothPermissionsAreGranted() {
        assertNull(
            nextFirstRunPermissionStep(
                sdkInt = 35,
                notificationGranted = true,
                overlayGranted = true,
            ),
        )
    }
}

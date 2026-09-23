package com.phonecontrol.assistant.session

import com.phonecontrol.assistant.domain.ActionMetadata
import com.phonecontrol.assistant.domain.ClickPhase
import com.phonecontrol.assistant.domain.ObservationSnapshot
import com.phonecontrol.assistant.domain.PhoneAction
import com.phonecontrol.assistant.domain.ReasoningEffort
import com.phonecontrol.assistant.domain.ScreenProtection
import com.phonecontrol.assistant.domain.ScreenProtectionStatus
import com.phonecontrol.assistant.domain.SwipeAction
import com.phonecontrol.assistant.domain.TapAction
import com.phonecontrol.assistant.domain.TaskPointerEvent
import com.phonecontrol.assistant.policy.PolicyEngine
import com.phonecontrol.assistant.execution.PhoneActionTransport
import com.phonecontrol.assistant.execution.RejectionCode
import com.phonecontrol.assistant.execution.TransportResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCoordinatorTest {
    private val observation = ObservationSnapshot(
        id = "obs-1",
        packageName = "com.example.shop",
        activityName = null,
        displayId = 0,
        rotation = 0,
        width = 1080,
        height = 2400,
        screenshotFingerprint = "fingerprint",
    )

    @Test
    fun `start pause resume and stop update state and timeline`() {
        val coordinator = coordinator()

        assertTrue(coordinator.start("Find a restaurant"))
        assertTrue(coordinator.pause())
        assertTrue(coordinator.resume())
        assertTrue(coordinator.stop())

        assertTrue(coordinator.state.value is SessionState.Stopped)
        assertEquals(4, coordinator.events.value.size)
    }

    @Test
    fun `reset returns state to idle and clears session data`() {
        val coordinator = coordinator()

        assertTrue(coordinator.start("Find a restaurant"))
        coordinator.enqueueSteer("Scroll down")
        assertTrue(coordinator.stop())
        assertTrue(coordinator.state.value is SessionState.Stopped)

        assertTrue(coordinator.reset())
        assertTrue(coordinator.state.value is SessionState.Idle)
        assertTrue(coordinator.events.value.isEmpty())
        assertTrue(coordinator.toolCalls.value.isEmpty())
        assertNull(coordinator.pendingSteer())
        assertNull(coordinator.activeSessionId())
    }

    @Test
    fun `stopped run can continue in the same conversation with its settings`() {
        val coordinator = coordinator()

        assertTrue(
            coordinator.start(
                request = "Find a restaurant",
                conversationId = "dhd-assistant",
                reasoningEffort = ReasoningEffort.EXTRA_HIGH.codexValue,
                fastMode = true,
            ),
        )
        assertTrue(coordinator.stop("Stopped by the user."))
        val stopped = coordinator.state.value as SessionState.Stopped
        assertTrue(coordinator.continueStopped())

        val continued = coordinator.state.value as SessionState.Running
        assertEquals("dhd-assistant", continued.conversationId)
        assertEquals("Find a restaurant", continued.request)
        assertEquals(ReasoningEffort.EXTRA_HIGH.codexValue, continued.reasoningEffort)
        assertTrue(continued.fastMode)
        assertTrue(continued.isContinuation)
        assertEquals(stopped.workedDurationMs, continued.elapsedBeforeStartMs)
        assertTrue(coordinator.pendingRequest()?.isContinuation == true)
        assertEquals("Find a restaurant", coordinator.pendingRequest()?.request)
    }

    @Test
    fun `executes action and updates timeline`() = runTest {
        val coordinator = coordinator()
        coordinator.start("Buy dinner")

        val result = coordinator.executeAction(
            TapAction(
                x = 500,
                y = 900,
                metadata = ActionMetadata(
                    purpose = "Place order",
                    observationId = observation.id,
                    targetDescription = "Place order button",
                ),
            ),
            observation,
        )

        assertTrue(result is ActionExecutionResult.TransportFinished)
        assertTrue(coordinator.events.value.any { it.kind == com.phonecontrol.assistant.domain.ActivityEventKind.ACTION_SUCCEEDED })
        val pointer = coordinator.pointerEvent.value
        assertTrue(pointer is TaskPointerEvent.Click)
        assertEquals(500, (pointer as TaskPointerEvent.Click).x)
        assertEquals(900, pointer.y)
        assertEquals(ClickPhase.PRESSED, pointer.phase)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `phone access loss pauses the tool until access returns`() = runTest {
        var phoneAccessReady = false
        var transportCalls = 0
        var attentionNotifications = 0
        var attentionResolved = 0
        val transport = object : PhoneActionTransport {
            override suspend fun execute(
                action: PhoneAction,
                observation: ObservationSnapshot?,
            ): TransportResult = TransportResult.Succeeded("executed")

            override suspend fun executeForSession(
                sessionKey: String,
                action: PhoneAction,
                observation: ObservationSnapshot?,
                beforeInput: (() -> Unit)?,
                onPointerMove: (() -> Unit)?,
            ): TransportResult {
                transportCalls += 1
                return if (transportCalls == 1) {
                    TransportResult.Rejected(
                        code = RejectionCode.DEVELOPER_MODE_UNAVAILABLE,
                        message = "Phone access is unavailable.",
                    )
                } else {
                    TransportResult.Succeeded("executed")
                }
            }
        }
        val coordinator = SessionCoordinator(
            enabledPackagesProvider = { setOf("com.example.shop") },
            policyEngine = PolicyEngine(),
            transport = transport,
            phoneAccessReadyProvider = { phoneAccessReady },
            onPhoneAccessAttentionRequested = { _, _ -> attentionNotifications += 1 },
            onPhoneAccessAttentionResolved = { attentionResolved += 1 },
        )
        coordinator.start("Buy dinner")

        val result = async {
            coordinator.executeAction(
                TapAction(
                    x = 500,
                    y = 900,
                    metadata = ActionMetadata(
                        purpose = "Place order",
                        observationId = observation.id,
                        targetDescription = "Place order button",
                    ),
                ),
                observation,
            )
        }

        runCurrent()
        assertFalse(result.isCompleted)
        assertTrue(coordinator.attentionPending())
        assertEquals("View instructions", (coordinator.state.value as SessionState.Running).attentionActionLabel)
        assertEquals(1, attentionNotifications)

        phoneAccessReady = true
        advanceUntilIdle()

        assertTrue(result.await() is ActionExecutionResult.TransportFinished)
        assertEquals(2, transportCalls)
        assertEquals(1, attentionResolved)
        assertFalse(coordinator.attentionPending())
        assertTrue(coordinator.events.value.any {
            it.message.contains("Phone access was restored")
        })
    }

    @Test
    fun `publishes an initial calibration pointer for an active task display`() {
        val coordinator = coordinator()
        coordinator.start("Open the shopping app")

        val pointer = coordinator.publishCalibrationPointerEvent(observation)
        val calibration = pointer ?: error("Expected a calibration pointer")

        assertEquals(calibration, coordinator.pointerEvent.value)
        assertTrue(calibration.x in 0 until observation.width)
        assertTrue(calibration.y in 0 until observation.height)
        assertEquals(observation.width, calibration.displayWidth)
        assertEquals(observation.height, calibration.displayHeight)
    }

    @Test
    fun `click movement is published before input and press at the input boundary`() = runTest {
        val phases = mutableListOf<ClickPhase>()
        lateinit var coordinator: SessionCoordinator
        val transport = object : PhoneActionTransport {
            override suspend fun execute(
                action: PhoneAction,
                observation: ObservationSnapshot?,
            ): TransportResult = TransportResult.Succeeded("executed")

            override suspend fun executeForSession(
                sessionKey: String,
                action: PhoneAction,
                observation: ObservationSnapshot?,
                beforeInput: (() -> Unit)?,
                onPointerMove: (() -> Unit)?,
            ): TransportResult {
                onPointerMove?.invoke()
                phases += (coordinator.pointerEvent.value as TaskPointerEvent.Click).phase
                beforeInput?.invoke()
                phases += (coordinator.pointerEvent.value as TaskPointerEvent.Click).phase
                return TransportResult.Succeeded("executed")
            }
        }
        coordinator = SessionCoordinator(
            enabledPackagesProvider = { setOf("com.example.shop") },
            policyEngine = PolicyEngine(),
            transport = transport,
        )
        coordinator.start("Buy dinner")

        val result = coordinator.executeAction(
            TapAction(
                x = 500,
                y = 900,
                metadata = ActionMetadata(
                    purpose = "Place order",
                    observationId = observation.id,
                    targetDescription = "Place order button",
                ),
            ),
            observation,
        )

        assertTrue(result is ActionExecutionResult.TransportFinished)
        assertEquals(listOf(ClickPhase.MOVING, ClickPhase.PRESSED), phases)
        assertEquals(ClickPhase.PRESSED, (coordinator.pointerEvent.value as TaskPointerEvent.Click).phase)
    }

    @Test
    fun `swipe movement is published before input`() = runTest {
        val order = mutableListOf<String>()
        lateinit var coordinator: SessionCoordinator
        val transport = object : PhoneActionTransport {
            override suspend fun execute(
                action: PhoneAction,
                observation: ObservationSnapshot?,
            ): TransportResult = TransportResult.Succeeded("executed")

            override suspend fun executeForSession(
                sessionKey: String,
                action: PhoneAction,
                observation: ObservationSnapshot?,
                beforeInput: (() -> Unit)?,
                onPointerMove: (() -> Unit)?,
            ): TransportResult {
                onPointerMove?.invoke()
                assertTrue(coordinator.pointerEvent.value is TaskPointerEvent.Swipe)
                order += "pointer"
                order += "input"
                return TransportResult.Succeeded("executed")
            }
        }
        coordinator = SessionCoordinator(
            enabledPackagesProvider = { setOf("com.example.shop") },
            policyEngine = PolicyEngine(),
            transport = transport,
        )
        coordinator.start("Buy dinner")

        val result = coordinator.executeAction(
            SwipeAction(
                startX = 500,
                startY = 1600,
                endX = 500,
                endY = 700,
                durationMs = 400,
                metadata = ActionMetadata(
                    purpose = "Scroll to the menu",
                    observationId = observation.id,
                    targetDescription = "Menu list",
                ),
            ),
            observation,
        )

        assertTrue(result is ActionExecutionResult.TransportFinished)
        assertEquals(listOf("pointer", "input"), order)
        assertTrue(coordinator.pointerEvent.value is TaskPointerEvent.Swipe)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `click dispatch does not add a second travel wait`() = runTest {
        var transportCalls = 0
        val transport = object : PhoneActionTransport {
            override suspend fun execute(
                action: PhoneAction,
                observation: ObservationSnapshot?,
            ): TransportResult = TransportResult.Succeeded("executed")

            override suspend fun executeForSession(
                sessionKey: String,
                action: PhoneAction,
                observation: ObservationSnapshot?,
                beforeInput: (() -> Unit)?,
                onPointerMove: (() -> Unit)?,
            ): TransportResult {
                onPointerMove?.invoke()
                beforeInput?.invoke()
                transportCalls += 1
                return TransportResult.Succeeded("executed")
            }
        }
        val coordinator = SessionCoordinator(
            enabledPackagesProvider = { setOf("com.example.shop") },
            policyEngine = PolicyEngine(),
            transport = transport,
        )
        coordinator.start("Buy dinner")

        fun tap(x: Int, y: Int) = TapAction(
            x = x,
            y = y,
            metadata = ActionMetadata(
                purpose = "Place order",
                observationId = observation.id,
                targetDescription = "Place order button",
            ),
        )

        coordinator.executeAction(tap(500, 900), observation)
        val second = async { coordinator.executeAction(tap(700, 1200), observation) }

        runCurrent()
        assertTrue(second.isCompleted)
        assertEquals(2, transportCalls)
        assertTrue(second.await() is ActionExecutionResult.TransportFinished)
    }

    @Test
    fun `desktop request can be claimed once and released for retry`() {
        val coordinator = coordinator()
        coordinator.start("Find a restaurant")

        val pending = coordinator.pendingRequest()
        assertNotNull(pending)
        val claimed = coordinator.claimRequest(pending!!.sessionId)
        assertEquals(pending, claimed)
        assertNull(coordinator.pendingRequest())
        assertNull(coordinator.claimRequest(pending.sessionId))

        assertTrue(coordinator.releaseRequest(pending.sessionId))
        assertEquals(pending, coordinator.pendingRequest())
    }

    @Test
    fun `pending desktop request carries the selected reasoning effort`() {
        val coordinator = coordinator()
        coordinator.start(
            "Find a restaurant",
            reasoningEffort = ReasoningEffort.EXTRA_HIGH.codexValue,
            fastMode = true,
        )

        assertEquals("xhigh", coordinator.pendingRequest()?.reasoningEffort)
        assertEquals("xhigh", (coordinator.state.value as SessionState.Running).reasoningEffort)
        assertTrue(coordinator.pendingRequest()?.fastMode == true)
        assertTrue((coordinator.state.value as SessionState.Running).fastMode)
    }

    @Test
    fun `desktop request remains visible while phone actions are unavailable`() {
        val coordinator = coordinator()
        coordinator.start("Open Spotify")

        val pending = coordinator.pendingRequest()
        assertNotNull(pending)
        assertEquals(pending, coordinator.claimRequest(pending!!.sessionId))
    }

    @Test
    fun `steer request can be claimed once and completed`() {
        val coordinator = coordinator()
        assertTrue(coordinator.start("Find a restaurant"))

        val queued = coordinator.enqueueSteer("Actually use the closest location.")
        assertNotNull(queued)
        assertEquals(queued, coordinator.pendingSteer(queued!!.sessionId))

        val claimed = coordinator.claimSteer(queued.sessionId, queued.steerId)
        assertEquals(queued, claimed)
        assertNull(coordinator.pendingSteer(queued.sessionId))
        assertTrue(coordinator.completeSteer(queued.sessionId, queued.steerId))
        assertNull(coordinator.claimSteer(queued.sessionId, queued.steerId))
    }

    @Test
    fun `failed steer delivery is requeued for the same running session`() {
        val coordinator = coordinator()
        assertTrue(coordinator.start("Find a restaurant"))

        val queued = coordinator.enqueueSteer("Do not submit anything yet.")!!
        assertNotNull(coordinator.claimSteer(queued.sessionId, queued.steerId))
        assertTrue(coordinator.releaseSteer(queued.sessionId, queued.steerId))
        assertEquals(queued, coordinator.pendingSteer(queued.sessionId))
    }

    @Test
    fun `steer cannot be released through a different session id`() {
        val coordinator = coordinator()
        assertTrue(coordinator.start("Find a restaurant"))

        val queued = coordinator.enqueueSteer("Keep the current plan.")!!
        assertNotNull(coordinator.claimSteer(queued.sessionId, queued.steerId))
        assertTrue(!coordinator.releaseSteer("another-session", queued.steerId))
        assertNull(coordinator.pendingSteer(queued.sessionId))
        assertTrue(coordinator.releaseSteer(queued.sessionId, queued.steerId))
        assertEquals(queued, coordinator.pendingSteer(queued.sessionId))
    }

    @Test
    fun `agent feedback is kept as a conversation message when completed`() {
        val coordinator = coordinator()
        coordinator.start("Find a restaurant")

        assertTrue(
            coordinator.complete(
                message = "Fallback completion",
                agentFeedback = "I stopped because the screen changed before the tap.",
            ),
        )

        val state = coordinator.state.value as SessionState.Completed
        assertEquals("I stopped because the screen changed before the tap.", state.message)
        assertTrue(coordinator.events.value.any {
            it.kind == com.phonecontrol.assistant.domain.ActivityEventKind.AGENT_MESSAGE &&
                it.message == state.message
        })
    }

    @Test
    fun `attention request creates a user-visible attention event`() {
        val coordinator = coordinator()
        coordinator.start("Find a restaurant")

        assertTrue(coordinator.requestAttention("The screen changed; please review the phone."))

        assertTrue(coordinator.state.value is SessionState.Running)
        assertEquals("Needs your attention", (coordinator.state.value as SessionState.Running).currentPurpose)
        assertTrue(coordinator.events.value.any {
            it.kind == com.phonecontrol.assistant.domain.ActivityEventKind.ATTENTION_REQUIRED
        })
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `attention waiter stays blocked until the user acknowledges`() = runTest {
        val coordinator = coordinator()
        coordinator.start("Find a restaurant")
        val sessionId = coordinator.activeSessionId()!!
        val attention = coordinator.requestAttentionWaiter("Complete the PIN on the phone.")!!

        val waiter = async { attention.await() }
        runCurrent()
        assertTrue(!waiter.isCompleted)
        assertTrue(coordinator.attentionPending())

        assertTrue(coordinator.acknowledgeAttention())
        assertEquals(AttentionResolution.Acknowledged, waiter.await())
        assertTrue(!coordinator.attentionPending())
        assertNull((coordinator.state.value as SessionState.Running).attentionReason)
    }

    @Test
    fun `attention completion survives an immediate Done tap before the bridge awaits`() = runTest {
        val coordinator = coordinator()
        coordinator.start("Find a restaurant")
        val attention = coordinator.requestAttentionWaiter("Complete the PIN on the phone.")!!

        assertTrue(coordinator.acknowledgeAttention())
        assertEquals(AttentionResolution.Acknowledged, attention.await())
    }

    @Test
    fun `secure observation rejects input and tells the agent to request attention`() = runTest {
        val coordinator = coordinator()
        coordinator.start("Unlock the banking app")
        val secureObservation = observation.copy(
            screenProtection = ScreenProtection(
                status = ScreenProtectionStatus.SECURE,
                requiresUserAttention = true,
                reason = "The focused screen is protected by a PIN.",
            ),
        )

        val result = coordinator.executeAction(
            TapAction(
                x = 500,
                y = 900,
                metadata = ActionMetadata(
                    purpose = "Continue",
                    observationId = secureObservation.id,
                    targetDescription = "Continue button",
                ),
            ),
            secureObservation,
        )

        assertTrue(result is ActionExecutionResult.PolicyRejected)
        assertEquals("SECURE_SCREEN_REQUIRES_USER", (result as ActionExecutionResult.PolicyRejected).code)
        assertTrue(result.message.contains("dhd_request_attention"))
    }

    @Test
    fun `purpose events retain the originating tool name`() {
        val coordinator = coordinator()
        coordinator.start("Find an app")

        assertTrue(
            coordinator.recordPurpose(
                purpose = "Browsing installed apps",
                targetDescription = "Spotify",
                toolName = "dhd_browse_app",
            ),
        )

        val event = coordinator.events.value.last()
        assertEquals("dhd_browse_app", event.toolName)
    }

    @Test
    fun `tool call lifecycle exposes only bounded safe activity`() {
        val coordinator = coordinator()
        coordinator.start("Find a restaurant")

        val callId = coordinator.beginToolCall("dhd_observe", "Inspecting the current screen")

        assertNotNull(callId)
        assertEquals(1, coordinator.toolCalls.value.size)
        assertEquals("dhd_observe", coordinator.toolCalls.value.single().toolName)
        assertEquals(DhdToolCallStatus.RUNNING, coordinator.toolCalls.value.single().status)
        assertTrue(coordinator.finishToolCall(callId, DhdToolCallStatus.COMPLETED))
        assertEquals(DhdToolCallStatus.COMPLETED, coordinator.toolCalls.value.single().status)
        assertFalse(coordinator.finishToolCall(callId, DhdToolCallStatus.FAILED))
    }

    @Test
    fun `tool call history is bounded to the most recent calls`() {
        val coordinator = coordinator()
        coordinator.start("Find a restaurant")

        repeat(15) { index ->
            assertNotNull(coordinator.beginToolCall("dhd_execute", "Step $index"))
        }

        assertEquals(12, coordinator.toolCalls.value.size)
        assertEquals("Step 3", coordinator.toolCalls.value.first().purpose)
    }

    private fun coordinator(): SessionCoordinator = SessionCoordinator(
        enabledPackagesProvider = { setOf("com.example.shop") },
        policyEngine = PolicyEngine(),
        transport = object : PhoneActionTransport {
            override suspend fun execute(
                action: com.phonecontrol.assistant.domain.PhoneAction,
                observation: ObservationSnapshot?,
            ): TransportResult = TransportResult.Succeeded("executed")
        },
    )
}

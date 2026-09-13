package com.phonecontrol.assistant

import android.app.Application
import android.view.Surface
import com.phonecontrol.assistant.apps.AppPermissionRepository
import com.phonecontrol.assistant.bridge.DevBridgeServer
import com.phonecontrol.assistant.data.ConversationStore
import com.phonecontrol.assistant.developer.DhdAdbController
import com.phonecontrol.assistant.developer.DhdAdbProcessRunner
import com.phonecontrol.assistant.developer.DhdTaskDisplayBackend
import com.phonecontrol.assistant.developer.PreviewSurfaceDispatcher
import com.phonecontrol.assistant.developer.DhdVirtualDisplayManager
import com.phonecontrol.assistant.overlay.OverlayVisibilityGate
import com.phonecontrol.assistant.policy.PolicyEngine
import com.phonecontrol.assistant.session.SessionCoordinator
import com.phonecontrol.assistant.execution.PhoneObservationProvider
import com.phonecontrol.assistant.execution.TypedPhoneActionTransport
import com.phonecontrol.assistant.execution.TaskDisplaySession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class PhoneControlApplication : Application() {
    private val previewScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var appPermissionRepository: AppPermissionRepository
        private set
    lateinit var developerModeController: DhdAdbController
        private set
    lateinit var processRunner: DhdAdbProcessRunner
        private set
    lateinit var observationProvider: PhoneObservationProvider
        private set
    lateinit var taskDisplayBackend: DhdTaskDisplayBackend
        private set
    lateinit var sessionCoordinator: SessionCoordinator
        private set
    lateinit var conversationStore: ConversationStore
        private set
    lateinit var devBridgeServer: DevBridgeServer
        private set
    lateinit var overlayVisibilityGate: OverlayVisibilityGate
        private set

    private val previewSurfaces by lazy {
        PreviewSurfaceDispatcher<TaskDisplaySession, Surface>(
            scope = previewScope,
            attach = { session, surface -> taskDisplayBackend.attachLiveSurface(session, surface) },
            detach = { session, surface -> taskDisplayBackend.detachLiveSurface(session, surface) },
            onFailure = { error -> android.util.Log.w("DhdPreview", "Surface lifecycle failed", error) },
        )
    }

    fun attachTaskPreview(session: TaskDisplaySession, surface: Surface) {
        previewSurfaces.attach(surface) { session }
    }

    fun detachTaskPreview(
        surface: Surface,
        releaseSurface: () -> Unit,
    ) {
        previewSurfaces.detach(surface, releaseSurface)
    }

    /** Session lookup and cleanup run in the same order as the UI callbacks. */
    fun attachTaskPreview(sessionKey: String, surface: Surface) {
        previewSurfaces.attach(surface) { taskDisplayBackend.current(sessionKey) }
    }

    fun retryTaskPreview(sessionKey: String) {
        previewScope.launch { taskDisplayBackend.retryLiveSurface(sessionKey) }
    }

    /** End one display, stopping its owning agent run before releasing native resources. */
    fun endTaskDisplay(displayId: Int?, displayRef: String?) {
        val selectedDisplayId = displayId ?: return
        previewScope.launch {
            val activeRunKey = sessionCoordinator.activeSessionId()
            if (activeRunKey != null && taskDisplayBackend.isDisplayClaimedByRun(selectedDisplayId, activeRunKey)) {
                sessionCoordinator.stop("Display ended by the user.")
            }
            taskDisplayBackend.closeTaskDisplay(selectedDisplayId, displayRef)
        }
    }

    override fun onCreate() {
        super.onCreate()
        appPermissionRepository = AppPermissionRepository(this)
        developerModeController = DhdAdbController(this).also { it.start() }
        processRunner = DhdAdbProcessRunner(developerModeController)
        conversationStore = ConversationStore(this)
        taskDisplayBackend = DhdTaskDisplayBackend(
            this,
            DhdVirtualDisplayManager(this, developerModeController),
            processRunner,
            conversationStore,
        )
        previewScope.launch {
            developerModeController.status.collect { status ->
                if (status.privilegedApiReady) {
                    // A restarted shell daemon loses its in-memory display
                    // sessions. Reconcile before a retained viewer or the
                    // next task action can use the stale app-side binding.
                    taskDisplayBackend.reconcileNativeSessionsNow()
                }
            }
        }
        observationProvider = PhoneObservationProvider(this, processRunner, taskDisplayBackend)
        overlayVisibilityGate = OverlayVisibilityGate()
        sessionCoordinator = SessionCoordinator(
            enabledPackagesProvider = { appPermissionRepository.enabledPackages() },
            // Structural observation checks are always enabled; guard-region
            // fingerprints add the optional stricter visual check per action.
            policyEngine = PolicyEngine(enforceObservationFreshness = true),
            transport = TypedPhoneActionTransport(
                context = this,
                observationProvider = observationProvider,
                processRunner = processRunner,
                executionReadyProvider = { developerModeController.status.value.privilegedApiReady },
                executionUnavailableMessageProvider = { developerModeController.status.value.message },
                enforceObservationFreshness = true,
                taskDisplayBackend = taskDisplayBackend,
            ),
            conversationStore = conversationStore,
            phoneActionsReadyProvider = { developerModeController.status.value.privilegedApiReady },
            fullAccessProvider = { appPermissionRepository.isFullAccessEnabled() },
            taskDisplayRequiredProvider = { true },
            taskDisplayBackend = taskDisplayBackend,
        )
        // The bridge accepts paired LAN connections for the development
        // companion. adb forwarding remains compatible because forwarded
        // clients arrive as loopback and bypass the LAN token check.
        devBridgeServer = DevBridgeServer(
            context = this,
            coordinator = sessionCoordinator,
            observationProvider = observationProvider,
            allowedPackagesProvider = { appPermissionRepository.enabledPackages() },
            fullAccessProvider = { appPermissionRepository.isFullAccessEnabled() },
            taskDisplayRequiredProvider = { true },
            taskDisplayBackend = taskDisplayBackend,
        ).also { it.start() }
    }
}

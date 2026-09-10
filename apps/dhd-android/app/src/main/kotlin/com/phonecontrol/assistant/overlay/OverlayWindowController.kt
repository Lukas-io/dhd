package com.phonecontrol.assistant.overlay

import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.phonecontrol.assistant.MainActivity
import com.phonecontrol.assistant.data.DHD_CONVERSATION_ID
import com.phonecontrol.assistant.domain.ReasoningEffort
import com.phonecontrol.assistant.session.AssistantForegroundService
import com.phonecontrol.assistant.session.SessionCoordinator
import com.phonecontrol.assistant.session.SessionState
import com.phonecontrol.assistant.ui.DarkAssistantColors
import com.phonecontrol.assistant.ui.LightAssistantColors
import com.phonecontrol.assistant.ui.LocalAssistantColors
import com.phonecontrol.assistant.ui.ThemeMode
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Owns the two WindowManager surfaces used by the DHD overlay. */
class OverlayWindowController(
    context: Context,
    private val coordinator: SessionCoordinator,
    private val visibilityGate: OverlayVisibilityGate,
) {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val _panelMode = MutableStateFlow(OverlayPanelMode.BUBBLE)
    private val _resultMessage = MutableStateFlow<String?>(null)

    private var panelView: ComposeView? = null
    private var glowView: ComposeView? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var viewTreeOwner: OverlayViewTreeOwner? = null
    private var lastState: SessionState = coordinator.state.value
    private var hidden = visibilityGate.hidden.value

    val panelMode: StateFlow<OverlayPanelMode> = _panelMode.asStateFlow()
    val resultMessage: StateFlow<String?> = _resultMessage.asStateFlow()

    fun show(): Boolean {
        if (!Settings.canDrawOverlays(appContext)) return false
        if (panelView == null || glowView == null) {
            createViews()
        }
        onSessionState(coordinator.state.value)
        setHidden(hidden)
        return panelView != null && glowView != null
    }

    fun hide() {
        hideKeyboard()
        removeViews()
    }

    fun destroy() {
        hide()
    }

    fun setHidden(value: Boolean) {
        hidden = value
        if (value) hideKeyboard()
        val visibility = if (value) View.GONE else View.VISIBLE
        glowView?.visibility = visibility
        panelView?.visibility = visibility
    }

    fun onSessionState(state: SessionState) {
        val wasActive = lastState.isActiveForOverlay()
        val isActive = state.isActiveForOverlay()
        lastState = state

        when {
            isActive -> {
                _resultMessage.value = null
                setPanelMode(
                    if (state.needsAttention()) OverlayPanelMode.ATTENTION else OverlayPanelMode.WORKING,
                )
            }
            wasActive && state is SessionState.Completed -> {
                _resultMessage.value = state.message
                setPanelMode(OverlayPanelMode.RESULT)
            }
            wasActive && state is SessionState.Stopped -> {
                _resultMessage.value = state.reason
                setPanelMode(OverlayPanelMode.RESULT)
            }
        }
    }

    fun openComposer() {
        if (coordinator.state.value.isActiveForOverlay()) return
        _resultMessage.value = null
        setPanelMode(OverlayPanelMode.COMPOSER)
    }

    fun showBubble() {
        hideKeyboard()
        setPanelMode(OverlayPanelMode.BUBBLE)
    }

    fun moveBubble(deltaX: Float, deltaY: Float) {
        val params = panelParams ?: return
        if (_panelMode.value != OverlayPanelMode.BUBBLE || hidden) return
        val metrics = appContext.resources.displayMetrics
        val insets = bubbleInsets()
        val position = clampBubblePosition(
            x = params.x + deltaX.roundToInt(),
            y = params.y + deltaY.roundToInt(),
            displayWidth = metrics.widthPixels,
            displayHeight = metrics.heightPixels,
            bubbleWidth = params.width.takeIf { it > 0 } ?: dp(64),
            bubbleHeight = params.height.takeIf { it > 0 } ?: dp(64),
            topInset = insets.top,
            bottomInset = insets.bottom,
        )
        params.x = position.x
        params.y = position.y
        runCatching { panelView?.let { windowManager.updateViewLayout(it, params) } }
        OverlayPreferences.setBubblePosition(appContext, position)
    }

    private fun setPanelMode(mode: OverlayPanelMode) {
        if (_panelMode.value == mode) return
        _panelMode.value = mode
        updatePanelLayout(mode != OverlayPanelMode.BUBBLE)
    }

    private fun createViews() {
        if (!Settings.canDrawOverlays(appContext)) return
        val colors = assistantColors()
        val lifecycleOwner = OverlayViewTreeOwner()
        val panel = ComposeView(appContext).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent {
                CompositionLocalProvider(LocalAssistantColors provides colors) {
                    OverlayPanel(
                        sessionState = coordinator.state,
                        toolCalls = coordinator.toolCalls,
                        panelMode = panelMode,
                        resultMessage = resultMessage,
                        onExpand = ::openComposer,
                        onNewRequest = ::openComposer,
                        onSubmit = ::submitRequest,
                        onDrag = ::moveBubble,
                        onStop = ::stopSession,
                        onContinueInDhd = ::continueInDhd,
                    )
                }
            }
        }
        val glow = ComposeView(appContext).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent {
                CompositionLocalProvider(LocalAssistantColors provides colors) {
                    OverlayGlow(
                        sessionState = coordinator.state,
                        hidden = visibilityGate.hidden,
                    )
                }
            }
        }
        val glowLayout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        val savedPosition = OverlayPreferences.bubblePosition(appContext)
        val insets = bubbleInsets()
        val bubblePosition = clampBubblePosition(
            x = savedPosition.x,
            y = savedPosition.y,
            displayWidth = appContext.resources.displayMetrics.widthPixels,
            displayHeight = appContext.resources.displayMetrics.heightPixels,
            bubbleWidth = dp(64),
            bubbleHeight = dp(64),
            topInset = insets.top,
            bottomInset = insets.bottom,
        )
        val panelLayout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bubblePosition.x
            y = bubblePosition.y
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }

        try {
            windowManager.addView(glow, glowLayout)
            windowManager.addView(panel, panelLayout)
            glowView = glow
            panelView = panel
            panelParams = panelLayout
            viewTreeOwner = lifecycleOwner
        } catch (error: RuntimeException) {
            runCatching { windowManager.removeViewImmediate(panel) }
            runCatching { windowManager.removeViewImmediate(glow) }
            lifecycleOwner.destroy()
            android.util.Log.w("DhdOverlay", "Could not attach overlay windows", error)
        }
    }

    private fun updatePanelLayout(expanded: Boolean) {
        val panel = panelView ?: return
        val params = panelParams ?: return
        if (expanded) {
            params.width = WindowManager.LayoutParams.MATCH_PARENT
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            params.gravity = Gravity.BOTTOM or Gravity.START
            params.x = 0
            params.y = 0
            params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        } else {
            val savedPosition = OverlayPreferences.bubblePosition(appContext)
            val insets = bubbleInsets()
            val bubblePosition = clampBubblePosition(
                x = savedPosition.x,
                y = savedPosition.y,
                displayWidth = appContext.resources.displayMetrics.widthPixels,
                displayHeight = appContext.resources.displayMetrics.heightPixels,
                bubbleWidth = dp(64),
                bubbleHeight = dp(64),
                topInset = insets.top,
                bottomInset = insets.bottom,
            )
            params.width = WindowManager.LayoutParams.WRAP_CONTENT
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            params.gravity = Gravity.TOP or Gravity.START
            params.x = bubblePosition.x
            params.y = bubblePosition.y
            params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }
        runCatching { windowManager.updateViewLayout(panel, params) }
    }

    private fun removeViews() {
        panelView?.let { runCatching { windowManager.removeViewImmediate(it) } }
        glowView?.let { runCatching { windowManager.removeViewImmediate(it) } }
        panelView = null
        glowView = null
        panelParams = null
        viewTreeOwner?.destroy()
        viewTreeOwner = null
    }

    private fun submitRequest(request: String) {
        val prefs = appContext.getSharedPreferences(OverlayPreferences.PREFS_NAME, Context.MODE_PRIVATE)
        val reasoningEffort = ReasoningEffort.fromStorage(
            prefs.getString(OverlayPreferences.KEY_REASONING_EFFORT, ReasoningEffort.default.storageValue),
        )?.codexValue ?: ReasoningEffort.default.codexValue
        val fastMode = prefs.getBoolean(OverlayPreferences.KEY_FAST_MODE, false)
        val intent = android.content.Intent(appContext, AssistantForegroundService::class.java)
            .setAction(AssistantForegroundService.ACTION_START)
            .putExtra(AssistantForegroundService.EXTRA_REQUEST, request)
            .putExtra(AssistantForegroundService.EXTRA_CONVERSATION_ID, DHD_CONVERSATION_ID)
            .putExtra(AssistantForegroundService.EXTRA_REASONING_EFFORT, reasoningEffort)
            .putExtra(AssistantForegroundService.EXTRA_FAST_MODE, fastMode)
        ContextCompat.startForegroundService(appContext, intent)
        setPanelMode(OverlayPanelMode.WORKING)
    }

    private fun stopSession() {
        appContext.startService(
            android.content.Intent(appContext, AssistantForegroundService::class.java)
                .setAction(AssistantForegroundService.ACTION_STOP),
        )
    }

    private fun continueInDhd() {
        val state = coordinator.state.value
        val conversationId = when (state) {
            is SessionState.Running -> state.conversationId
            is SessionState.Paused -> state.conversationId
            is SessionState.Stopped -> state.conversationId
            is SessionState.Completed -> state.conversationId
            SessionState.Idle -> DHD_CONVERSATION_ID
        } ?: DHD_CONVERSATION_ID
        appContext.startActivity(
            android.content.Intent(appContext, MainActivity::class.java).apply {
                addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
                putExtra(MainActivity.EXTRA_CONVERSATION_ID, conversationId)
            },
        )
    }

    private fun hideKeyboard() {
        panelView?.windowToken?.let { token ->
            appContext.getSystemService(InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(token, 0)
        }
    }

    private fun assistantColors(): com.phonecontrol.assistant.ui.AssistantColorScheme {
        val prefs = appContext.getSharedPreferences(OverlayPreferences.PREFS_NAME, Context.MODE_PRIVATE)
        val mode = ThemeMode.fromStorage(prefs.getString(OverlayPreferences.KEY_THEME_MODE, "dark"))
        val isSystemDark = (appContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) !=
            Configuration.UI_MODE_NIGHT_NO
        val isDark = when (mode) {
            ThemeMode.SYSTEM -> isSystemDark
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
        return if (isDark) DarkAssistantColors else LightAssistantColors
    }

    private fun dp(value: Int): Int =
        (value * appContext.resources.displayMetrics.density).roundToInt()

    private fun bubbleInsets(): BubbleInsets {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return BubbleInsets()
        val insets = windowManager.currentWindowMetrics.windowInsets
            .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
        return BubbleInsets(top = insets.top, bottom = insets.bottom)
    }

private data class BubbleInsets(
        val top: Int = 0,
        val bottom: Int = 0,
    )
}

private class OverlayViewTreeOwner : SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    init {
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(Bundle())
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun destroy() {
        if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
            savedStateRegistryController.performSave(Bundle())
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
    }
}

private fun SessionState.isActiveForOverlay(): Boolean =
    this is SessionState.Running || this is SessionState.Paused

private fun SessionState.needsAttention(): Boolean =
    when (this) {
        is SessionState.Running -> currentPurpose.equals("Needs your attention", ignoreCase = true)
        is SessionState.Paused -> currentPurpose.equals("Needs your attention", ignoreCase = true)
        else -> false
    }

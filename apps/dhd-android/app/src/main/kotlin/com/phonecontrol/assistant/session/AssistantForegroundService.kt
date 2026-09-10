package com.phonecontrol.assistant.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.phonecontrol.assistant.MainActivity
import com.phonecontrol.assistant.PhoneControlApplication
import com.phonecontrol.assistant.R
import com.phonecontrol.assistant.domain.ReasoningEffort
import com.phonecontrol.assistant.overlay.OverlayPreferences
import com.phonecontrol.assistant.overlay.OverlayWindowController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AssistantForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val coordinator: SessionCoordinator
        get() = (application as PhoneControlApplication).sessionCoordinator
    private lateinit var overlayWindowController: OverlayWindowController
    private val overlayVisibilityGate
        get() = (application as PhoneControlApplication).overlayVisibilityGate

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        val application = application as PhoneControlApplication
        overlayWindowController = OverlayWindowController(
            context = this,
            coordinator = coordinator,
            visibilityGate = overlayVisibilityGate,
            taskPreviewState = application.taskDisplayBackend.previewState,
            onTaskPreviewSurfaceAvailable = { session, surface ->
                application.attachTaskPreview(session, surface)
            },
            onTaskPreviewSurfaceDestroyed = { session, surface ->
                application.detachTaskPreview(session, surface)
            },
        )
        startForegroundCompat(buildNotification(coordinator.state.value))
        serviceScope.launch {
            coordinator.state.collectLatest { state ->
                overlayWindowController.onSessionState(state)
                updateNotification(state)
            }
        }
        serviceScope.launch {
            overlayVisibilityGate.hidden.collectLatest { hidden ->
                overlayWindowController.setHidden(hidden)
            }
        }
        if (overlayEnabledAndPermitted()) {
            overlayWindowController.show()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ENABLE_OVERLAY -> {
                if (overlayEnabledAndPermitted()) {
                    overlayWindowController.show()
                }
            }

            ACTION_DISABLE_OVERLAY -> {
                overlayWindowController.hide()
                if (!coordinator.state.value.isActiveForService()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(startId)
                }
            }

            ACTION_REFRESH -> {
                if (overlayEnabledAndPermitted()) {
                    overlayWindowController.show()
                } else if (!coordinator.state.value.isActiveForService()) {
                    overlayWindowController.hide()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(startId)
                }
            }

            ACTION_SESSION_ENDED -> {
                if (!overlayEnabledAndPermitted() && !coordinator.state.value.isActiveForService()) {
                    overlayWindowController.hide()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(startId)
                } else {
                    updateNotification(coordinator.state.value)
                }
            }

            ACTION_TOGGLE_PAUSE -> coordinator.togglePause()
            ACTION_CONTINUE -> {
                if (!coordinator.continueStopped()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(startId)
                }
            }
            ACTION_STOP -> {
                coordinator.stop("Stopped from the notification.")
                removeAttentionNotification(this)
                if (!overlayEnabledAndPermitted()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(startId)
                } else {
                    updateNotification(coordinator.state.value)
                }
            }

            ACTION_START, null -> {
                if (overlayEnabledAndPermitted()) {
                    overlayWindowController.show()
                }
                val request = intent?.getStringExtra(EXTRA_REQUEST)
                    ?.takeIf(String::isNotBlank)
                if (request != null) {
                    coordinator.start(
                        request = request,
                        conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID),
                        reasoningEffort = intent.getStringExtra(EXTRA_REASONING_EFFORT)
                            ?: ReasoningEffort.default.codexValue,
                        fastMode = intent.getBooleanExtra(EXTRA_FAST_MODE, false),
                    )
                }
            }
        }
        return if (overlayEnabledAndPermitted()) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        if (::overlayWindowController.isInitialized) {
            overlayWindowController.destroy()
        }
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateNotification(state: SessionState) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(state: SessionState): Notification {
        val isActive = state is SessionState.Running || state is SessionState.Paused
        val overlayEnabled = overlayEnabledAndPermitted()
        val isPaused = state is SessionState.Paused
        val status = when (state) {
            SessionState.Idle -> if (overlayEnabled) "Overlay ready" else "Ready"
            is SessionState.Running -> notificationPurpose(state.currentPurpose)
            is SessionState.Paused -> "Paused · ${notificationPurpose(state.currentPurpose)}"
            is SessionState.Stopped -> if (overlayEnabled) "Overlay ready · Stopped" else "Stopped"
            is SessionState.Completed -> if (overlayEnabled) "Overlay ready · Completed" else "Completed"
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_CONVERSATION_ID, state.conversationIdOrNull())
            },
            PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentImmutableFlag(),
        )
        val pauseIntent = PendingIntent.getService(
            this,
            REQUEST_TOGGLE_PAUSE,
            Intent(this, AssistantForegroundService::class.java).setAction(ACTION_TOGGLE_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentImmutableFlag(),
        )
        val stopIntent = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, AssistantForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentImmutableFlag(),
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(status)
            .setStyle(NotificationCompat.BigTextStyle().bigText(status))
            .setContentIntent(contentIntent)
            .setOngoing(isActive || overlayEnabled)
            .setOnlyAlertOnce(true)
        if (isActive) {
            builder
                .addAction(
                    if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                    if (isPaused) "Resume" else "Pause",
                    pauseIntent,
                )
                .addAction(android.R.drawable.ic_delete, "Stop", stopIntent)
        }
        return builder.build()
    }

    private fun notificationPurpose(purpose: String): String = when {
        purpose.equals("Preparing request", ignoreCase = true) -> "Connecting to Codex…"
        purpose.equals("Codex is planning", ignoreCase = true) -> "Thinking…"
        purpose.equals("Waiting for desktop Codex bridge", ignoreCase = true) -> "Companion not connected"
        purpose.equals("Needs your attention", ignoreCase = true) -> "DHD needs your attention"
        else -> purpose
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannels() {
        createNotificationChannels(this)
    }

    private fun overlayEnabledAndPermitted(): Boolean =
        OverlayPreferences.isEnabled(this) && Settings.canDrawOverlays(this)

    private fun pendingIntentImmutableFlag(): Int =
        pendingIntentFlags()

    companion object {
        const val ACTION_START = "com.phonecontrol.assistant.action.START"
        const val ACTION_ENABLE_OVERLAY = "com.phonecontrol.assistant.action.ENABLE_OVERLAY"
        const val ACTION_DISABLE_OVERLAY = "com.phonecontrol.assistant.action.DISABLE_OVERLAY"
        const val ACTION_REFRESH = "com.phonecontrol.assistant.action.REFRESH"
        const val ACTION_SESSION_ENDED = "com.phonecontrol.assistant.action.SESSION_ENDED"
        const val ACTION_TOGGLE_PAUSE = "com.phonecontrol.assistant.action.TOGGLE_PAUSE"
        const val ACTION_CONTINUE = "com.phonecontrol.assistant.action.CONTINUE"
        const val ACTION_STOP = "com.phonecontrol.assistant.action.STOP"
        const val EXTRA_REQUEST = "com.phonecontrol.assistant.extra.REQUEST"
        const val EXTRA_CONVERSATION_ID = "com.phonecontrol.assistant.extra.CONVERSATION_ID"
        const val EXTRA_REASONING_EFFORT = "com.phonecontrol.assistant.extra.REASONING_EFFORT"
        const val EXTRA_FAST_MODE = "com.phonecontrol.assistant.extra.FAST_MODE"

        private const val CHANNEL_ID = "assistant_sessions"
        private const val RESULT_CHANNEL_ID = "assistant_results"
        private const val ATTENTION_CHANNEL_ID = "assistant_attention"
        private const val NOTIFICATION_ID = 4201
        private const val REQUEST_OPEN_APP = 4202
        private const val REQUEST_TOGGLE_PAUSE = 4203
        private const val REQUEST_STOP = 4204
        private const val COMPLETION_NOTIFICATION_ID = 4205
        private const val ATTENTION_NOTIFICATION_ID = 4206
        private const val MAX_NOTIFICATION_TEXT_CHARS = 240

        /** Remove the in-progress notification when a bridge-owned run ends. */
        fun removeSessionNotification(context: Context) {
            context.getSystemService(NotificationManager::class.java)
                .cancel(NOTIFICATION_ID)
        }

        /** Keep the foreground host alive only for an active task or enabled overlay. */
        fun reconcileLifetime(context: Context) {
            val appContext = context.applicationContext
            val application = appContext as? PhoneControlApplication ?: return
            val active = application.sessionCoordinator.state.value.isActiveForService()
            val overlayAvailable = OverlayPreferences.isEnabled(appContext) && Settings.canDrawOverlays(appContext)
            if (!overlayAvailable && !active) {
                appContext.stopService(Intent(appContext, AssistantForegroundService::class.java))
            } else {
                ContextCompat.startForegroundService(
                    appContext,
                    Intent(appContext, AssistantForegroundService::class.java)
                        .setAction(ACTION_SESSION_ENDED),
                )
            }
        }

        /** Post a result notification without bringing the assistant to the foreground. */
        fun showCompletionNotification(context: Context, message: String, conversationId: String? = null) {
            createNotificationChannels(context)
            val preview = completionNotificationPreview(message)
            val notification = NotificationCompat.Builder(context, RESULT_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(context.getString(R.string.notification_completion_title))
                .setContentText(preview)
                .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
                .setContentIntent(openAssistantIntent(context, REQUEST_OPEN_APP + 1, conversationId))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build()
            context.getSystemService(NotificationManager::class.java)
                .notify(COMPLETION_NOTIFICATION_ID, notification)
        }

        /** Notify the user without launching an Activity or interrupting Watch mode. */
        fun showAttentionNotification(context: Context, reason: String, conversationId: String? = null) {
            createNotificationChannels(context)
            val safeReason = reason.trim()
                .ifBlank { "The phone assistant needs your attention." }
                .take(MAX_NOTIFICATION_TEXT_CHARS)
            val notification = NotificationCompat.Builder(context, ATTENTION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("DHD needs your attention")
                .setContentText(safeReason)
                .setStyle(NotificationCompat.BigTextStyle().bigText(safeReason))
                .setContentIntent(openAssistantIntent(context, REQUEST_OPEN_APP + 2, conversationId))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .build()
            context.getSystemService(NotificationManager::class.java)
                .notify(ATTENTION_NOTIFICATION_ID, notification)
        }

        fun removeAttentionNotification(context: Context) {
            context.getSystemService(NotificationManager::class.java)
                .cancel(ATTENTION_NOTIFICATION_ID)
        }

        private fun createNotificationChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannels(
                listOf(
                    NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.notification_channel_name),
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply {
                        description = context.getString(R.string.notification_channel_description)
                    },
                    NotificationChannel(
                        RESULT_CHANNEL_ID,
                        context.getString(R.string.notification_result_channel_name),
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ).apply {
                        description = context.getString(R.string.notification_result_channel_description)
                    },
                    NotificationChannel(
                        ATTENTION_CHANNEL_ID,
                        context.getString(R.string.notification_attention_channel_name),
                        NotificationManager.IMPORTANCE_HIGH,
                    ).apply {
                        description = context.getString(R.string.notification_attention_channel_description)
                    },
                ),
            )
        }

        private fun openAssistantIntent(context: Context, requestCode: Int, conversationId: String? = null): PendingIntent =
            PendingIntent.getActivity(
                context,
                requestCode,
                Intent(context, MainActivity::class.java).apply {
                    if (!conversationId.isNullOrBlank()) {
                        putExtra(MainActivity.EXTRA_CONVERSATION_ID, conversationId)
                    }
                },
                PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentFlags(),
            )

        private fun pendingIntentFlags(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
    }
}

private fun SessionState.conversationIdOrNull(): String? = when (this) {
    SessionState.Idle -> null
    is SessionState.Running -> conversationId
    is SessionState.Paused -> conversationId
    is SessionState.Stopped -> conversationId
    is SessionState.Completed -> conversationId
}

private fun SessionState.isActiveForService(): Boolean =
    this is SessionState.Running || this is SessionState.Paused

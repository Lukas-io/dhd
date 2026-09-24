package com.phonecontrol.assistant.overlay

import android.content.Context

object OverlayPreferences {
    const val PREFS_NAME = "dhd_ui_preferences"
    const val KEY_OVERLAY_ENABLED = "pref_overlay_enabled"
    const val KEY_BUBBLE_X = "pref_overlay_bubble_x"
    const val KEY_BUBBLE_Y = "pref_overlay_bubble_y"
    const val KEY_REASONING_EFFORT = "pref_reasoning_effort"
    const val KEY_VISIBLE_REASONING_EFFORTS = "pref_visible_reasoning_efforts"
    const val KEY_FAST_MODE = "pref_fast_mode"
    const val KEY_THEME_MODE = "pref_theme_mode"

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        preferences(context).getBoolean(KEY_OVERLAY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_OVERLAY_ENABLED, enabled).apply()
    }

    fun bubblePosition(context: Context): BubblePosition = {
        val prefs = preferences(context)
        BubblePosition(
            x = prefs.getInt(KEY_BUBBLE_X, 24),
            y = prefs.getInt(KEY_BUBBLE_Y, 240),
        )
    }()

    fun setBubblePosition(context: Context, position: BubblePosition) {
        preferences(context).edit()
            .putInt(KEY_BUBBLE_X, position.x)
            .putInt(KEY_BUBBLE_Y, position.y)
            .apply()
    }
}

package com.tapnex.autoclicker.settings

import android.content.Context

/**
 * Settings for the second, independent floating button. They are stored separately from the main
 * floating control's settings so the two controls never share state.
 */
object SecondaryControlSettings {
    const val DEFAULT_SIZE_DP = 48
    const val MIN_SIZE_DP = 32
    const val MAX_SIZE_DP = 80

    private const val PREFS = "tapnex_secondary_control"
    private const val KEY_SIZE_DP = "size_dp"
    private const val KEY_HAS_POSITION = "has_position"
    private const val KEY_X = "pos_x"
    private const val KEY_Y = "pos_y"

    data class Snapshot(val sizeDp: Int, val hasPosition: Boolean, val x: Int, val y: Int)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): Snapshot {
        val p = prefs(context)
        return Snapshot(
            sizeDp = p.getInt(KEY_SIZE_DP, DEFAULT_SIZE_DP).coerceIn(MIN_SIZE_DP, MAX_SIZE_DP),
            hasPosition = p.getBoolean(KEY_HAS_POSITION, false),
            x = p.getInt(KEY_X, 0),
            y = p.getInt(KEY_Y, 0)
        )
    }

    fun saveSizeDp(context: Context, sizeDp: Int) {
        prefs(context).edit().putInt(KEY_SIZE_DP, sizeDp.coerceIn(MIN_SIZE_DP, MAX_SIZE_DP)).apply()
    }

    fun savePosition(context: Context, x: Int, y: Int) {
        prefs(context).edit().putBoolean(KEY_HAS_POSITION, true).putInt(KEY_X, x).putInt(KEY_Y, y).apply()
    }

    fun clearPosition(context: Context) {
        prefs(context).edit().putBoolean(KEY_HAS_POSITION, false).apply()
    }

    fun restoreDefaults(context: Context) {
        prefs(context).edit()
            .putInt(KEY_SIZE_DP, DEFAULT_SIZE_DP)
            .putBoolean(KEY_HAS_POSITION, false)
            .apply()
    }
}

/**
 * The second button's action: a generic, user-started tap-assistance toggle. It runs the user's own
 * enabled click points through the shared accessibility engine, and only while the user has turned
 * on TAPNEX's accessibility service. It targets no app or game and bypasses no system restriction.
 */
object SecondaryAction {
    const val DESCRIPTION =
        "Tap once to start tapping your enabled click points. Tap again to stop. Speed comes from Floating control settings."
}

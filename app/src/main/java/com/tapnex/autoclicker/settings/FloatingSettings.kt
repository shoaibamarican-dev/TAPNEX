package com.tapnex.autoclicker.settings

import android.content.Context

/**
 * Local settings for the floating control, stored in SharedPreferences.
 * Speed 1 = slowest, 200 = fastest. Speed applies live to the running clicker.
 */
object FloatingSettings {
    const val MIN_SPEED = 1
    const val MAX_SPEED = 200
    const val DEFAULT_SPEED = 200
    const val DEFAULT_SIZE_DP = 56
    const val MIN_SIZE_DP = 40
    const val MAX_SIZE_DP = 88

    private const val PREFS = "tapnex_floating_settings"
    private const val KEY_SPEED = "speed"
    private const val KEY_SIZE_DP = "size_dp"
    private const val KEY_HAS_POSITION = "has_position"
    private const val KEY_X = "pos_x"
    private const val KEY_Y = "pos_y"
    private const val KEY_LOCKED = "locked"
    private const val KEY_SPEED_CUSTOM = "speed_custom"
    private const val KEY_SHOW_POINTS = "show_points"
    private const val KEY_HOLD = "hold_enabled"

    data class Snapshot(
        val speed: Int,
        val sizeDp: Int,
        val hasPosition: Boolean,
        val x: Int,
        val y: Int,
        val locked: Boolean,
        val speedCustom: Boolean = true,
        val showPoints: Boolean = true,
        val holdEnabled: Boolean = false
    ) {
        val intervalMs: Long
            get() = speedToIntervalMs(speed)
    }

    /** Delay between taps. Speed 200 asks for 20 ms; Android's gesture completion limits the real rate. */
    fun speedToIntervalMs(speed: Int): Long {
        val s = speed.coerceIn(0, MAX_SPEED)
        return (1000L - s * 5L).coerceAtLeast(20L)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): Snapshot {
        val p = prefs(context)
        return Snapshot(
            speed = p.getInt(KEY_SPEED, DEFAULT_SPEED).coerceIn(MIN_SPEED, MAX_SPEED),
            sizeDp = p.getInt(KEY_SIZE_DP, DEFAULT_SIZE_DP).coerceIn(MIN_SIZE_DP, MAX_SIZE_DP),
            hasPosition = p.getBoolean(KEY_HAS_POSITION, false),
            x = p.getInt(KEY_X, 0),
            y = p.getInt(KEY_Y, 0),
            locked = p.getBoolean(KEY_LOCKED, false),
            speedCustom = p.getBoolean(KEY_SPEED_CUSTOM, true),
            showPoints = p.getBoolean(KEY_SHOW_POINTS, true),
            holdEnabled = p.getBoolean(KEY_HOLD, false)
        )
    }

    fun saveSpeed(context: Context, speed: Int) {
        prefs(context).edit()
            .putInt(KEY_SPEED, speed.coerceIn(MIN_SPEED, MAX_SPEED))
            .putBoolean(KEY_SPEED_CUSTOM, true)
            .apply()
    }

    fun clearSpeedCustom(context: Context) {
        prefs(context).edit().putBoolean(KEY_SPEED_CUSTOM, false).apply()
    }

    fun saveShowPoints(context: Context, show: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_POINTS, show).apply()
    }

    fun saveHold(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_HOLD, enabled).apply()
    }

    fun saveSizeDp(context: Context, sizeDp: Int) {
        prefs(context).edit().putInt(KEY_SIZE_DP, sizeDp.coerceIn(MIN_SIZE_DP, MAX_SIZE_DP)).apply()
    }

    fun savePosition(context: Context, x: Int, y: Int) {
        prefs(context).edit()
            .putBoolean(KEY_HAS_POSITION, true)
            .putInt(KEY_X, x)
            .putInt(KEY_Y, y)
            .apply()
    }

    fun saveLocked(context: Context, locked: Boolean) {
        prefs(context).edit().putBoolean(KEY_LOCKED, locked).apply()
    }

    fun clearPosition(context: Context) {
        prefs(context).edit().putBoolean(KEY_HAS_POSITION, false).apply()
    }

    fun restoreDefaults(context: Context) {
        prefs(context).edit()
            .putInt(KEY_SPEED, DEFAULT_SPEED)
            .putInt(KEY_SIZE_DP, DEFAULT_SIZE_DP)
            .putBoolean(KEY_HAS_POSITION, false)
            .putBoolean(KEY_LOCKED, false)
            .putBoolean(KEY_HOLD, false)
            .putBoolean(KEY_SHOW_POINTS, true)
            .apply()
    }
}

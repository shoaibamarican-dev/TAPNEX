package com.tapnex.autoclicker.settings

import android.content.Context

/**
 * Local settings for the floating control, stored in SharedPreferences.
 * Speed 0 = slowest (5 s between taps), 100 = fastest (100 ms between taps).
 * The speed applies to sessions started from the floating control, and overrides the per-point interval.
 * Speed, size and lock state are re-read by the running floating control, so changes apply live.
 */
object FloatingSettings {
    const val DEFAULT_SPEED = 50
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

    data class Snapshot(
        val speed: Int,
        val sizeDp: Int,
        val hasPosition: Boolean,
        val x: Int,
        val y: Int,
        val locked: Boolean,
        /** False until the user moves the speed slider; until then each point's own interval is used. */
        val speedCustom: Boolean = false,
        val showPoints: Boolean = true
    ) {
        val intervalMs: Long
            get() = speedToIntervalMs(speed)
    }

    fun speedToIntervalMs(speed: Int): Long {
        val s = speed.coerceIn(0, 100)
        return 5000L - (s * 4900L / 100L)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): Snapshot {
        val p = prefs(context)
        return Snapshot(
            speed = p.getInt(KEY_SPEED, DEFAULT_SPEED).coerceIn(0, 100),
            sizeDp = p.getInt(KEY_SIZE_DP, DEFAULT_SIZE_DP).coerceIn(MIN_SIZE_DP, MAX_SIZE_DP),
            hasPosition = p.getBoolean(KEY_HAS_POSITION, false),
            x = p.getInt(KEY_X, 0),
            y = p.getInt(KEY_Y, 0),
            locked = p.getBoolean(KEY_LOCKED, false),
            speedCustom = p.getBoolean(KEY_SPEED_CUSTOM, false),
            showPoints = p.getBoolean(KEY_SHOW_POINTS, true)
        )
    }

    fun saveSpeed(context: Context, speed: Int) {
        prefs(context).edit().putInt(KEY_SPEED, speed.coerceIn(0, 100)).putBoolean(KEY_SPEED_CUSTOM, true).apply()
    }

    fun clearSpeedCustom(context: Context) {
        prefs(context).edit().putBoolean(KEY_SPEED_CUSTOM, false).apply()
    }

    fun saveShowPoints(context: Context, show: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_POINTS, show).apply()
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
            .apply()
    }
}

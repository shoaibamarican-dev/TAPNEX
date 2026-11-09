package com.tapnex.autoclicker.model

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ClickPoint(
    val id: String = UUID.randomUUID().toString(),
    val label: String = "Tap",
    val nx: Float,
    val ny: Float,
    val enabled: Boolean = true,
    val locked: Boolean = false,
    val intervalMs: Long = 500L,
    val opacityPercent: Int = 35,
    val sizeDp: Int = 36
)

object PointStore {
    private const val PREFS = "tapnex_points"
    private const val KEY = "points"
    // Set context once from an Android entry point; retained only as configuration, not wallet authority.
    private var context: Context? = null
    fun initialize(ctx: Context) { context = ctx.applicationContext }
    fun load(): List<ClickPoint> {
        val raw = context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ClickPoint(o.getString("id"), o.getString("label"), o.getDouble("nx").toFloat(),
                    o.getDouble("ny").toFloat(), o.optBoolean("enabled", true), o.optBoolean("locked", false),
                    o.optLong("intervalMs", 500L),
                    o.optInt("opacityPercent", 35).coerceIn(10, 50),
                    o.optInt("sizeDp", 36).coerceIn(28, 64))
            }
        }.getOrDefault(emptyList())
    }
    fun save(points: List<ClickPoint>) {
        val arr = JSONArray()
        points.forEach { p ->
            arr.put(JSONObject().put("id", p.id).put("label", p.label).put("nx", p.nx).put("ny", p.ny)
                .put("enabled", p.enabled).put("locked", p.locked).put("intervalMs", p.intervalMs)
                .put("opacityPercent", p.opacityPercent).put("sizeDp", p.sizeDp))
        }
        context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putString(KEY, arr.toString())?.apply()
    }
}

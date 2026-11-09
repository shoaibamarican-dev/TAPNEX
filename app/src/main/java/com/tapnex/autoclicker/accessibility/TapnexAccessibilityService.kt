package com.tapnex.autoclicker.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import com.tapnex.autoclicker.Permissions
import com.tapnex.autoclicker.overlay.FloatingControlService
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.tapnex.autoclicker.model.ClickPoint
import com.tapnex.autoclicker.model.PointStore

/**
 * Dispatches only the user-configured tap points. Foreground detection reads only the package name;
 * TAPNEX does not inspect window text, nodes, or screen content.
 */
class TapnexAccessibilityService : AccessibilityService() {

    enum class EngineState { IDLE, RUNNING, PAUSED }

    companion object {
        /** Owner ids: which floating control started the current tap session. */
        const val OWNER_MAIN = "main"
        const val OWNER_SECOND = "second"

        /** Non-null only while the system has connected this service. Used by the overlay. */
        @Volatile
        var instance: TapnexAccessibilityService? = null
            private set
    }

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    var engineState: EngineState = EngineState.IDLE
        private set

    /** Which floating control started the current session, or null when idle. */
    @Volatile
    var sessionOwner: String? = null
        private set

    private var cursor = 0
    private var permissionPromptedForTarget: String? = null

    // Incremented whenever the loop is started, stopped or resumed, so callbacks from an older loop are ignored.
    private var generation = 0L

    private val tick = Runnable { dispatchNext(generation) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Only observe the foreground package name. No screen text, nodes, or content are read.
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val target = getSharedPreferences("tapnex_target", MODE_PRIVATE).getString("package", null) ?: return
        val foregroundPackage = event.packageName?.toString() ?: return
        if (foregroundPackage == target) {
            if (Permissions.canDrawOverlay(this) && instance === this) {
                runCatching { startForegroundService(Intent(this, FloatingControlService::class.java)) }
                permissionPromptedForTarget = null
            } else if (!Permissions.canDrawOverlay(this) && permissionPromptedForTarget != target) {
                permissionPromptedForTarget = target
                runCatching {
                    startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        } else if (foregroundPackage != packageName) {
            permissionPromptedForTarget = null
            // Leaving the selected target hides controls and stops any active click session.
            stopEngine()
            stopService(Intent(this, FloatingControlService::class.java))
            stopService(Intent(this, com.tapnex.autoclicker.overlay.SecondaryControlService::class.java))
        }
    }

    override fun onInterrupt() {
        stopEngine()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopEngine()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stopEngine()
        instance = null
        super.onDestroy()
    }

    /** Returns null when tapping started, otherwise a message the user can read. */
    /** When set (by the floating control's speed setting), replaces each point's interval. */
    @Volatile
    var intervalOverrideMs: Long? = null

    private fun intervalFor(point: ClickPoint): Long = intervalOverrideMs ?: point.intervalMs

    fun startEngine(owner: String = OWNER_MAIN): String? {
        if (engineState == EngineState.RUNNING) return null
        if (PointStore.load().none { it.enabled }) {
            return "Add and enable at least one click point first."
        }
        generation++
        cursor = 0
        sessionOwner = owner
        engineState = EngineState.RUNNING
        handler.post(tick)
        return null
    }

    fun pauseEngine() {
        if (engineState != EngineState.RUNNING) return
        engineState = EngineState.PAUSED
        handler.removeCallbacks(tick)
    }

    fun resumeEngine() {
        if (engineState != EngineState.PAUSED) return
        engineState = EngineState.RUNNING
        generation++
        handler.post(tick)
    }

    fun stopEngine() {
        sessionOwner = null
        engineState = EngineState.IDLE
        generation++
        handler.removeCallbacks(tick)
    }

    private fun dispatchNext(gen: Long) {
        if (gen != generation || engineState != EngineState.RUNNING) return
        val active = PointStore.load().filter { it.enabled }
        if (active.isEmpty()) {
            stopEngine()
            return
        }
        val point = active[cursor % active.size]
        cursor++

        val dm = resources.displayMetrics
        val x = (point.nx.coerceIn(0f, 1f) * dm.widthPixels).coerceIn(0f, (dm.widthPixels - 1).toFloat())
        val y = (point.ny.coerceIn(0f, 1f) * dm.heightPixels).coerceIn(0f, (dm.heightPixels - 1).toFloat())
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()

        val accepted = dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                scheduleNext(gen, intervalFor(point))
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                scheduleNext(gen, intervalFor(point))
            }
        }, handler)

        if (!accepted) stopEngine()
    }

    private fun scheduleNext(gen: Long, delayMs: Long) {
        if (gen != generation || engineState != EngineState.RUNNING) return
        handler.postDelayed(tick, delayMs.coerceAtLeast(100L))
    }
}

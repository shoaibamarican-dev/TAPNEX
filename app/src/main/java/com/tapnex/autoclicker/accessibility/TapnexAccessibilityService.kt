package com.tapnex.autoclicker.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import com.tapnex.autoclicker.overlay.FloatingController
import com.tapnex.autoclicker.overlay.ScreenUtil
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

    // Incremented whenever the loop is started, stopped or resumed, so callbacks from an older loop are ignored.
    private var generation = 0L

    private val tick = Runnable { dispatchNext(generation) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    // ---------------------------------------------------------------- floating controls

    private var controller: FloatingController? = null

    /** Ignore window events from these packages: they appear on top of games without leaving them. */
    private val ignoredPackages = setOf(
        "com.android.systemui",
        "android",
        "com.google.android.gms",
        "com.android.permissioncontroller",
        "com.miui.securitycenter",
        "com.miui.gamebooster"
    )

    // After TAPNEX itself launches the target, events from the TAPNEX screen closing must not hide the controls.
    private var graceUntil = 0L

    fun showControls() {
        if (controller != null) return
        controller = FloatingController(this).also { it.show() }
    }

    fun hideControls() {
        controller?.destroy()
        controller = null
        stopEngine()
        intervalOverrideMs = null
    }

    /** Called by the TAPNEX app right after it launches the selected target app. */
    fun onTargetLaunchedByApp() {
        graceUntil = SystemClock.uptimeMillis() + 6000L
        showControls()
    }

    private fun currentImePackage(): String? =
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Only the foreground package name is read. No screen text, nodes, or content are read.
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val target = getSharedPreferences("tapnex_target", MODE_PRIVATE).getString("package", null) ?: return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString().orEmpty()

        if (pkg == target) {
            showControls()
            return
        }
        // Our own floating windows and ad screens are not "another app". Only the TAPNEX home screen is.
        if (pkg == packageName && !cls.endsWith("MainActivity")) return
        if (pkg in ignoredPackages || pkg == currentImePackage()) return
        if (SystemClock.uptimeMillis() < graceUntil) return
        hideControls()
    }

    override fun onInterrupt() {
        stopEngine()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        hideControls()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        hideControls()
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

        val (screenW, screenH) = ScreenUtil.realSize(this)
        val x = (point.nx.coerceIn(0f, 1f) * screenW).coerceIn(0f, (screenW - 1).toFloat())
        val y = (point.ny.coerceIn(0f, 1f) * screenH).coerceIn(0f, (screenH - 1).toFloat())
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

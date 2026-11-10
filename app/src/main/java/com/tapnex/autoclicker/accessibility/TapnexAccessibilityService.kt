package com.tapnex.autoclicker.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.tapnex.autoclicker.model.ClickPoint
import com.tapnex.autoclicker.model.PointStore
import com.tapnex.autoclicker.overlay.FloatingController
import com.tapnex.autoclicker.overlay.ScreenUtil
import com.tapnex.autoclicker.overlay.SecondaryControlService

/**
 * Dispatches only the user-configured tap points. Foreground detection reads only the package name;
 * TAPNEX does not inspect window text, nodes, or screen content.
 */
class TapnexAccessibilityService : AccessibilityService() {

    enum class EngineState { IDLE, RUNNING, PAUSED }

    companion object {
        const val OWNER_MAIN = "main"
        const val OWNER_SECOND = "second"

        /** Non-null only while the system has connected this service. */
        @Volatile
        var instance: TapnexAccessibilityService? = null
            private set
    }

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    var engineState: EngineState = EngineState.IDLE
        private set

    @Volatile
    var sessionOwner: String? = null
        private set

    /** When set, replaces each point's own interval (speed slider). */
    @Volatile
    var intervalOverrideMs: Long? = null

    private var cursor = 0
    private var generation = 0L
    private val tick = Runnable { dispatchNext(generation) }

    private var controller: FloatingController? = null
    private var foreground: String? = null
    private var graceUntil = 0L

    /** Packages whose windows (notifications, IME, system dialogs) must not count as a foreground change. */
    private val ignoredPackages = setOf(
        "com.android.systemui",
        "android",
        "com.google.android.gms",
        "com.android.permissioncontroller",
        "com.miui.securitycenter",
        "com.miui.gamebooster"
    )

    /** Runs after a short delay when a real other app came to the front; hides the controls only if it stays. */
    private val hideCheck = Runnable {
        if (foreground != targetPackage() && SystemClock.uptimeMillis() >= graceUntil) hideControls()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    private fun targetPackage(): String? =
        getSharedPreferences("tapnex_target", MODE_PRIVATE).getString("package", null)

    private fun currentImePackage(): String? =
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString().orEmpty()
        // Our own floating windows are not a foreground change; the TAPNEX home screen is.
        if (pkg == packageName && !cls.endsWith("MainActivity")) return
        if (pkg in ignoredPackages || pkg == currentImePackage()) return

        foreground = pkg
        handler.removeCallbacks(hideCheck)
        if (pkg == targetPackage()) {
            showControls()
        } else {
            handler.postDelayed(hideCheck, 600L)
        }
    }

    // ---------------------------------------------------------------- floating controls

    fun showControls() {
        if (controller != null) return
        controller = FloatingController(this).also { it.show() }
    }

    fun hideControls() {
        controller?.destroy()
        controller = null
        stopEngine()
        intervalOverrideMs = null
        runCatching { stopService(Intent(this, SecondaryControlService::class.java)) }
    }

    /** Called by the TAPNEX app right after it launches the selected target app. */
    fun onTargetLaunchedByApp() {
        graceUntil = SystemClock.uptimeMillis() + 6000L
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

    // ---------------------------------------------------------------- tap engine

    /** Returns null when tapping started, otherwise a message the user can read. */
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

    private fun intervalFor(point: ClickPoint): Long = intervalOverrideMs ?: point.intervalMs

    private fun dispatchNext(gen: Long) {
        if (gen != generation || engineState != EngineState.RUNNING) return
        val active = PointStore.load().filter { it.enabled }
        if (active.isEmpty()) {
            stopEngine()
            return
        }
        val point = active[cursor % active.size]
        cursor++

        val (sw, sh) = ScreenUtil.realSize(this)
        val x = (point.nx.coerceIn(0f, 1f) * sw).coerceIn(0f, (sw - 1).toFloat())
        val y = (point.ny.coerceIn(0f, 1f) * sh).coerceIn(0f, (sh - 1).toFloat())
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 20))
            .build()

        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
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
        handler.postDelayed(tick, delayMs.coerceAtLeast(0L))
    }
}

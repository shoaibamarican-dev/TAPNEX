package com.tapnex.autoclicker.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.tapnex.autoclicker.Permissions
import com.tapnex.autoclicker.R
import com.tapnex.autoclicker.accessibility.TapnexAccessibilityService
import com.tapnex.autoclicker.accessibility.TapnexAccessibilityService.EngineState
import com.tapnex.autoclicker.model.PointStore
import com.tapnex.autoclicker.settings.FloatingSettings
import com.tapnex.autoclicker.settings.SecondaryControlSettings
import kotlin.math.abs

/**
 * Independent second floating button: a tap-assistance toggle.
 *  - One tap starts tapping (the user's enabled click points, using the shared accessibility engine).
 *    Another tap stops it. Drag moves the button and does not toggle it.
 *  - Its own window, notification, saved position and size, independent of the main control.
 *  - It only stops a session it started. Removing it never stops the main control's session.
 *  - Colour: grey = accessibility off, amber = no enabled click points, teal = ready (stopped),
 *    bright green = tapping (active).
 *  - Speed comes from the floating control settings and applies live while tapping.
 */
class SecondaryControlService : Service() {

    companion object {
        const val ACTION_STOP = "com.tapnex.autoclicker.action.STOP_SECONDARY"
        private const val CHANNEL_ID = "tapnex_secondary"
        private const val NOTIFICATION_ID = 1002
        private const val DRAG_SLOP_PX = 12f
        private const val REFRESH_MS = 400L
        private val COLOR_OFF = Color.parseColor("#6B7280")
        private val COLOR_NO_POINTS = Color.parseColor("#E0A030")
        private val COLOR_READY = Color.parseColor("#2A6F5E")
        private val COLOR_ACTIVE = Color.parseColor("#1FD39A")

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, SecondaryControlService::class.java))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var button: ImageView? = null
    private var buttonBackground: GradientDrawable? = null
    private var buttonParams: WindowManager.LayoutParams? = null
    private var sizePx = 0

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var moved = false

    /** True only while this control has started the current tap session. */
    private var startedHere = false

    private val refresh = object : Runnable {
        override fun run() {
            if (!Permissions.canDrawOverlay(this@SecondaryControlService)) {
                stopSelf()
                return
            }
            syncWithEngine()
            applyLiveSettings()
            updateColor()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_NOT_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        // Must run first: the system requires startForeground soon after startForegroundService.
        startAsForeground()
        if (!Permissions.canDrawOverlay(this)) {
            stopSelf()
            return
        }
        buildButton()
        handler.post(refresh)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        // Stop safely: end only the session this control started.
        TapnexAccessibilityService.instance?.let { svc ->
            if (startedHere && svc.sessionOwner == TapnexAccessibilityService.OWNER_SECOND) svc.stopEngine()
        }
        startedHere = false
        button?.let { runCatching { wm().removeView(it) } }
        button = null
        buttonParams = null
        buttonBackground = null
        super.onDestroy()
    }

    private fun wm(): WindowManager {
        windowManager?.let { return it }
        val manager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = manager
        return manager
    }

    private fun density(): Float = resources.displayMetrics.density
    private fun screenW(): Int = resources.displayMetrics.widthPixels
    private fun screenH(): Int = resources.displayMetrics.heightPixels

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "TAPNEX second floating button", NotificationManager.IMPORTANCE_LOW)
        )
        val closeIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, SecondaryControlService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TAPNEX second button is on")
            .setContentText("Tap the floating button to start or stop tapping. Close removes it.")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Close", closeIntent)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun clampToScreen(x: Int, y: Int, size: Int): Pair<Int, Int> {
        val maxX = (screenW() - size).coerceAtLeast(0)
        val maxY = (screenH() - size).coerceAtLeast(0)
        return Pair(x.coerceIn(0, maxX), y.coerceIn(0, maxY))
    }

    private fun buildButton() {
        if (button != null) return // Never create a second button.
        val settings = SecondaryControlSettings.load(this)
        sizePx = (settings.sizeDp * density()).toInt()
        val pad = (4 * density()).toInt()

        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(COLOR_OFF)
        }
        buttonBackground = bg

        val view = ImageView(this).apply {
            setImageResource(R.drawable.floating_icon)
            setPadding(pad, pad, pad, pad)
            this.background = bg
            contentDescription = "TAPNEX second floating button"
            setOnTouchListener { v, event -> onTouch(v, event) }
        }
        button = view

        val start = if (settings.hasPosition) {
            clampToScreen(settings.x, settings.y, sizePx)
        } else {
            clampToScreen((24 * density()).toInt(), (380 * density()).toInt(), sizePx)
        }
        val lp = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = start.first
            y = start.second
        }
        buttonParams = lp
        wm().addView(view, lp)
    }

    /** Re-reads size and speed so both apply while the button is shown. */
    private fun applyLiveSettings() {
        val settings = SecondaryControlSettings.load(this)
        val newSize = (settings.sizeDp * density()).toInt()
        if (button != null && newSize != sizePx) {
            sizePx = newSize
            applySize(sizePx)
        }
        val svc = TapnexAccessibilityService.instance
        if (svc != null && svc.sessionOwner == TapnexAccessibilityService.OWNER_SECOND) {
            svc.intervalOverrideMs = FloatingSettings.load(this).intervalMs
        }
    }

    private fun applySize(px: Int) {
        val view = button ?: return
        val lp = buttonParams ?: return
        val pos = clampToScreen(lp.x, lp.y, px)
        lp.width = px
        lp.height = px
        lp.x = pos.first
        lp.y = pos.second
        wm().updateViewLayout(view, lp)
    }

    /** Clears our ownership flag if the session ended by another route (main control, accessibility off). */
    private fun syncWithEngine() {
        val svc = TapnexAccessibilityService.instance
        if (startedHere && (svc == null || svc.engineState != EngineState.RUNNING)) startedHere = false
    }

    private fun onTouch(v: View, event: MotionEvent): Boolean {
        val lp = buttonParams ?: return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                startX = lp.x
                startY = lp.y
                moved = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!moved && (abs(dx) > DRAG_SLOP_PX || abs(dy) > DRAG_SLOP_PX)) moved = true
                if (moved) {
                    val pos = clampToScreen(startX + dx.toInt(), startY + dy.toInt(), lp.width)
                    lp.x = pos.first
                    lp.y = pos.second
                    wm().updateViewLayout(v, lp)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (moved) SecondaryControlSettings.savePosition(this, lp.x, lp.y) else onTapped()
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return false
    }

    /** One tap starts tapping, the next tap stops it. */
    private fun onTapped() {
        val svc = TapnexAccessibilityService.instance
        if (svc == null) {
            toast("Accessibility is off. Turn on TAPNEX in Settings > Accessibility, then tap again.")
            updateColor()
            return
        }
        val running = svc.engineState == EngineState.RUNNING || svc.engineState == EngineState.PAUSED
        when {
            running && svc.sessionOwner == TapnexAccessibilityService.OWNER_SECOND -> {
                svc.stopEngine()
                startedHere = false
                toast("Tapping stopped.")
            }
            running -> toast("Tapping is already running from the main floating control. Stop it there.")
            else -> {
                svc.intervalOverrideMs = FloatingSettings.load(this).intervalMs
                val error = svc.startEngine(TapnexAccessibilityService.OWNER_SECOND)
                if (error != null) {
                    toast(error)
                } else {
                    startedHere = true
                    toast("Tapping started. Tap again to stop.")
                }
            }
        }
        updateColor()
    }

    private fun updateColor() {
        val svc = TapnexAccessibilityService.instance
        val hasPoints = PointStore.load().any { it.enabled }
        val color = when {
            svc == null -> COLOR_OFF
            svc.engineState == EngineState.RUNNING && svc.sessionOwner == TapnexAccessibilityService.OWNER_SECOND -> COLOR_ACTIVE
            !hasPoints -> COLOR_NO_POINTS
            else -> COLOR_READY
        }
        buttonBackground?.setColor(color)
        button?.contentDescription = when (color) {
            COLOR_ACTIVE -> "TAPNEX second button: tapping. Tap to stop."
            COLOR_READY -> "TAPNEX second button: ready. Tap to start tapping."
            COLOR_NO_POINTS -> "TAPNEX second button: add and enable a click point first."
            else -> "TAPNEX second button: accessibility is off."
        }
        button?.invalidate()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

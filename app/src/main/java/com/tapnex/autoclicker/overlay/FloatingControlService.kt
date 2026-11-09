package com.tapnex.autoclicker.overlay

import android.app.AlertDialog
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
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.tapnex.autoclicker.Permissions
import com.tapnex.autoclicker.R
import com.tapnex.autoclicker.accessibility.TapnexAccessibilityService
import com.tapnex.autoclicker.accessibility.TapnexAccessibilityService.EngineState
import com.tapnex.autoclicker.model.ClickPoint
import com.tapnex.autoclicker.model.PointStore
import com.tapnex.autoclicker.settings.FloatingSettings
import kotlin.math.abs

/** Premium animated TAPNEX floating control and transparent user-configured click-point overlays. */
class FloatingControlService : Service() {
    companion object {
        const val ACTION_STOP = "com.tapnex.autoclicker.action.STOP_FLOATING"
        private const val CHANNEL_ID = "tapnex_control"
        private const val NOTIFICATION_ID = 1001
        private const val DRAG_SLOP_PX = 12f
        private const val REFRESH_MS = 500L
        private const val MINIMIZED_SCALE = 0.58f
        private const val MENU_WIDTH_DP = 220
        private const val MENU_GAP_DP = 10
        private val COLOR_IDLE = Color.parseColor("#D91F2B")
        private val COLOR_RUNNING = Color.parseColor("#F5B700")
        private val COLOR_PAUSED = Color.parseColor("#FFFFFF")
    }

    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var bubble: ImageView? = null
    private var bubbleBackground: GradientDrawable? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var baseSizePx = 0
    private var minimized = false
    private var locked = false
    private var menuView: LinearLayout? = null
    private var menuParams: WindowManager.LayoutParams? = null
    private var menuShown = false
    private var menuState: TextView? = null
    private var startButton: Button? = null
    private var minimizeButton: Button? = null
    private var lockButton: Button? = null
    private val clickerViews = mutableMapOf<String, View>()
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var moved = false

    private val refresh = object : Runnable {
        override fun run() {
            if (!Permissions.canDrawOverlay(this@FloatingControlService)) { stopSelf(); return }
            applyLiveSettings()
            syncClickers()
            updateColor()
            updateMenuLabels()
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
        startAsForeground()
        if (!Permissions.canDrawOverlay(this)) { stopSelf(); return }
        locked = FloatingSettings.load(this).locked
        buildBubble()
        handler.post(refresh)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        TapnexAccessibilityService.instance?.let { svc ->
            if (svc.sessionOwner == TapnexAccessibilityService.OWNER_MAIN) svc.stopEngine()
            svc.intervalOverrideMs = null
        }
        hideMenu(false)
        clickerViews.values.forEach { runCatching { wm().removeView(it) } }
        clickerViews.clear()
        bubble?.let { runCatching { wm().removeView(it) } }
        bubble = null
        bubbleParams = null
        bubbleBackground = null
        super.onDestroy()
    }

    private fun wm(): WindowManager {
        windowManager?.let { return it }
        return (getSystemService(Context.WINDOW_SERVICE) as WindowManager).also { windowManager = it }
    }
    private fun density() = resources.displayMetrics.density
    private fun screenW() = resources.displayMetrics.widthPixels
    private fun screenH() = resources.displayMetrics.heightPixels

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "TAPNEX floating controls", NotificationManager.IMPORTANCE_LOW))
        val closeIntent = PendingIntent.getService(this, 0, Intent(this, FloatingControlService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TAPNEX controls are active")
            .setContentText("Floating TAPNEX controls are visible.")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Close", closeIntent)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION_ID, notification)
    }

    private fun clampToScreen(x: Int, y: Int, size: Int): Pair<Int, Int> {
        return Pair(x.coerceIn(0, (screenW() - size).coerceAtLeast(0)), y.coerceIn(0, (screenH() - size).coerceAtLeast(0)))
    }

    private fun buildBubble() {
        if (bubble != null) return
        val settings = FloatingSettings.load(this)
        baseSizePx = (settings.sizeDp * density()).toInt()
        val bg = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(COLOR_IDLE) }
        bubbleBackground = bg
        val view = ImageView(this).apply {
            setImageResource(R.drawable.floating_icon)
            setPadding((3 * density()).toInt(), (3 * density()).toInt(), (3 * density()).toInt(), (3 * density()).toInt())
            background = bg
            contentDescription = "TAPNEX floating menu"
            setOnTouchListener { v, event -> onBubbleTouch(v, event) }
        }
        bubble = view
        val pos = if (settings.hasPosition) clampToScreen(settings.x, settings.y, baseSizePx) else clampToScreen((24 * density()).toInt(), (300 * density()).toInt(), baseSizePx)
        bubbleParams = WindowManager.LayoutParams(baseSizePx, baseSizePx, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START; x = pos.first; y = pos.second
        }
        wm().addView(view, bubbleParams)
        view.scaleX = 0.75f; view.scaleY = 0.75f; view.alpha = 0f
        view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(260).start()
    }

    private fun onBubbleTouch(v: View, event: MotionEvent): Boolean {
        val lp = bubbleParams ?: return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> { downRawX = event.rawX; downRawY = event.rawY; startX = lp.x; startY = lp.y; moved = false; return true }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX; val dy = event.rawY - downRawY
                if (!moved && (abs(dx) > DRAG_SLOP_PX || abs(dy) > DRAG_SLOP_PX)) moved = true
                if (moved && !locked) { val pos = clampToScreen(startX + dx.toInt(), startY + dy.toInt(), lp.width); lp.x = pos.first; lp.y = pos.second; wm().updateViewLayout(v, lp); positionMenu() }
                return true
            }
            MotionEvent.ACTION_UP -> { if (moved) { if (!locked) FloatingSettings.savePosition(this, lp.x, lp.y) } else onIconTapped(); return true }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return false
    }

    private fun onIconTapped() {
        if (minimized) restoreSize() else if (menuShown) hideMenu(true) else showMenu()
    }

    private fun buildMenuView(): LinearLayout {
        val pad = (14 * density()).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply { cornerRadius = 22 * density(); setColor(Color.parseColor("#F21A2028")) }
        }
        menuState = TextView(this).apply { setTextColor(Color.WHITE); textSize = 14f; setPadding(6, 2, 6, 8) }
        container.addView(menuState)
        container.addView(menuButton("Add Clicker") { addClicker() })
        startButton = menuButton("Start Clicking") { toggleEngine() }.also { container.addView(it) }
        lockButton = menuButton("Lock Clicker") { toggleClickerLocks() }.also { container.addView(it) }
        container.addView(menuButton("Click Speed") { showSpeedDialog() })
        minimizeButton = menuButton("Minimize") { if (minimized) restoreSize() else minimizeSize() }.also { container.addView(it) }
        container.addView(menuButton("Close floating menu") { hideMenu(true) })
        return container
    }

    private fun menuButton(label: String, onClick: () -> Unit) = Button(this).apply { text = label; isAllCaps = false; setTextColor(Color.WHITE); setOnClickListener { onClick() } }

    private fun showMenu() {
        if (menuShown) return
        val view = menuView ?: buildMenuView().also { menuView = it }
        val width = (MENU_WIDTH_DP * density()).toInt()
        menuParams = WindowManager.LayoutParams(width, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }
        wm().addView(view, menuParams)
        menuShown = true
        view.alpha = 0f; view.scaleX = 0.86f; view.scaleY = 0.86f
        positionMenu()
        view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start()
        bubble?.animate()?.rotationBy(135f)?.setDuration(180)?.start()
        updateMenuLabels()
    }

    private fun hideMenu(animated: Boolean) {
        if (!menuShown) return
        val view = menuView ?: return
        menuShown = false
        val finish = { runCatching { wm().removeView(view) }; menuParams = null }
        if (animated) { view.animate().alpha(0f).scaleX(0.86f).scaleY(0.86f).setDuration(150).withEndAction(finish).start() }
        else finish()
        bubble?.animate()?.rotation(0f)?.setDuration(150)?.start()
    }

    private fun positionMenu() {
        if (!menuShown) return
        val view = menuView ?: return; val mLp = menuParams ?: return; val bLp = bubbleParams ?: return
        val gap = (MENU_GAP_DP * density()).toInt(); val menuW = mLp.width
        val right = bLp.x + bLp.width + gap
        mLp.x = (if (right + menuW <= screenW()) right else bLp.x - menuW - gap).coerceIn(0, (screenW() - menuW).coerceAtLeast(0))
        val h = if (view.height > 0) view.height else (420 * density()).toInt()
        mLp.y = bLp.y.coerceIn(0, (screenH() - h).coerceAtLeast(0))
        wm().updateViewLayout(view, mLp)
    }

    private fun updateMenuLabels() {
        val svc = TapnexAccessibilityService.instance
        menuState?.text = when (svc?.engineState) {
            EngineState.RUNNING -> "TAPNEX • Clicking"
            EngineState.PAUSED -> "TAPNEX • Paused"
            else -> "TAPNEX • Ready"
        }
        startButton?.text = if (svc?.engineState == EngineState.RUNNING) "Stop Clicking" else "Start Clicking"
        minimizeButton?.text = if (minimized) "Restore" else "Minimize"
        lockButton?.text = if (PointStore.load().all { it.locked }) "Unlock Clicker" else "Lock Clicker"
    }

    private fun toggleEngine() {
        val svc = TapnexAccessibilityService.instance ?: run { toast("Accessibility permission is required."); return }
        if (svc.engineState == EngineState.RUNNING || svc.engineState == EngineState.PAUSED) svc.stopEngine()
        else {
            svc.intervalOverrideMs = FloatingSettings.load(this).intervalMs
            svc.startEngine(TapnexAccessibilityService.OWNER_MAIN)?.let { toast(it) }
        }
        updateColor(); updateMenuLabels()
    }

    private fun addClicker() {
        val points = PointStore.load()
        val p = ClickPoint(label = "Clicker ${points.size + 1}", nx = 0.5f, ny = 0.5f)
        PointStore.save(points + p)
        syncClickers()
        toast("Clicker added. Drag the red circle to set its position.")
    }

    private fun toggleClickerLocks() {
        val points = PointStore.load()
        val lock = !points.all { it.locked }
        PointStore.save(points.map { it.copy(locked = lock) })
        syncClickers()
        updateMenuLabels()
        toast(if (lock) "Clickers locked." else "Clickers unlocked.")
    }

    private fun showSpeedDialog() {
        val settings = FloatingSettings.load(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 8, 32, 8) }
        val label = TextView(this).apply { text = "Speed: ${settings.speed}%"; setTextColor(Color.BLACK) }
        val seek = SeekBar(this).apply { max = 100; progress = settings.speed }
        root.addView(label); root.addView(seek)
        val dialog = AlertDialog.Builder(this).setTitle("Click Speed").setView(root).setPositiveButton("Done", null).create()
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) { FloatingSettings.saveSpeed(this@FloatingControlService, value); label.text = "Speed: $value%"; TapnexAccessibilityService.instance?.intervalOverrideMs = FloatingSettings.speedToIntervalMs(value) }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
    }

    private fun minimizeSize() { minimized = true; hideMenu(true); applyBubbleSize((baseSizePx * MINIMIZED_SCALE).toInt()) }
    private fun restoreSize() { minimized = false; applyBubbleSize(baseSizePx); bubble?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(160)?.start() }
    private fun applyBubbleSize(px: Int) { val view = bubble ?: return; val lp = bubbleParams ?: return; val pos = clampToScreen(lp.x, lp.y, px); lp.width = px; lp.height = px; lp.x = pos.first; lp.y = pos.second; wm().updateViewLayout(view, lp); positionMenu() }

    private fun applyLiveSettings() {
        val settings = FloatingSettings.load(this)
        TapnexAccessibilityService.instance?.intervalOverrideMs = settings.intervalMs
        val newBase = (settings.sizeDp * density()).toInt()
        if (bubble != null && newBase != baseSizePx) { baseSizePx = newBase; if (!minimized) applyBubbleSize(baseSizePx) }
    }

    private fun syncClickers() {
        val points = PointStore.load().filter { it.enabled }
        val ids = points.map { it.id }.toSet()
        clickerViews.keys.filter { it !in ids }.toList().forEach { id -> clickerViews.remove(id)?.let { runCatching { wm().removeView(it) } } }
        points.forEach { point -> if (!clickerViews.containsKey(point.id)) addClickerView(point) else updateClickerView(point) }
    }

    private fun addClickerView(point: ClickPoint) {
        val size = (point.sizeDp * density()).toInt()
        val bg = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.RED) }
        val view = View(this).apply {
            background = bg
            alpha = point.opacityPercent / 100f
            contentDescription = point.label
        }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var movedHere = false
        view.setOnTouchListener { v, event ->
            val current = PointStore.load().firstOrNull { it.id == point.id } ?: return@setOnTouchListener true
            val lp = v.layoutParams as WindowManager.LayoutParams
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; startX = lp.x; startY = lp.y; movedHere = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    if (!current.locked) { if (abs(dx) > DRAG_SLOP_PX || abs(dy) > DRAG_SLOP_PX) movedHere = true; if (movedHere) { val pos = clampToScreen(startX + dx.toInt(), startY + dy.toInt(), lp.width); lp.x = pos.first; lp.y = pos.second; wm().updateViewLayout(v, lp) } }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (movedHere && !current.locked) { val nx = (lp.x + lp.width / 2f) / screenW().coerceAtLeast(1); val ny = (lp.y + lp.height / 2f) / screenH().coerceAtLeast(1); PointStore.save(PointStore.load().map { if (it.id == current.id) it.copy(nx = nx.coerceIn(0f, 1f), ny = ny.coerceIn(0f, 1f)) else it }) }
                    true
                }
                else -> true
            }
        }
        val pos = clampToScreen((point.nx * screenW() - size / 2f).toInt(), (point.ny * screenH() - size / 2f).toInt(), size)
        val lp = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = pos.first; y = pos.second }
        clickerViews[point.id] = view
        wm().addView(view, lp)
    }

    private fun updateClickerView(point: ClickPoint) {
        val view = clickerViews[point.id] ?: return
        val lp = view.layoutParams as? WindowManager.LayoutParams ?: return
        val size = (point.sizeDp * density()).toInt()
        val pos = clampToScreen((point.nx * screenW() - size / 2f).toInt(), (point.ny * screenH() - size / 2f).toInt(), size)
        if (lp.width != size || lp.height != size || lp.x != pos.first || lp.y != pos.second) {
            lp.width = size; lp.height = size; lp.x = pos.first; lp.y = pos.second; wm().updateViewLayout(view, lp)
        }
        view.alpha = point.opacityPercent / 100f
        view.contentDescription = if (point.locked) "${point.label}, locked" else "${point.label}, draggable"
    }

    private fun updateColor() {
        val color = when (TapnexAccessibilityService.instance?.engineState) { EngineState.RUNNING -> COLOR_RUNNING; EngineState.PAUSED -> COLOR_PAUSED; else -> COLOR_IDLE }
        bubbleBackground?.setColor(color); bubble?.invalidate()
    }

    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
}

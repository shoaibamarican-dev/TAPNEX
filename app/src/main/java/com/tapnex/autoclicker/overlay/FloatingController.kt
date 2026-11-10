package com.tapnex.autoclicker.overlay

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.tapnex.autoclicker.R
import com.tapnex.autoclicker.accessibility.TapnexAccessibilityService
import com.tapnex.autoclicker.accessibility.TapnexAccessibilityService.EngineState
import com.tapnex.autoclicker.model.ClickPoint
import com.tapnex.autoclicker.model.PointStore
import com.tapnex.autoclicker.settings.FloatingSettings
import kotlin.math.abs
import kotlin.math.min

/**
 * TAPNEX floating UI: animated bubble, animated toggle menu and numbered click-point markers.
 *
 * It lives inside the accessibility service and uses TYPE_ACCESSIBILITY_OVERLAY windows. That type
 * needs no "display over other apps" permission, no foreground service and no MIUI pop-up permission,
 * so it appears reliably on top of games.
 */
class FloatingController(private val svc: TapnexAccessibilityService) {

    companion object {
        private const val DRAG_SLOP_PX = 12f
        private const val REFRESH_MS = 400L
        private const val MINIMIZED_SCALE = 0.58f
        private const val MENU_WIDTH_DP = 250
        private const val MENU_GAP_DP = 10
        private val COLOR_IDLE = Color.parseColor("#D91F2B")
        private val COLOR_RUNNING = Color.parseColor("#F5B700")
        private val COLOR_PAUSED = Color.parseColor("#FFFFFF")
        private val COLOR_MUTED = Color.parseColor("#8E9AA3")
        private val COLOR_CARD = Color.parseColor("#F2141B22")
        private val COLOR_BTN = Color.parseColor("#2A343C")
        private val COLOR_RED = Color.parseColor("#E53935")
    }

    private val handler = Handler(Looper.getMainLooper())
    private val d = svc.resources.displayMetrics.density
    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    private var destroyed = false

    // Bubble
    private var bubble: ImageView? = null
    private var bubbleBg: GradientDrawable? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var baseSizePx = 0
    private var minimized = false
    private val bubbleLocked: Boolean get() = FloatingSettings.load(svc).locked
    private var pulse: ObjectAnimator? = null
    private var pulsing = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var moved = false

    // Menu
    private var menuRoot: ScrollView? = null
    private var menuCard: LinearLayout? = null
    private var menuParams: WindowManager.LayoutParams? = null
    private var menuShown = false
    private var menuAttached = false
    private var statusText: TextView? = null
    private var statusDot: View? = null
    private var autoSwitch: ToggleSwitch? = null
    private var showSwitch: ToggleSwitch? = null
    private var lockSwitch: ToggleSwitch? = null
    private var holdButton: TextView? = null
    private var minimizeButton: TextView? = null
    private var speedLabel: TextView? = null
    private var speedBar: SeekBar? = null
    private var speedTracking = false
    private var holdStartedEngine = false

    // Click point markers
    private val markers = mutableMapOf<String, TextView>()
    private var draggingId: String? = null

    private var lastScreen = Pair(0, 0)

    private val refresh = object : Runnable {
        override fun run() {
            if (destroyed) return
            runCatching {
                checkScreen()
                applySpeed()
                applyLiveSize()
                syncMarkers()
                updateColor()
                syncMenu()
            }
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    // ------------------------------------------------------------------ lifecycle

    fun show() {
        runCatching {
            lastScreen = ScreenUtil.realSize(svc)
            buildBubble()
            handler.post(refresh)
        }.onFailure { toast("Could not show TAPNEX controls.") }
    }

    fun destroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        pulse?.cancel()
        pulse = null
        menuShown = false
        menuRoot?.let { v ->
            v.animate().cancel()
            if (menuAttached) runCatching { wm.removeView(v) }
        }
        menuAttached = false
        markers.values.forEach { runCatching { wm.removeView(it) } }
        markers.clear()
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        bubbleParams = null
        bubbleBg = null
    }

    // ------------------------------------------------------------------ helpers

    private fun dp(v: Int) = (v * d).toInt()
    private fun screenW() = ScreenUtil.realSize(svc).first
    private fun screenH() = ScreenUtil.realSize(svc).second
    private fun toast(msg: String) = Toast.makeText(svc, msg, Toast.LENGTH_SHORT).show()

    private fun overlayParams(w: Int, h: Int): WindowManager.LayoutParams {
        val lp = WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, baseFlags, PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        return lp
    }

    private fun clampToScreen(x: Int, y: Int, w: Int, h: Int): Pair<Int, Int> {
        return Pair(
            x.coerceIn(0, (screenW() - w).coerceAtLeast(0)),
            y.coerceIn(0, (screenH() - h).coerceAtLeast(0))
        )
    }

    private fun applySpeed() {
        val s = FloatingSettings.load(svc)
        // Until the user touches the speed slider, every point keeps its own interval.
        svc.intervalOverrideMs = if (s.speedCustom) s.intervalMs else null
    }

    private fun checkScreen() {
        val now = ScreenUtil.realSize(svc)
        if (now == lastScreen) return
        lastScreen = now
        val v = bubble
        val lp = bubbleParams
        if (v != null && lp != null) {
            val pos = clampToScreen(lp.x, lp.y, lp.width, lp.height)
            lp.x = pos.first
            lp.y = pos.second
            wm.updateViewLayout(v, lp)
        }
        positionMenu()
    }

    // ------------------------------------------------------------------ bubble

    private fun buildBubble() {
        if (bubble != null) return
        val settings = FloatingSettings.load(svc)
        baseSizePx = dp(settings.sizeDp)
        val bg = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(COLOR_IDLE) }
        bubbleBg = bg
        val view = ImageView(svc).apply {
            setImageResource(R.drawable.floating_icon)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = bg
            contentDescription = "TAPNEX floating menu"
            setOnTouchListener { v, event -> onBubbleTouch(v, event) }
        }
        bubble = view
        val pos = if (settings.hasPosition) {
            clampToScreen(settings.x, settings.y, baseSizePx, baseSizePx)
        } else {
            clampToScreen(dp(24), dp(120), baseSizePx, baseSizePx)
        }
        val lp = overlayParams(baseSizePx, baseSizePx).apply { x = pos.first; y = pos.second }
        bubbleParams = lp
        wm.addView(view, lp)
        view.scaleX = 0.6f; view.scaleY = 0.6f; view.alpha = 0f
        view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(320).start()
    }

    private fun onBubbleTouch(v: View, event: MotionEvent): Boolean {
        val lp = bubbleParams ?: return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX; downRawY = event.rawY
                startX = lp.x; startY = lp.y; moved = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!moved && (abs(dx) > DRAG_SLOP_PX || abs(dy) > DRAG_SLOP_PX)) moved = true
                if (moved && !bubbleLocked) {
                    val pos = clampToScreen(startX + dx.toInt(), startY + dy.toInt(), lp.width, lp.height)
                    lp.x = pos.first; lp.y = pos.second
                    wm.updateViewLayout(v, lp)
                    positionMenu()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (moved) {
                    if (!bubbleLocked) FloatingSettings.savePosition(svc, lp.x, lp.y)
                } else onBubbleTapped()
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return false
    }

    private fun onBubbleTapped() {
        when {
            minimized -> restoreSize()
            svc.engineState == EngineState.RUNNING && !holdStartedEngine -> {
                // Quick stop: one tap on the pulsing bubble ends auto clicking.
                svc.stopEngine()
                syncMarkers(); updateColor(); syncMenu()
                toast("Auto click stopped.")
            }
            menuShown -> hideMenu(true)
            else -> showMenu()
        }
    }

    private fun updateColor() {
        val state = svc.engineState
        val color = when (state) {
            EngineState.RUNNING -> COLOR_RUNNING
            EngineState.PAUSED -> COLOR_PAUSED
            else -> COLOR_IDLE
        }
        bubbleBg?.setColor(color)
        (statusDot?.background as? GradientDrawable)?.setColor(color)
        bubble?.invalidate()
        setPulse(state == EngineState.RUNNING)
    }

    private fun setPulse(on: Boolean) {
        val b = bubble ?: return
        if (on == pulsing) return
        pulsing = on
        if (on) {
            pulse = ObjectAnimator.ofPropertyValuesHolder(
                b,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.12f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.12f)
            ).apply {
                duration = 600
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        } else {
            pulse?.cancel()
            pulse = null
            b.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
        }
    }

    private fun minimizeSize() { minimized = true; hideMenu(true); applyBubbleSize((baseSizePx * MINIMIZED_SCALE).toInt()) }
    private fun restoreSize() { minimized = false; applyBubbleSize(baseSizePx) }

    private fun applyBubbleSize(px: Int) {
        val view = bubble ?: return
        val lp = bubbleParams ?: return
        val pos = clampToScreen(lp.x, lp.y, px, px)
        lp.width = px; lp.height = px; lp.x = pos.first; lp.y = pos.second
        wm.updateViewLayout(view, lp)
        positionMenu()
    }

    private fun applyLiveSize() {
        val newBase = dp(FloatingSettings.load(svc).sizeDp)
        if (bubble != null && newBase != baseSizePx) {
            baseSizePx = newBase
            if (!minimized) applyBubbleSize(baseSizePx)
        }
    }

    // ------------------------------------------------------------------ menu

    private fun pressFx(v: View) {
        v.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.95f).scaleY(0.95f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            false // let the click listener still run
        }
    }

    private fun pill(label: String, fill: Int): TextView = TextView(svc).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(fill) }
        minHeight = dp(40)
        setPadding(dp(10), 0, dp(10), 0)
        isClickable = true
        pressFx(this)
    }

    private fun toggleRow(title: String, sub: String, onChange: (Boolean) -> Unit): Pair<View, ToggleSwitch> {
        val row = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        val texts = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(svc).apply {
            text = title; setTextColor(Color.WHITE); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
        })
        texts.addView(TextView(svc).apply { text = sub; setTextColor(COLOR_MUTED); textSize = 11f })
        val sw = ToggleSwitch(svc).apply { onChanged = onChange }
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(sw)
        row.setOnClickListener { sw.performClick() }
        return Pair(row, sw)
    }

    private fun divider() = View(svc).apply {
        setBackgroundColor(Color.parseColor("#22FFFFFF"))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
            topMargin = dp(2); bottomMargin = dp(2)
        }
    }

    private fun fullWidth(topDp: Int = 8) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(topDp) }

    private fun buildMenu(): ScrollView {
        val pad = dp(14)
        val card = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(COLOR_CARD)
                setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
        }

        // Header: status dot, title, state text
        val header = LinearLayout(svc).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val dot = View(svc).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(COLOR_IDLE) }
        }
        statusDot = dot
        header.addView(dot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(8) })
        header.addView(TextView(svc).apply {
            text = "TAPNEX"; setTextColor(COLOR_RED); textSize = 17f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        statusText = TextView(svc).apply { text = "Ready"; setTextColor(COLOR_MUTED); textSize = 12f }
        header.addView(statusText)
        card.addView(header)
        card.addView(divider(), fullWidth(6))

        // Toggles
        val auto = toggleRow("Auto Click", "Taps your points in order") { on -> onAutoToggled(on) }
        autoSwitch = auto.second
        card.addView(auto.first)
        val show = toggleRow("Show Points", "Numbered circles on screen") { on ->
            FloatingSettings.saveShowPoints(svc, on)
            syncMarkers()
        }
        showSwitch = show.second
        card.addView(show.first)
        val lock = toggleRow("Lock Points", "Taps pass through, no dragging") { on -> setAllLocked(on) }
        lockSwitch = lock.second
        card.addView(lock.first)
        card.addView(divider(), fullWidth(4))

        // Point buttons
        val pointRow = LinearLayout(svc).apply { orientation = LinearLayout.HORIZONTAL }
        val add = pill("+ Add Point", COLOR_RED).also { it.setOnClickListener { addPoint() } }
        val remove = pill("− Remove", COLOR_BTN).also { it.setOnClickListener { removeLastPoint() } }
        pointRow.addView(add, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f).apply { rightMargin = dp(6) })
        pointRow.addView(remove, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(pointRow, fullWidth(8))

        // Hold to click
        holdButton = pill("Hold to Click", COLOR_BTN).also { card.addView(it, fullWidth(8)); setupHoldButton(it) }

        // Speed
        speedLabel = TextView(svc).apply {
            setTextColor(Color.WHITE); textSize = 13f; typeface = Typeface.DEFAULT_BOLD
            setOnClickListener {
                FloatingSettings.clearSpeedCustom(svc)
                applySpeed(); syncMenu()
                toast("Speed: using each point's own timing.")
            }
        }
        card.addView(speedLabel, fullWidth(10))
        speedBar = SeekBar(svc).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(COLOR_RED)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    FloatingSettings.saveSpeed(svc, value)
                    applySpeed()
                    speedLabel?.text = speedText(FloatingSettings.load(svc))
                }
                override fun onStartTrackingTouch(bar: SeekBar?) { speedTracking = true }
                override fun onStopTrackingTouch(bar: SeekBar?) { speedTracking = false }
            })
        }
        card.addView(speedBar, fullWidth(2))
        card.addView(divider(), fullWidth(6))

        // Bottom row
        val bottom = LinearLayout(svc).apply { orientation = LinearLayout.HORIZONTAL }
        minimizeButton = pill("Minimize", COLOR_BTN).also { it.setOnClickListener { if (minimized) restoreSize() else minimizeSize() } }
        val close = pill("Close menu", COLOR_BTN).also { it.setOnClickListener { hideMenu(true) } }
        bottom.addView(minimizeButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
        bottom.addView(close, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(bottom, fullWidth(8))

        menuCard = card
        return ScrollView(svc).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(card)
        }
    }

    private fun setupHoldButton(button: TextView) {
        button.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    view.animate().scaleX(0.95f).scaleY(0.95f).setDuration(80).start()
                    holdStartedEngine = svc.engineState == EngineState.IDLE
                    if (holdStartedEngine) {
                        applySpeed()
                        val error = svc.startEngine(TapnexAccessibilityService.OWNER_MAIN)
                        if (error != null) {
                            holdStartedEngine = false
                            toast(error)
                        } else {
                            syncMarkers()
                        }
                    }
                    updateColor(); syncMenu()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    if (holdStartedEngine) svc.stopEngine()
                    holdStartedEngine = false
                    syncMarkers(); updateColor(); syncMenu()
                    view.performClick()
                    true
                }
                else -> true
            }
        }
    }

    private fun speedText(s: FloatingSettings.Snapshot): String =
        if (s.speedCustom) "Speed: ${s.speed}%  (tap here = auto)" else "Speed: Auto (point timing)"

    private fun syncMenu() {
        val s = FloatingSettings.load(svc)
        val state = svc.engineState
        statusText?.text = when (state) {
            EngineState.RUNNING -> "Clicking"
            EngineState.PAUSED -> "Paused"
            else -> "Ready"
        }
        autoSwitch?.setChecked(state == EngineState.RUNNING && !holdStartedEngine, true)
        showSwitch?.setChecked(s.showPoints, true)
        val pts = PointStore.load()
        lockSwitch?.setChecked(pts.isNotEmpty() && pts.all { it.locked }, true)
        holdButton?.text = if (holdStartedEngine && state == EngineState.RUNNING) "Release to Stop" else "Hold to Click"
        minimizeButton?.text = if (minimized) "Restore" else "Minimize"
        if (!speedTracking) {
            speedLabel?.text = speedText(s)
            val target = if (s.speedCustom) s.speed else 50
            if (speedBar?.progress != target) speedBar?.progress = target
        }
    }

    private fun computeMenuLayout(lp: WindowManager.LayoutParams) {
        val card = menuCard ?: return
        val bLp = bubbleParams ?: return
        val gap = dp(MENU_GAP_DP)
        val sw = screenW()
        val sh = screenH()
        val w = min(dp(MENU_WIDTH_DP), sw - 2 * gap)
        card.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val h = min(card.measuredHeight, sh - 2 * dp(8))
        val right = bLp.x + bLp.width + gap
        lp.width = w
        lp.height = h
        lp.x = (if (right + w <= sw) right else bLp.x - w - gap).coerceIn(0, (sw - w).coerceAtLeast(0))
        lp.y = bLp.y.coerceIn(0, (sh - h).coerceAtLeast(0))
    }

    private fun showMenu() {
        if (menuShown || destroyed) return
        val view = menuRoot ?: buildMenu().also { menuRoot = it }
        view.animate().cancel()
        val lp = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
        syncMenu()
        computeMenuLayout(lp)
        if (menuAttached) wm.updateViewLayout(view, lp) else { wm.addView(view, lp); menuAttached = true }
        menuParams = lp
        menuShown = true
        view.alpha = 0f; view.scaleX = 0.88f; view.scaleY = 0.88f
        view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).start()
        bubble?.animate()?.rotationBy(135f)?.setDuration(200)?.start()
        // Rows slide in one after another.
        menuCard?.let { card ->
            for (i in 0 until card.childCount) {
                val child = card.getChildAt(i)
                child.alpha = 0f
                child.translationY = dp(10).toFloat()
                child.animate().alpha(1f).translationY(0f).setStartDelay(40L * i).setDuration(220).start()
            }
        }
    }

    private fun hideMenu(animated: Boolean) {
        if (!menuShown) return
        val view = menuRoot ?: return
        menuShown = false
        val finish = {
            if (!menuShown && menuAttached) {
                runCatching { wm.removeView(view) }
                menuAttached = false
            }
        }
        if (animated) view.animate().alpha(0f).scaleX(0.88f).scaleY(0.88f).setDuration(150).withEndAction(finish).start()
        else finish()
        bubble?.animate()?.rotation(0f)?.setDuration(150)?.start()
    }

    private fun positionMenu() {
        if (!menuShown) return
        val view = menuRoot ?: return
        val lp = menuParams ?: return
        computeMenuLayout(lp)
        runCatching { wm.updateViewLayout(view, lp) }
    }

    // ------------------------------------------------------------------ actions

    private fun onAutoToggled(on: Boolean) {
        if (on) {
            applySpeed()
            val error = svc.startEngine(TapnexAccessibilityService.OWNER_MAIN)
            if (error != null) {
                toast(error)
                autoSwitch?.setChecked(false, true)
            } else {
                syncMarkers() // markers become tap-through before the first tap
                hideMenu(true) // keep the menu from sitting on top of a tap point
                toast("Auto click started. Tap the pulsing bubble to stop.")
            }
        } else {
            svc.stopEngine()
            syncMarkers()
        }
        updateColor()
    }

    private fun addPoint() {
        val pts = PointStore.load()
        val n = pts.size
        val off = (n % 6) * 0.06f
        val p = ClickPoint(label = "Point ${n + 1}", nx = (0.5f + off).coerceIn(0f, 1f), ny = (0.5f + off).coerceIn(0f, 1f))
        PointStore.save(pts + p)
        if (!FloatingSettings.load(svc).showPoints) {
            FloatingSettings.saveShowPoints(svc, true)
        }
        syncMarkers()
        syncMenu()
        hideMenu(true)
        toast("Point ${n + 1} added. Drag the red circle onto the target.")
    }

    private fun removeLastPoint() {
        val pts = PointStore.load()
        if (pts.isEmpty()) { toast("No points to remove."); return }
        PointStore.save(pts.dropLast(1))
        syncMarkers()
        toast("Point removed.")
    }

    private fun setAllLocked(lock: Boolean) {
        PointStore.save(PointStore.load().map { it.copy(locked = lock) })
        syncMarkers()
        toast(if (lock) "Points locked." else "Points unlocked.")
    }

    // ------------------------------------------------------------------ click point markers

    private fun syncMarkers() {
        val settings = FloatingSettings.load(svc)
        val all = PointStore.load().filter { it.enabled }
        val visible = if (settings.showPoints) all else emptyList()
        val ids = visible.map { it.id }.toSet()
        markers.keys.filter { it !in ids }.toList().forEach { id ->
            markers.remove(id)?.let { runCatching { wm.removeView(it) } }
        }
        val running = svc.engineState != EngineState.IDLE
        visible.forEachIndexed { index, point ->
            val view = markers[point.id]
            if (view == null) addMarker(point, index, running) else updateMarker(view, point, index, running)
        }
    }

    private fun addMarker(point: ClickPoint, index: Int, running: Boolean) {
        val size = dp(point.sizeDp)
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.RED)
            setStroke(dp(2), Color.WHITE)
        }
        val view = TextView(svc).apply {
            text = (index + 1).toString()
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = bg
            alpha = point.opacityPercent / 100f
            contentDescription = point.label
        }
        var downX = 0f; var downY = 0f; var sx = 0; var sy = 0; var movedHere = false
        view.setOnTouchListener { v, event ->
            val current = PointStore.load().firstOrNull { it.id == point.id } ?: return@setOnTouchListener true
            val lp = v.layoutParams as WindowManager.LayoutParams
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; sx = lp.x; sy = lp.y; movedHere = false
                    draggingId = point.id
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!current.locked) {
                        if (abs(dx) > DRAG_SLOP_PX || abs(dy) > DRAG_SLOP_PX) movedHere = true
                        if (movedHere) {
                            val pos = clampToScreen(sx + dx.toInt(), sy + dy.toInt(), lp.width, lp.height)
                            lp.x = pos.first; lp.y = pos.second
                            wm.updateViewLayout(v, lp)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    draggingId = null
                    if (movedHere && !current.locked) {
                        val nx = ((lp.x + lp.width / 2f) / screenW()).coerceIn(0f, 1f)
                        val ny = ((lp.y + lp.height / 2f) / screenH()).coerceIn(0f, 1f)
                        PointStore.save(PointStore.load().map { if (it.id == current.id) it.copy(nx = nx, ny = ny) else it })
                    }
                    true
                }
                else -> true
            }
        }
        val pos = clampToScreen(
            (point.nx * screenW() - size / 2f).toInt(), (point.ny * screenH() - size / 2f).toInt(), size, size
        )
        val lp = overlayParams(size, size).apply {
            x = pos.first; y = pos.second
            if (!touchableFor(point, running)) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        markers[point.id] = view
        wm.addView(view, lp)
    }

    /** Markers must not catch touches while tapping (they would swallow the injected taps) or when locked. */
    private fun touchableFor(point: ClickPoint, running: Boolean) = !running && !point.locked

    private fun updateMarker(view: TextView, point: ClickPoint, index: Int, running: Boolean) {
        val lp = view.layoutParams as? WindowManager.LayoutParams ?: return
        var changed = false
        val size = dp(point.sizeDp)
        if (draggingId != point.id) {
            val pos = clampToScreen(
                (point.nx * screenW() - size / 2f).toInt(), (point.ny * screenH() - size / 2f).toInt(), size, size
            )
            if (lp.width != size || lp.height != size || lp.x != pos.first || lp.y != pos.second) {
                lp.width = size; lp.height = size; lp.x = pos.first; lp.y = pos.second
                changed = true
            }
        }
        val wantNotTouchable = !touchableFor(point, running)
        val isNotTouchable = (lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0
        if (wantNotTouchable != isNotTouchable) {
            lp.flags = if (wantNotTouchable) lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            else lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            changed = true
        }
        if (changed) wm.updateViewLayout(view, lp)
        view.alpha = point.opacityPercent / 100f
        val label = (index + 1).toString()
        if (view.text.toString() != label) view.text = label
        view.contentDescription = if (point.locked) "${point.label}, locked" else "${point.label}, draggable"
    }
}

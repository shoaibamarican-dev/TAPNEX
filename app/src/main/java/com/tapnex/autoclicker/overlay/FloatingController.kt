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
import android.view.animation.OvershootInterpolator
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
 * Drawn with TYPE_ACCESSIBILITY_OVERLAY, so it needs no extra overlay permission and stays on top of games.
 */
class FloatingController(private val svc: TapnexAccessibilityService) {

    companion object {
        private const val DRAG_SLOP_PX = 12f
        private const val REFRESH_MS = 250L
        private const val MINIMIZED_SCALE = 0.6f
        private const val MENU_WIDTH_DP = 260
        private const val MENU_GAP_DP = 10
        private const val COLOR_IDLE = "#D91F2B"
        private const val COLOR_RUNNING = "#F5B700"
        private const val COLOR_PAUSED = "#FFFFFF"
        private const val COLOR_MUTED = "#8E9AA3"
        private const val COLOR_BTN = "#2A343C"
        private const val COLOR_RED = "#E53935"
        private const val COLOR_CARD = "#F2141B22"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val d = svc.resources.displayMetrics.density
    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    private var destroyed = false
    private var screen = Pair(0, 0)

    // Bubble
    private var bubble: ImageView? = null
    private var bubbleBg: GradientDrawable? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var baseSize = 0
    private var minimized = false
    private var pulse: ObjectAnimator? = null
    private var pulsing = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var moved = false

    // Menu
    private var menuView: ScrollView? = null
    private var menuCard: LinearLayout? = null
    private var menuLp: WindowManager.LayoutParams? = null
    private var menuOpen = false
    private var menuAttached = false
    private var statusText: TextView? = null
    private var statusDot: View? = null
    private var holdSwitch: ToggleSwitch? = null
    private var showSwitch: ToggleSwitch? = null
    private var lockSwitch: ToggleSwitch? = null
    private var holdPill: TextView? = null
    private var minimizeBtn: TextView? = null
    private var speedLabel: TextView? = null
    private var speedBar: SeekBar? = null
    private var speedTracking = false
    private var holding = false

    // Click point markers
    private val markers = mutableMapOf<String, TextView>()
    private var draggingId: String? = null

    private val refresh = object : Runnable {
        override fun run() {
            if (destroyed) return
            runCatching {
                checkScreen()
                pushSpeed()
                applyLiveSize()
                syncMarkers()
                syncUi()
            }
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    // ------------------------------------------------------------ lifecycle

    fun show() {
        runCatching {
            screen = ScreenUtil.realSize(svc)
            buildBubble()
            handler.post(refresh)
        }.onFailure { toast("Could not show TAPNEX controls.") }
    }

    fun destroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        pulse?.cancel()
        pulse = null
        menuView?.let { v ->
            v.animate().cancel()
            if (menuAttached) runCatching { wm.removeView(v) }
        }
        menuAttached = false
        menuOpen = false
        markers.values.forEach { runCatching { wm.removeView(it) } }
        markers.clear()
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        bubbleLp = null
    }

    // ------------------------------------------------------------ helpers

    private fun dp(v: Int) = (v * d).toInt()
    private fun toast(msg: String) {
        Toast.makeText(svc, msg, Toast.LENGTH_SHORT).show()
    }
    private fun engineRunning() = svc.engineState != EngineState.IDLE

    private fun clamp(x: Int, y: Int, w: Int, h: Int): Pair<Int, Int> = Pair(
        x.coerceIn(0, (screen.first - w).coerceAtLeast(0)),
        y.coerceIn(0, (screen.second - h).coerceAtLeast(0))
    )

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

    private fun pushSpeed() {
        svc.intervalOverrideMs = FloatingSettings.speedToIntervalMs(FloatingSettings.load(svc).speed)
    }

    private fun checkScreen() {
        val now = ScreenUtil.realSize(svc)
        if (now == screen) return
        screen = now
        val lp = bubbleLp
        val v = bubble
        if (lp != null && v != null) {
            val p = clamp(lp.x, lp.y, lp.width, lp.height)
            lp.x = p.first
            lp.y = p.second
            wm.updateViewLayout(v, lp)
        }
        positionMenu()
    }

    // ------------------------------------------------------------ bubble

    private fun buildBubble() {
        if (bubble != null) return
        val s = FloatingSettings.load(svc)
        baseSize = dp(s.sizeDp)
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(COLOR_IDLE))
        }
        bubbleBg = bg
        val view = ImageView(svc).apply {
            setImageResource(R.drawable.floating_icon)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = bg
            contentDescription = "TAPNEX floating menu"
            setOnTouchListener { v, e -> onBubbleTouch(v, e) }
        }
        bubble = view
        val pos = if (s.hasPosition) {
            clamp(s.x, s.y, baseSize, baseSize)
        } else {
            clamp(dp(24), dp(120), baseSize, baseSize)
        }
        val lp = overlayParams(baseSize, baseSize)
        lp.x = pos.first
        lp.y = pos.second
        bubbleLp = lp
        wm.addView(view, lp)
        view.scaleX = 0.5f
        view.scaleY = 0.5f
        view.alpha = 0f
        view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(350)
            .setInterpolator(OvershootInterpolator()).start()
    }

    private fun onBubbleTouch(v: View, e: MotionEvent): Boolean {
        val lp = bubbleLp ?: return false
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = e.rawX
                downRawY = e.rawY
                startX = lp.x
                startY = lp.y
                moved = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downRawX
                val dy = e.rawY - downRawY
                if (!moved && (abs(dx) > DRAG_SLOP_PX || abs(dy) > DRAG_SLOP_PX)) moved = true
                if (moved) {
                    val p = clamp(startX + dx.toInt(), startY + dy.toInt(), lp.width, lp.height)
                    lp.x = p.first
                    lp.y = p.second
                    wm.updateViewLayout(v, lp)
                    positionMenu()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (moved) FloatingSettings.savePosition(svc, lp.x, lp.y) else onBubbleTapped()
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return false
    }

    private fun onBubbleTapped() {
        when {
            minimized -> setMinimized(false)
            engineRunning() -> {
                svc.stopEngine()
                holding = false
                syncUi()
                toast("Clicking stopped.")
            }
            menuOpen -> hideMenu(true)
            else -> showMenu()
        }
    }

    private fun setPulse(on: Boolean) {
        if (on == pulsing) return
        val b = bubble ?: return
        pulsing = on
        if (on) {
            pulse = ObjectAnimator.ofPropertyValuesHolder(
                b,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.12f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.12f)
            ).apply {
                duration = 500
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        } else {
            pulse?.cancel()
            pulse = null
            b.scaleX = 1f
            b.scaleY = 1f
        }
    }

    private fun setMinimized(m: Boolean) {
        minimized = m
        if (m) hideMenu(true)
        applyBubbleSize(if (m) (baseSize * MINIMIZED_SCALE).toInt() else baseSize)
        syncUi()
    }

    private fun applyBubbleSize(px: Int) {
        val view = bubble ?: return
        val lp = bubbleLp ?: return
        val p = clamp(lp.x, lp.y, px, px)
        lp.width = px
        lp.height = px
        lp.x = p.first
        lp.y = p.second
        wm.updateViewLayout(view, lp)
        positionMenu()
    }

    private fun applyLiveSize() {
        val want = dp(FloatingSettings.load(svc).sizeDp)
        if (bubble != null && want != baseSize) {
            baseSize = want
            if (!minimized) applyBubbleSize(baseSize)
        }
    }

    // ------------------------------------------------------------ menu building

    private fun divider() = View(svc).apply {
        setBackgroundColor(Color.parseColor("#22FFFFFF"))
    }

    private fun fullWidth(topDp: Int = 8) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(topDp) }

    private fun pill(label: String, fill: String): TextView = TextView(svc).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(Color.parseColor(fill))
        }
        minHeight = dp(40)
        setPadding(dp(10), 0, dp(10), 0)
        isClickable = true
        setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            false
        }
    }

    private fun toggleRow(title: String, sub: String, onChange: (Boolean) -> Unit): Pair<View, ToggleSwitch> {
        val row = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        val texts = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(svc).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
        })
        texts.addView(TextView(svc).apply {
            text = sub
            setTextColor(Color.parseColor(COLOR_MUTED))
            textSize = 11f
        })
        val sw = ToggleSwitch(svc).apply { onChanged = onChange }
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(sw)
        row.setOnClickListener { sw.performClick() }
        return Pair(row, sw)
    }

    private fun buildMenu(): ScrollView {
        val pad = dp(14)
        val card = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(Color.parseColor(COLOR_CARD))
                setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
        }

        // Header
        val header = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusDot = View(svc).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(COLOR_IDLE))
            }
        }
        header.addView(statusDot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(8) })
        header.addView(
            TextView(svc).apply {
                text = "TAPNEX"
                setTextColor(Color.parseColor(COLOR_RED))
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        statusText = TextView(svc).apply {
            text = "Ready"
            setTextColor(Color.parseColor(COLOR_MUTED))
            textSize = 12f
        }
        header.addView(statusText)
        card.addView(header)
        card.addView(divider(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { topMargin = dp(6) })

        // Toggles
        val hold = toggleRow("Hold to Click", "Turn on, then press & hold the button") { on ->
            FloatingSettings.saveHold(svc, on)
            if (!on && engineRunning()) svc.stopEngine()
            syncUi()
        }
        holdSwitch = hold.second
        card.addView(hold.first)

        val show = toggleRow("Show Points", "Numbered circles on screen") { on ->
            FloatingSettings.saveShowPoints(svc, on)
            syncMarkers()
            syncUi()
        }
        showSwitch = show.second
        card.addView(show.first)

        val lock = toggleRow("Lock Points", "Taps pass through, no dragging") { on ->
            val pts = PointStore.load()
            if (pts.isEmpty()) {
                toast("Add a point first.")
            } else {
                PointStore.save(pts.map { it.copy(locked = on) })
                syncMarkers()
            }
            syncUi()
        }
        lockSwitch = lock.second
        card.addView(lock.first)

        // Hold button
        holdPill = pill("HOLD TO CLICK", COLOR_BTN).also { p ->
            p.setOnTouchListener { v, e -> onHoldTouch(v, e) }
            card.addView(p, fullWidth(8))
        }

        // Points
        val pointRow = LinearLayout(svc).apply { orientation = LinearLayout.HORIZONTAL }
        pointRow.addView(
            pill("+ Add Point", COLOR_RED).also { p -> p.setOnClickListener { addPoint() } },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f).apply { rightMargin = dp(6) }
        )
        pointRow.addView(
            pill("− Remove", COLOR_BTN).also { p -> p.setOnClickListener { removeLastPoint() } },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        card.addView(pointRow, fullWidth(8))

        // Speed
        speedLabel = TextView(svc).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
        }
        card.addView(speedLabel, fullWidth(10))
        speedBar = SeekBar(svc).apply {
            max = FloatingSettings.MAX_SPEED
            progressTintList = ColorStateList.valueOf(Color.parseColor(COLOR_RED))
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    FloatingSettings.saveSpeed(svc, value.coerceAtLeast(FloatingSettings.MIN_SPEED))
                    syncSpeedText()
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {
                    speedTracking = true
                }
                override fun onStopTrackingTouch(bar: SeekBar?) {
                    speedTracking = false
                }
            })
        }
        card.addView(speedBar, fullWidth(2))

        // Bottom row
        card.addView(divider(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { topMargin = dp(6) })
        val bottom = LinearLayout(svc).apply { orientation = LinearLayout.HORIZONTAL }
        minimizeBtn = pill("Minimize", COLOR_BTN).also { p -> p.setOnClickListener { setMinimized(!minimized) } }
        bottom.addView(minimizeBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
        bottom.addView(
            pill("Close menu", COLOR_BTN).also { p -> p.setOnClickListener { hideMenu(true) } },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        card.addView(bottom, fullWidth(8))

        menuCard = card
        return ScrollView(svc).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(card)
        }
    }

    /** Press and hold to click: clicking runs while the finger is down and stops on release. */
    private fun onHoldTouch(v: View, e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                if (!FloatingSettings.load(svc).holdEnabled) {
                    toast("Turn on Hold to Click first.")
                    return true
                }
                val err = svc.startEngine(TapnexAccessibilityService.OWNER_MAIN)
                if (err != null) {
                    toast(err)
                    return true
                }
                holding = true
                v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(80).start()
                syncUi()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                if (holding) {
                    svc.stopEngine()
                    holding = false
                }
                syncUi()
                return true
            }
        }
        return true
    }

    private fun syncSpeedText() {
        val s = FloatingSettings.load(svc)
        speedLabel?.text = "Speed: ${s.speed} / ${FloatingSettings.MAX_SPEED}  •  ${s.intervalMs} ms"
    }

    /** Pushes stored settings and engine state into the visible controls. Safe to call often. */
    private fun syncUi() {
        val s = FloatingSettings.load(svc)
        val state = svc.engineState
        val colorName = when (state) {
            EngineState.RUNNING -> COLOR_RUNNING
            EngineState.PAUSED -> COLOR_PAUSED
            else -> COLOR_IDLE
        }
        val colorInt = Color.parseColor(colorName)
        bubbleBg?.setColor(colorInt)
        (statusDot?.background as? GradientDrawable)?.setColor(colorInt)
        statusText?.text = when (state) {
            EngineState.RUNNING -> "Clicking"
            EngineState.PAUSED -> "Paused"
            else -> "Ready"
        }
        setPulse(state == EngineState.RUNNING)

        val pts = PointStore.load()
        holdSwitch?.setChecked(s.holdEnabled, true)
        showSwitch?.setChecked(s.showPoints, true)
        lockSwitch?.setChecked(pts.isNotEmpty() && pts.all { it.locked }, true)
        holdPill?.let {
            it.alpha = if (s.holdEnabled) 1f else 0.4f
            it.text = if (holding) "RELEASE TO STOP" else "HOLD TO CLICK"
        }
        minimizeBtn?.text = if (minimized) "Restore" else "Minimize"
        if (!speedTracking) speedBar?.progress = s.speed
        syncSpeedText()
    }

    // ------------------------------------------------------------ menu show / hide

    private fun computeMenuLayout(lp: WindowManager.LayoutParams) {
        val card = menuCard ?: return
        val b = bubbleLp ?: return
        val gap = dp(MENU_GAP_DP)
        val sw = screen.first
        val sh = screen.second
        val w = min(dp(MENU_WIDTH_DP), sw - 2 * gap).coerceAtLeast(dp(200))
        card.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val h = min(card.measuredHeight, sh - dp(16)).coerceAtLeast(dp(120))
        val right = b.x + b.width + gap
        lp.width = w
        lp.height = h
        lp.x = (if (right + w <= sw) right else b.x - w - gap).coerceIn(0, (sw - w).coerceAtLeast(0))
        lp.y = b.y.coerceIn(0, (sh - h).coerceAtLeast(0))
    }

    private fun showMenu() {
        if (menuOpen || destroyed) return
        val view = menuView ?: buildMenu().also { menuView = it }
        view.animate().cancel()
        syncUi()
        val lp = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
        computeMenuLayout(lp)
        if (menuAttached) {
            wm.updateViewLayout(view, lp)
        } else {
            wm.addView(view, lp)
            menuAttached = true
        }
        menuLp = lp
        menuOpen = true
        view.alpha = 0f
        view.scaleX = 0.85f
        view.scaleY = 0.85f
        view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220)
            .setInterpolator(OvershootInterpolator(0.8f)).start()
        bubble?.animate()?.rotation(135f)?.setDuration(220)?.start()
        // Each row fades and slides in one after another.
        menuCard?.let { card ->
            for (i in 0 until card.childCount) {
                val c = card.getChildAt(i)
                c.alpha = 0f
                c.translationY = dp(8).toFloat()
                c.animate().alpha(1f).translationY(0f).setStartDelay(30L * i).setDuration(200).start()
            }
        }
    }

    private fun hideMenu(animated: Boolean) {
        if (!menuOpen) return
        val view = menuView ?: return
        menuOpen = false
        val finish = Runnable {
            if (!menuOpen && menuAttached) {
                runCatching { wm.removeView(view) }
                menuAttached = false
            }
        }
        if (animated) {
            view.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).setDuration(160)
                .withEndAction(finish).start()
        } else {
            finish.run()
        }
        bubble?.animate()?.rotation(0f)?.setDuration(160)?.start()
    }

    private fun positionMenu() {
        if (!menuOpen) return
        val view = menuView ?: return
        val lp = menuLp ?: return
        computeMenuLayout(lp)
        runCatching { wm.updateViewLayout(view, lp) }
    }

    // ------------------------------------------------------------ point actions

    private fun addPoint() {
        val pts = PointStore.load()
        val n = pts.size + 1
        val off = ((n - 1) % 6) * 0.06f
        PointStore.save(pts + ClickPoint(label = "Point $n", nx = 0.5f + off, ny = 0.5f + off))
        if (!FloatingSettings.load(svc).showPoints) FloatingSettings.saveShowPoints(svc, true)
        syncMarkers()
        hideMenu(true)
        toast("Point $n added. Drag the circle onto the target.")
    }

    private fun removeLastPoint() {
        val pts = PointStore.load()
        if (pts.isEmpty()) {
            toast("No points to remove.")
            return
        }
        PointStore.save(pts.dropLast(1))
        syncMarkers()
        syncUi()
    }

    // ------------------------------------------------------------ click point markers

    private fun touchableFor(point: ClickPoint, running: Boolean) = !running && !point.locked

    private fun syncMarkers() {
        val s = FloatingSettings.load(svc)
        val all = PointStore.load().filter { it.enabled }
        val visible = if (s.showPoints) all else emptyList()
        val ids = visible.map { it.id }.toSet()
        markers.keys.filter { it !in ids }.forEach { id ->
            markers.remove(id)?.let { runCatching { wm.removeView(it) } }
        }
        val running = engineRunning()
        visible.forEachIndexed { index, point ->
            val view = markers[point.id]
            if (view == null) addMarker(point, index, running) else updateMarker(view, point, index, running)
        }
    }

    private fun addMarker(point: ClickPoint, index: Int, running: Boolean) {
        val size = dp(point.sizeDp)
        val view = TextView(svc).apply {
            text = (index + 1).toString()
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.RED)
                setStroke(dp(2), Color.WHITE)
            }
            alpha = point.opacityPercent / 100f
            contentDescription = point.label
        }
        var downX = 0f
        var downY = 0f
        var startPx = 0
        var startPy = 0
        var touchMoved = false
        view.setOnTouchListener { v, event ->
            val current = PointStore.load().firstOrNull { it.id == point.id } ?: return@setOnTouchListener true
            val lp = v.layoutParams as WindowManager.LayoutParams
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startPx = lp.x
                    startPy = lp.y
                    touchMoved = false
                    draggingId = point.id
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!current.locked) {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (!touchMoved && (abs(dx) > DRAG_SLOP_PX || abs(dy) > DRAG_SLOP_PX)) touchMoved = true
                        if (touchMoved) {
                            val p = clamp(startPx + dx.toInt(), startPy + dy.toInt(), lp.width, lp.height)
                            lp.x = p.first
                            lp.y = p.second
                            wm.updateViewLayout(v, lp)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    draggingId = null
                    if (touchMoved && !current.locked) {
                        val nx = ((lp.x + lp.width / 2f) / screen.first).coerceIn(0f, 1f)
                        val ny = ((lp.y + lp.height / 2f) / screen.second).coerceIn(0f, 1f)
                        PointStore.save(PointStore.load().map {
                            if (it.id == current.id) it.copy(nx = nx, ny = ny) else it
                        })
                    }
                    true
                }
                else -> true
            }
        }
        val pos = clamp(
            (point.nx * screen.first - size / 2f).toInt(),
            (point.ny * screen.second - size / 2f).toInt(),
            size, size
        )
        val lp = overlayParams(size, size)
        lp.x = pos.first
        lp.y = pos.second
        if (!touchableFor(point, running)) lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        markers[point.id] = view
        runCatching { wm.addView(view, lp) }.onFailure { markers.remove(point.id) }
    }

    private fun updateMarker(view: TextView, point: ClickPoint, index: Int, running: Boolean) {
        val lp = view.layoutParams as? WindowManager.LayoutParams ?: return
        var changed = false
        val size = dp(point.sizeDp)
        if (draggingId != point.id) {
            val pos = clamp(
                (point.nx * screen.first - size / 2f).toInt(),
                (point.ny * screen.second - size / 2f).toInt(),
                size, size
            )
            if (lp.width != size || lp.height != size || lp.x != pos.first || lp.y != pos.second) {
                lp.width = size
                lp.height = size
                lp.x = pos.first
                lp.y = pos.second
                changed = true
            }
        }
        val wantNotTouchable = !touchableFor(point, running)
        val isNotTouchable = (lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0
        if (wantNotTouchable != isNotTouchable) {
            lp.flags = if (wantNotTouchable) {
                lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            } else {
                lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            }
            changed = true
        }
        if (changed) runCatching { wm.updateViewLayout(view, lp) }
        view.alpha = point.opacityPercent / 100f
        val label = (index + 1).toString()
        if (view.text.toString() != label) view.text = label
        view.contentDescription = if (point.locked) "${point.label}, locked" else "${point.label}, draggable"
    }
}

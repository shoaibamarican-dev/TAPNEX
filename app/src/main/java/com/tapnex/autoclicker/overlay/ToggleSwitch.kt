package com.tapnex.autoclicker.overlay

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/** Animated on/off switch drawn by hand so it works inside overlay windows. */
class ToggleSwitch(context: Context) : View(context) {
    var onChanged: ((Boolean) -> Unit)? = null
    var checked = false
        private set

    private val d = context.resources.displayMetrics.density
    private var progress = 0f
    private var anim: ValueAnimator? = null
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val evaluator = ArgbEvaluator()
    private val offColor = Color.parseColor("#3B4650")
    private val onColor = Color.parseColor("#E53935")

    init {
        setOnClickListener {
            val next = !checked
            setChecked(next, true)
            onChanged?.invoke(next)
        }
    }

    /** Visual state only; does not call onChanged. Safe to call every refresh. */
    fun setChecked(value: Boolean, animate: Boolean) {
        val target = if (value) 1f else 0f
        if (checked == value && (anim?.isRunning == true || progress == target)) return
        checked = value
        anim?.cancel()
        if (!animate) {
            progress = target
            invalidate()
            return
        }
        anim = ValueAnimator.ofFloat(progress, target).apply {
            duration = 180
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((46 * d).toInt(), (26 * d).toInt())
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        trackPaint.color = evaluator.evaluate(progress, offColor, onColor) as Int
        canvas.drawRoundRect(0f, 0f, w, h, h / 2, h / 2, trackPaint)
        val pad = 3 * d
        val r = h / 2 - pad
        val cx = pad + r + progress * (w - 2 * (pad + r))
        canvas.drawCircle(cx, h / 2, r, thumbPaint)
    }
}

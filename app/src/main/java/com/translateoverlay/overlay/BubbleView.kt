package com.translateoverlay.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.LinearInterpolator
import kotlin.math.hypot

/** Round floating button. Reports taps, long presses and drags to [listener]. */
@SuppressLint("ViewConstructor")
class BubbleView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        fun onTap()
        fun onLongPress()
        fun onDragStart()
        fun onDrag(dx: Float, dy: Float)
        fun onDragEnd()
    }

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x1E, 0x5E, 0xFF) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 2f * density
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 3f * density
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val arcRect = RectF()

    private var busy = false
    private var sweepStart = 0f
    private val spinner = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 900
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { sweepStart = it.animatedValue as Float; invalidate() }
    }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var longPressed = false
    private val longPressRunnable = Runnable {
        longPressed = true
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        listener.onLongPress()
    }

    fun setBusy(value: Boolean) {
        if (busy == value) return
        busy = value
        if (value) spinner.start() else spinner.cancel()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - ring.strokeWidth
        canvas.drawCircle(cx, cy, r, fill)
        canvas.drawCircle(cx, cy, r, ring)
        if (busy) {
            val inset = 5f * density
            arcRect.set(cx - r + inset, cy - r + inset, cx + r - inset, cy + r - inset)
            canvas.drawArc(arcRect, sweepStart, 100f, false, arc)
        }
        label.textSize = r * 0.62f
        canvas.drawText("文A", cx, cy - (label.descent() + label.ascent()) / 2, label)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX; downY = event.rawY
                dragging = false; longPressed = false
                postDelayed(longPressRunnable, longPressTimeout)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (!dragging && !longPressed && hypot(dx, dy) > touchSlop) {
                    dragging = true
                    removeCallbacks(longPressRunnable)
                    listener.onDragStart()
                }
                if (dragging) listener.onDrag(dx, dy)
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                when {
                    dragging -> listener.onDragEnd()
                    !longPressed -> { performClick(); listener.onTap() }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                if (dragging) listener.onDragEnd()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDetachedFromWindow() {
        spinner.cancel()
        removeCallbacks(longPressRunnable)
        super.onDetachedFromWindow()
    }
}

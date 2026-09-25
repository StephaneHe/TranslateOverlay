package com.translateoverlay.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.translateoverlay.settings.SettingsRepository

/**
 * Hosts the [BubbleView] in a TYPE_ACCESSIBILITY_OVERLAY window: drawn above every app without
 * the SYSTEM_ALERT_WINDOW permission. Handles dragging, edge snapping and position persistence.
 */
class BubbleController(
    private val context: Context,
    private val settings: SettingsRepository,
    private val onTap: () -> Unit,
    private val onLongPress: () -> Unit,
) : BubbleView.Listener {

    private val wm = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private val view = BubbleView(context, this)
    private var sizePx = 0
    private var attached = false
    private var startX = 0
    private var startY = 0
    private var snapAnimator: ValueAnimator? = null

    private val params = WindowManager.LayoutParams(
        0, 0,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    init {
        // Size must be known before the first setVisible(), which computes the position from it.
        settings.settings.value.let { applyAppearance(it.bubbleSizeDp, it.bubbleOpacity) }
    }

    fun applyAppearance(sizeDp: Int, opacity: Float) {
        val newSize = (sizeDp * density).toInt()
        view.alpha = opacity
        if (newSize == sizePx) return
        val screen = ScreenMetrics.bounds(context)
        val stuckRight = attached && params.x + sizePx >= screen.width - 1
        sizePx = newSize
        params.width = sizePx
        params.height = sizePx
        if (attached) {
            if (stuckRight) params.x = screen.width - sizePx
            clampInto(screen.width, screen.height)
            wm.updateViewLayout(view, params)
        }
    }

    fun setVisible(visible: Boolean) {
        if (visible == attached) return
        if (visible) {
            val screen = ScreenMetrics.bounds(context)
            val saved = settings.bubblePosition()
            params.x = saved?.first ?: (screen.width - sizePx)
            params.y = saved?.second ?: (screen.height / 3)
            clampInto(screen.width, screen.height)
            view.visibility = View.VISIBLE
            // BadTokenException if the service is being disconnected: stay detached.
            if (runCatching { wm.addView(view, params) }.isFailure) return
        } else {
            snapAnimator?.cancel()
            runCatching { wm.removeView(view) }
        }
        attached = visible
    }

    /** Hides the bubble without detaching it, e.g. while taking a screenshot. */
    fun setHidden(hidden: Boolean) {
        view.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
    }

    fun setBusy(busy: Boolean) = view.setBusy(busy)

    override fun onTap() = onTap.invoke()
    override fun onLongPress() = onLongPress.invoke()

    override fun onDragStart() {
        snapAnimator?.cancel()
        startX = params.x
        startY = params.y
    }

    override fun onDrag(dx: Float, dy: Float) {
        params.x = startX + dx.toInt()
        params.y = startY + dy.toInt()
        if (attached) wm.updateViewLayout(view, params)
    }

    override fun onDragEnd() {
        val screen = ScreenMetrics.bounds(context)
        clampInto(screen.width, screen.height)
        val targetX = if (params.x + sizePx / 2 < screen.width / 2) 0 else screen.width - sizePx
        snapAnimator = ValueAnimator.ofInt(params.x, targetX).apply {
            duration = 180
            addUpdateListener {
                params.x = it.animatedValue as Int
                if (attached) wm.updateViewLayout(view, params)
            }
            start()
        }
        settings.saveBubblePosition(targetX, params.y)
    }

    private fun clampInto(width: Int, height: Int) {
        params.x = params.x.coerceIn(0, (width - sizePx).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (height - sizePx).coerceAtLeast(0))
    }

    fun destroy() = setVisible(false)
}

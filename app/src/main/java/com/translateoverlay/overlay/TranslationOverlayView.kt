package com.translateoverlay.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextUtils
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import com.translateoverlay.core.Bidi
import com.translateoverlay.core.BlockSource
import com.translateoverlay.core.Box
import com.translateoverlay.core.EngineTier
import com.translateoverlay.core.TextAlign
import com.translateoverlay.core.TextFitter
import com.translateoverlay.core.TranslatedBlock

/**
 * Full-screen overlay painting each translation over its source block, in the estimated style.
 * Tap a block to toggle original/translation; tap anywhere else to close. Blocks can be replaced
 * while it is shown ([update]): offline translation first, online one as it arrives.
 *
 * Engine indicator: a status disc drawn where the (hidden) bubble sits — colour + letter of the
 * worst engine on screen, spinning ring while improving — and a small mark in each block's end
 * corner whose shape also tells the engine (circle / triangle / square: not colour alone).
 */
@SuppressLint("ViewConstructor")
class TranslationOverlayView(
    context: Context,
    blocks: List<TranslatedBlock>,
    targetLanguage: String,
    private var caption: String,
    private val screenHeight: Int,
    private val onDismiss: () -> Unit,
) : View(context) {

    private class Item(
        val rect: RectF,
        val textLeft: Float,
        val textTop: Float,
        val layout: StaticLayout,
        val background: Paint,
        val outlineColor: Int?,
        var showTranslation: Boolean = true,
        /** The app changed under this block (carousel, ad reloaded): nothing drawn any more. */
        var stale: Boolean = false,
    ) {
        /** No translation yet (waiting for the online engine): nothing drawn, original visible. */
        val empty: Boolean get() = layout.text.isEmpty()
    }

    private val density = resources.displayMetrics.density
    // Forced (not first-strong) so a Hebrew sentence starting with a Latin word or a number stays RTL.
    private val targetDirection =
        if (Bidi.isRtlLanguage(targetLanguage)) TextDirectionHeuristics.RTL else TextDirectionHeuristics.LTR
    private val blocks = blocks.toMutableList()
    private val items = blocks.map(::prepare).toMutableList()
    private val location = IntArray(2)

    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = Color.argb(200, 0x1E, 0x5E, 0xFF)
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)
    }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(225, 0x20, 0x21, 0x24) }
    private val pillText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }
    private val pillRect = RectF()

    private var tiers: List<EngineTier> = List(blocks.size) { EngineTier.OFFLINE }
    private var showMarkers = true
    private var status = EngineTier.OFFLINE
    private var statusBox: RectF? = null
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        color = Color.WHITE
        strokeJoin = Paint.Join.ROUND
    }
    private val markPath = Path()
    private val statusText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val statusArc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 3f * density
    }
    private val arcRect = RectF()
    private var sweepStart = 0f
    private val spinner = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 900
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { sweepStart = it.animatedValue as Float; invalidate() }
    }

    private fun prepare(block: TranslatedBlock): Item {
        val box = block.block.box
        val style = block.style
        val width = box.width.coerceAtLeast(1)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.textColor
            typeface = if (style.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        val sourceRtl = Bidi.isRtlLanguage(block.sourceLanguage) || Bidi.isRtlText(block.block.text)
        // Logical alignment + target direction: mirrors the source when the reading direction flips.
        val alignment = when (Bidi.logicalAlign(style.align, style.alignMeasured, sourceRtl)) {
            TextAlign.START -> Layout.Alignment.ALIGN_NORMAL
            TextAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
            TextAlign.END -> Layout.Alignment.ALIGN_OPPOSITE
        }
        fun build(sizePx: Float): StaticLayout {
            paint.textSize = sizePx
            return StaticLayout.Builder.obtain(block.translation, 0, block.translation.length, paint, width)
                .setAlignment(alignment)
                .setTextDirection(targetDirection)
                .setIncludePad(false)
                .build()
        }
        val fit = TextFitter.fit(
            preferredPx = style.textSizePx,
            minPx = MIN_TEXT_SP * resources.displayMetrics.scaledDensity,
            maxHeightPx = box.height,
        ) { size -> build(size).height }
        val layout = build(fit.textSizePx)

        // Translation too long even at minimum size: extend the card downwards.
        val bottom = maxOf(box.bottom, (box.top + layout.height).coerceAtMost(screenHeight))
        val lineTop = block.block.lines.minOfOrNull { it.box.top }
        val textTop = when {
            lineTop != null && lineTop + layout.height <= bottom -> lineTop.toFloat()
            else -> box.top + ((bottom - box.top) - layout.height).coerceAtLeast(0) / 2f
        }
        val bg = Paint().apply { color = style.backgroundColor or (0xFF shl 24) }
        val rect = RectF(box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), bottom.toFloat())
        // OCR boxes hug the glyphs: widen the mask so outlines/anti-aliasing of the original don't show.
        if (block.block.source == BlockSource.OCR) rect.inset(-OCR_MASK_PAD * fit.textSizePx, -OCR_MASK_PAD * fit.textSizePx)
        return Item(rect, box.left.toFloat(), textTop, layout, bg, style.outlineColor)
    }

    /** Replaces block [index]'s translation (keeps whether the user toggled it to the original). */
    fun update(index: Int, translation: String) {
        if (index !in items.indices) return
        val block = blocks[index].copy(translation = translation)
        blocks[index] = block
        val shown = items[index].showTranslation
        val stale = items[index].stale
        items[index] = prepare(block).also { it.showTranslation = shown; it.stale = stale }
        invalidate()
    }

    /** Engine tier of each block ([markers]: draw the per-block marks) and of the whole screen. */
    fun setEngineState(blockTiers: List<EngineTier>, markers: Boolean) {
        tiers = blockTiers
        showMarkers = markers
        status = EngineTier.overall(blockTiers)
        if (status == EngineTier.PENDING) {
            if (!spinner.isStarted) spinner.start()
        } else {
            spinner.cancel()
        }
        invalidate()
    }

    /** Screen boxes of the blocks, overlay order (to check them against the app). */
    fun blockBoxes(): List<Box> = blocks.map { it.block.box }

    /** Hides blocks whose region of the app changed (they stay hidden); returns how many were newly hidden. */
    fun hideStale(indices: Set<Int>): Int {
        val fresh = indices.filter { it in items.indices && !items[it].stale }
        fresh.forEach { items[it].stale = true }
        if (fresh.isNotEmpty()) invalidate()
        return fresh.size
    }

    /** Screen rectangle of the floating bubble: the status disc is drawn there. */
    fun setStatusBox(box: RectF?) {
        statusBox = box
        invalidate()
    }

    override fun onDetachedFromWindow() {
        spinner.cancel()
        super.onDetachedFromWindow()
    }

    fun setCaption(text: String) {
        caption = text
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        getLocationOnScreen(location)
        canvas.save()
        canvas.translate(-location[0].toFloat(), -location[1].toFloat())
        for ((i, item) in items.withIndex()) {
            if (item.empty || item.stale) continue
            if (item.showTranslation) {
                canvas.drawRect(item.rect, item.background)
                canvas.save()
                canvas.clipRect(item.rect)
                canvas.translate(item.textLeft, item.textTop)
                item.outlineColor?.let { outline -> drawOutline(canvas, item.layout, outline) }
                item.layout.draw(canvas)
                canvas.restore()
                if (showMarkers) drawMarker(canvas, item.rect, tiers.getOrElse(i) { EngineTier.OFFLINE })
            } else {
                canvas.drawRect(item.rect, outline)
            }
        }
        statusBox?.let { drawStatus(canvas, it) }
        canvas.restore()
        drawCaption(canvas)
    }

    /**
     * Small mark at the block's top-end corner (reading direction of the target language): in the
     * margin just outside the block when there is room, so it never covers a letter; else inside.
     */
    private fun drawMarker(canvas: Canvas, rect: RectF, tier: EngineTier) {
        val size = MARK_DP * density
        val gap = 1.5f * density
        if (rect.height() < size) return
        val rtl = targetDirection == TextDirectionHeuristics.RTL
        val screenWidth = resources.displayMetrics.widthPixels
        val cx = when {
            !rtl && rect.right + gap + size <= screenWidth -> rect.right + gap + size / 2
            rtl && rect.left - gap - size >= 0 -> rect.left - gap - size / 2
            rect.width() < 3 * size -> return
            rtl -> rect.left + gap + size / 2
            else -> rect.right - gap - size / 2
        }
        val cy = rect.top + size / 2
        val r = size / 2
        markPaint.color = tierColor(tier)
        markPaint.style = if (tier == EngineTier.PENDING) Paint.Style.STROKE else Paint.Style.FILL
        markPaint.strokeWidth = 1.8f * density
        markPath.reset()
        when (tier) {
            EngineTier.BEST, EngineTier.PENDING -> markPath.addCircle(cx, cy, r, Path.Direction.CW)
            EngineTier.FALLBACK -> {
                markPath.moveTo(cx, cy - r)
                markPath.lineTo(cx + r, cy + r * 0.8f)
                markPath.lineTo(cx - r, cy + r * 0.8f)
                markPath.close()
            }
            EngineTier.OFFLINE -> markPath.addRect(cx - r * 0.85f, cy - r * 0.85f, cx + r * 0.85f, cy + r * 0.85f, Path.Direction.CW)
        }
        if (tier != EngineTier.PENDING) canvas.drawPath(markPath, markEdge)
        canvas.drawPath(markPath, markPaint)
    }

    /** Stand-in for the bubble (hidden while the overlay is shown): engine state of the screen. */
    private fun drawStatus(canvas: Canvas, box: RectF) {
        val cx = box.centerX()
        val cy = box.centerY()
        val r = minOf(box.width(), box.height()) / 2f - markEdge.strokeWidth
        markPaint.style = Paint.Style.FILL
        markPaint.color = tierColor(status)
        canvas.drawCircle(cx, cy, r, markPaint)
        canvas.drawCircle(cx, cy, r, markEdge)
        if (status == EngineTier.PENDING) {
            val inset = 5f * density
            arcRect.set(cx - r + inset, cy - r + inset, cx + r - inset, cy + r - inset)
            canvas.drawArc(arcRect, sweepStart, 100f, false, statusArc)
        }
        statusText.color = if (status == EngineTier.FALLBACK) Color.BLACK else Color.WHITE
        statusText.textSize = r * if (status == EngineTier.PENDING) 0.62f else 0.9f
        val label = if (status == EngineTier.PENDING) "文A" else status.letter
        canvas.drawText(label, cx, cy - (statusText.descent() + statusText.ascent()) / 2, statusText)
    }

    /** Meme/subtitle style: stroke the glyphs in the outline colour, then fill on top. */
    private fun drawOutline(canvas: Canvas, layout: StaticLayout, outline: Int) {
        val paint = layout.paint
        val fill = paint.color
        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeWidth = paint.textSize * OUTLINE_WIDTH
        paint.color = outline or (0xFF shl 24)
        layout.draw(canvas)
        paint.style = Paint.Style.FILL
        paint.color = fill
    }

    private fun drawCaption(canvas: Canvas) {
        val pad = 12f * density
        // Ellipsized: the engine/progress part comes first, the closing hint is cut if needed.
        val caption = TextUtils.ellipsize(this.caption, pillText, width - 4 * pad, TextUtils.TruncateAt.END).toString()
        val textWidth = pillText.measureText(caption)
        val h = pillText.textSize + pad
        val cx = width / 2f
        val bottom = height - 48f * density
        pillRect.set(cx - textWidth / 2 - pad, bottom - h, cx + textWidth / 2 + pad, bottom)
        canvas.drawRoundRect(pillRect, h / 2, h / 2, pillPaint)
        canvas.drawText(caption, cx, pillRect.centerY() - (pillText.descent() + pillText.ascent()) / 2, pillText)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            getLocationOnScreen(location)
            val x = event.x + location[0]
            val y = event.y + location[1]
            // The status disc stands for the bubble: touching it closes, like outside the text.
            val onStatus = statusBox?.contains(x, y) == true
            val hit = if (onStatus) null else items.lastOrNull { !it.empty && !it.stale && it.rect.contains(x, y) }
            if (hit != null) {
                hit.showTranslation = !hit.showTranslation
                invalidate()
            } else {
                performClick()
                onDismiss()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun tierColor(tier: EngineTier): Int = when (tier) {
        EngineTier.BEST -> Color.rgb(0x2E, 0x7D, 0x32) // green
        EngineTier.FALLBACK -> Color.rgb(0xF9, 0xA8, 0x25) // amber
        EngineTier.OFFLINE -> Color.rgb(0xD8, 0x43, 0x15) // deep orange
        EngineTier.PENDING -> Color.rgb(0x1E, 0x5E, 0xFF) // the bubble's own blue
    }

    private companion object {
        const val MARK_DP = 7f
        const val MIN_TEXT_SP = 8f
        const val OCR_MASK_PAD = 0.15f
        const val OUTLINE_WIDTH = 0.12f
    }
}

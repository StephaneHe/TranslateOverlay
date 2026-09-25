package com.translateoverlay.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import com.translateoverlay.core.Bidi
import com.translateoverlay.core.BlockSource
import com.translateoverlay.core.TextAlign
import com.translateoverlay.core.TextFitter
import com.translateoverlay.core.TranslatedBlock

/**
 * Full-screen overlay painting each translation over its source block, in the estimated style.
 * Tap a block to toggle original/translation; tap anywhere else to close.
 */
@SuppressLint("ViewConstructor")
class TranslationOverlayView(
    context: Context,
    blocks: List<TranslatedBlock>,
    targetLanguage: String,
    private val caption: String,
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
    )

    private val density = resources.displayMetrics.density
    // Forced (not first-strong) so a Hebrew sentence starting with a Latin word or a number stays RTL.
    private val targetDirection =
        if (Bidi.isRtlLanguage(targetLanguage)) TextDirectionHeuristics.RTL else TextDirectionHeuristics.LTR
    private val items = blocks.map(::prepare)
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

    override fun onDraw(canvas: Canvas) {
        getLocationOnScreen(location)
        canvas.save()
        canvas.translate(-location[0].toFloat(), -location[1].toFloat())
        for (item in items) {
            if (item.showTranslation) {
                canvas.drawRect(item.rect, item.background)
                canvas.save()
                canvas.clipRect(item.rect)
                canvas.translate(item.textLeft, item.textTop)
                item.outlineColor?.let { outline -> drawOutline(canvas, item.layout, outline) }
                item.layout.draw(canvas)
                canvas.restore()
            } else {
                canvas.drawRect(item.rect, outline)
            }
        }
        canvas.restore()
        drawCaption(canvas)
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
            val hit = items.lastOrNull { it.rect.contains(x, y) }
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

    private companion object {
        const val MIN_TEXT_SP = 8f
        const val OCR_MASK_PAD = 0.15f
        const val OUTLINE_WIDTH = 0.12f
    }
}

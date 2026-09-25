package com.translateoverlay.pipeline

import android.graphics.Bitmap
import com.translateoverlay.core.AlignmentEstimator
import com.translateoverlay.core.Argb
import com.translateoverlay.core.BlockStyle
import com.translateoverlay.core.Box
import com.translateoverlay.core.ColorEstimator
import com.translateoverlay.core.TextBlock
import com.translateoverlay.core.TextSizeEstimator

/** Android glue: samples the screenshot behind a block and delegates to the pure estimators. */
class StyleEstimator(private val scaledDensity: Float) {
    private val minPx get() = 9f * scaledDensity
    private val maxPx get() = 40f * scaledDensity

    fun estimate(block: TextBlock, screenshot: Bitmap?): BlockStyle {
        val lineBoxes = block.lines.map { it.box }
        val size = (TextSizeEstimator.fromLines(lineBoxes)
            ?: TextSizeEstimator.fromBlock(block.box, block.text, 11f * scaledDensity, maxPx))
            .coerceIn(minPx, maxPx * 2)
        val align = AlignmentEstimator.estimate(lineBoxes, block.box)
        val measured = lineBoxes.size >= 2

        val colors = screenshot?.let { sample(it, block, size) }
        return if (colors != null) {
            BlockStyle(
                size, colors.text, colors.background, align, ColorEstimator.isBold(colors.strokeRatio),
                alignMeasured = measured, outlineColor = colors.outline,
            )
        } else {
            // No screenshot (Android < 11, secure window, OCR disabled): neutral dark card.
            BlockStyle(size, Argb.rgb(0xFA, 0xFA, 0xFA), Argb.rgb(0x20, 0x21, 0x24), align, bold = false, alignMeasured = measured)
        }
    }

    private fun sample(bitmap: Bitmap, block: TextBlock, sizePx: Float) = run {
        val screen = Box(0, 0, bitmap.width, bitmap.height)
        // Sample where the glyphs are when OCR lines are known, otherwise the whole block.
        val lineUnion = block.lines.map { it.box }.reduceOrNull(Box::union)
        val region = (lineUnion ?: block.box).intersect(screen)
        if (region.isEmpty) return@run null
        val step = (region.height / 60).coerceAtLeast(1)
        val rows = (region.height + step - 1) / step
        val pixels = IntArray(region.width * rows)
        for (r in 0 until rows) {
            bitmap.getPixels(pixels, r * region.width, region.width, region.left, region.top + r * step, region.width, 1)
        }
        // OCR line boxes are tight: the ring just around them is the background, even on a photo.
        // (Not for bare node boxes: outside a button lies the page, not the button colour.)
        val hint = lineUnion?.let { ColorEstimator.medianColor(ringPixels(bitmap, region, screen, sizePx)) }
        ColorEstimator.estimate(pixels, region.width, rows, sizePx, hint)
    }

    /** Pixels of a thin band around [region] (clipped to the screen), subsampled. */
    private fun ringPixels(bitmap: Bitmap, region: Box, screen: Box, sizePx: Float): IntArray {
        val pad = (sizePx / 5).toInt().coerceIn(3, 24)
        val outer = Box(region.left - pad, region.top - pad, region.right + pad, region.bottom + pad).intersect(screen)
        val out = ArrayList<Int>()
        val row = IntArray(outer.width.coerceAtLeast(1))
        var y = outer.top
        while (y < outer.bottom) {
            bitmap.getPixels(row, 0, outer.width, outer.left, y, outer.width, 1)
            val inBand = y < region.top || y >= region.bottom
            var x = 0
            while (x < outer.width) {
                val px = outer.left + x
                if (inBand || px < region.left || px >= region.right) out += row[x]
                x += 2
            }
            y += 2
        }
        return out.toIntArray()
    }
}

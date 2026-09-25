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
            BlockStyle(size, colors.text, colors.background, align, ColorEstimator.isBold(colors.strokeRatio), measured)
        } else {
            // No screenshot (Android < 11, secure window, OCR disabled): neutral dark card.
            BlockStyle(size, Argb.rgb(0xFA, 0xFA, 0xFA), Argb.rgb(0x20, 0x21, 0x24), align, bold = false, alignMeasured = measured)
        }
    }

    private fun sample(bitmap: Bitmap, block: TextBlock, sizePx: Float) = run {
        // Sample where the glyphs are when OCR lines are known, otherwise the whole block.
        val region = (block.lines.map { it.box }.reduceOrNull(Box::union) ?: block.box)
            .intersect(Box(0, 0, bitmap.width, bitmap.height))
        if (region.isEmpty) return@run null
        val step = (region.height / 60).coerceAtLeast(1)
        val rows = (region.height + step - 1) / step
        val pixels = IntArray(region.width * rows)
        for (r in 0 until rows) {
            bitmap.getPixels(pixels, r * region.width, region.width, region.left, region.top + r * step, region.width, 1)
        }
        ColorEstimator.estimate(pixels, region.width, rows, sizePx)
    }
}

package com.translateoverlay.core

import kotlin.math.abs
import kotlin.math.sqrt

/** ARGB helpers without android.graphics.Color so the logic stays JVM-testable. */
object Argb {
    fun a(c: Int) = (c ushr 24) and 0xFF
    fun r(c: Int) = (c shr 16) and 0xFF
    fun g(c: Int) = (c shr 8) and 0xFF
    fun b(c: Int) = c and 0xFF
    fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    fun distance(c1: Int, c2: Int): Double {
        val dr = (r(c1) - r(c2)).toDouble()
        val dg = (g(c1) - g(c2)).toDouble()
        val db = (b(c1) - b(c2)).toDouble()
        return sqrt(dr * dr + dg * dg + db * db)
    }

    /** Relative luminance in [0, 1] (approximate, sRGB weights without linearisation). */
    fun luminance(c: Int): Double = (0.2126 * r(c) + 0.7152 * g(c) + 0.0722 * b(c)) / 255.0

    fun contrasting(bg: Int): Int = if (luminance(bg) > 0.55) rgb(0x11, 0x11, 0x11) else rgb(0xFA, 0xFA, 0xFA)
}

/** @property outline colour of a contrasting outline around the glyphs (memes, video subtitles), if any. */
data class ColorEstimate(val background: Int, val text: Int, val strokeRatio: Double, val outline: Int? = null)

/**
 * Estimates background colour, text colour and stroke thickness from the pixels behind a block.
 * Background = [backgroundHint] when given (colour sampled just around the text), otherwise the
 * most frequent quantised colour; text = most frequent colour clearly distinct from it.
 *
 * The hint matters on photos: a noisy background spreads over many colour bins, so the uniform
 * fill of big white meme letters would otherwise win and the style would come out inverted.
 */
object ColorEstimator {
    private const val INK_DISTANCE = 90.0
    private const val OUTLINE_LUMINANCE_GAP = 0.6

    /**
     * @param pixels row-major ARGB pixels, [width] × [rows]
     * @param textHeightPx approximate glyph height, used to normalise stroke width for bold detection
     */
    fun estimate(
        pixels: IntArray,
        width: Int,
        rows: Int,
        textHeightPx: Float,
        backgroundHint: Int? = null,
    ): ColorEstimate? {
        if (width <= 0 || rows <= 0 || pixels.size < width * rows) return null
        val counts = HashMap<Int, IntArray>() // key -> [count, sumR, sumG, sumB]
        for (i in 0 until width * rows) {
            val p = pixels[i]
            val key = ((Argb.r(p) shr 4) shl 8) or ((Argb.g(p) shr 4) shl 4) or (Argb.b(p) shr 4)
            val acc = counts.getOrPut(key) { IntArray(4) }
            acc[0]++; acc[1] += Argb.r(p); acc[2] += Argb.g(p); acc[3] += Argb.b(p)
        }
        val background = backgroundHint ?: counts.values.maxByOrNull { it[0] }?.let(::average) ?: return null

        val inkBins = counts.values.filter { Argb.distance(average(it), background) >= INK_DISTANCE }
        val textBin = inkBins.maxByOrNull { it[0] }
        val text = textBin?.let { average(it) } ?: Argb.contrasting(background)
        // A second ink colour covering a good part of the glyphs, at the opposite end of the lightness
        // scale (white/black, yellow/black), is an outline. Mere colour differences (blue links in a
        // black paragraph) are not.
        val outline = textBin?.let { tb ->
            inkBins.filter {
                val c = average(it)
                abs(Argb.luminance(c) - Argb.luminance(text)) >= OUTLINE_LUMINANCE_GAP && it[0] * 10 >= tb[0] * 3
            }.maxByOrNull { it[0] }?.let { average(it) }
        }

        // Mean horizontal run length of "ink" pixels, relative to text height.
        var runs = 0
        var runPixels = 0
        for (y in 0 until rows) {
            var inRun = false
            for (x in 0 until width) {
                val ink = Argb.distance(pixels[y * width + x], background) >= INK_DISTANCE
                if (ink) {
                    runPixels++
                    if (!inRun) { runs++; inRun = true }
                } else {
                    inRun = false
                }
            }
        }
        val meanRun = if (runs == 0) 0.0 else runPixels.toDouble() / runs
        val strokeRatio = if (textHeightPx <= 0f) 0.0 else meanRun / textHeightPx
        return ColorEstimate(background, text, strokeRatio, outline)
    }

    /** Per-channel median: robust colour of a noisy background ring. */
    fun medianColor(pixels: IntArray): Int? {
        if (pixels.isEmpty()) return null
        fun median(channel: (Int) -> Int) = pixels.map(channel).sorted()[pixels.size / 2]
        return Argb.rgb(median(Argb::r), median(Argb::g), median(Argb::b))
    }

    /**
     * Heuristic: mean horizontal ink run / font size. Measured on a real device (Chrome, Roboto):
     * regular ≈ 0.14, bold ≈ 0.21.
     */
    fun isBold(strokeRatio: Double): Boolean = strokeRatio >= 0.185

    private fun average(acc: IntArray): Int {
        val n = acc[0].coerceAtLeast(1)
        return Argb.rgb(acc[1] / n, acc[2] / n, acc[3] / n)
    }
}

object AlignmentEstimator {
    fun estimate(lines: List<Box>, block: Box): TextAlign {
        if (lines.size < 2) return TextAlign.START
        val tolerance = (block.width * 0.03).coerceAtLeast(4.0)
        fun spread(values: List<Int>) = (values.max() - values.min()).toDouble()
        val left = spread(lines.map { it.left })
        val center = spread(lines.map { it.centerX })
        val right = spread(lines.map { it.right })
        return when {
            left <= tolerance -> TextAlign.START
            center <= tolerance -> TextAlign.CENTER
            right <= tolerance -> TextAlign.END
            else -> TextAlign.START
        }
    }
}

object TextSizeEstimator {
    /**
     * OCR line boxes are tight on the ink (ascender top to descender bottom), which is about one em
     * on a real device: font size ≈ line height.
     */
    fun fromLines(lines: List<Box>): Float? {
        if (lines.isEmpty()) return null
        val heights = lines.map { it.height }.sorted()
        return heights[heights.size / 2].toFloat()
    }

    /** Fallback when only the node bounds are known (includes padding, hence the 1.5 factor). */
    fun fromBlock(box: Box, text: String, minPx: Float, maxPx: Float): Float {
        val explicitLines = text.count { it == '\n' } + 1
        return (box.height / explicitLines / 1.5f).coerceIn(minPx, maxPx)
    }
}

data class FitResult(val textSizePx: Float, val heightPx: Int)

/**
 * Finds the largest text size ≤ [preferredPx] (down to [minPx]) whose laid-out height fits in
 * [maxHeightPx]. If even [minPx] overflows, returns [minPx] and the required height so the caller
 * can extend the box vertically.
 */
object TextFitter {
    fun fit(
        preferredPx: Float,
        minPx: Float,
        maxHeightPx: Int,
        measureHeight: (sizePx: Float) -> Int,
    ): FitResult {
        var size = preferredPx.coerceAtLeast(minPx)
        while (true) {
            val h = measureHeight(size)
            if (h <= maxHeightPx) return FitResult(size, h)
            if (size <= minPx) return FitResult(minPx, h)
            size = (size * 0.92f).coerceAtLeast(minPx)
        }
    }
}

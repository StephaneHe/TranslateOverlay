package com.translateoverlay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StyleEstimationTest {
    private val white = Argb.rgb(255, 255, 255)
    private val black = Argb.rgb(0, 0, 0)
    private val red = Argb.rgb(200, 20, 20)

    /** Background with vertical "strokes" of [strokeWidth] px every 10 px. */
    private fun glyphs(bg: Int, fg: Int, width: Int, rows: Int, strokeWidth: Int) =
        IntArray(width * rows) { i -> if ((i % width) % 10 < strokeWidth) fg else bg }

    @Test
    fun `dominant colour is background, contrasting colour is text`() {
        val est = ColorEstimator.estimate(glyphs(white, red, 100, 10, 2), 100, 10, textHeightPx = 20f)
        assertNotNull(est)
        assertEquals(white, est!!.background)
        assertEquals(red, est.text)
    }

    @Test
    fun `dark theme is detected`() {
        val est = ColorEstimator.estimate(glyphs(black, white, 100, 10, 2), 100, 10, 20f)!!
        assertEquals(black, est.background)
        assertEquals(white, est.text)
    }

    @Test
    fun `uniform region falls back to a contrasting text colour`() {
        val est = ColorEstimator.estimate(IntArray(100) { white }, 10, 10, 20f)!!
        assertEquals(white, est.background)
        assertTrue(Argb.luminance(est.text) < 0.2)
    }

    @Test
    fun `thick strokes are bold, thin strokes are not`() {
        val thin = ColorEstimator.estimate(glyphs(white, black, 100, 5, 2), 100, 5, 20f)!!
        val thick = ColorEstimator.estimate(glyphs(white, black, 100, 5, 4), 100, 5, 20f)!!
        // Ratios measured on a real screenshot: regular 0.14, bold 0.21.
        assertFalse(ColorEstimator.isBold(0.14))
        assertTrue(ColorEstimator.isBold(0.21))
        assertFalse(ColorEstimator.isBold(thin.strokeRatio))
        assertTrue(ColorEstimator.isBold(thick.strokeRatio))
    }

    @Test
    fun `meme on a noisy photo is not inverted when the ring colour is known`() {
        // White Impact fill dominates the box; the photo behind is spread over many colour bins.
        val rnd = java.util.Random(1)
        val width = 100
        val rows = 20
        val pixels = IntArray(width * rows) { i ->
            val x = i % width
            when {
                x % 10 in 2..6 -> white
                x % 10 == 1 || x % 10 == 7 -> black
                else -> Argb.rgb(90 + rnd.nextInt(60), 110 + rnd.nextInt(60), 70 + rnd.nextInt(60))
            }
        }
        val photo = Argb.rgb(120, 140, 100)
        val naive = ColorEstimator.estimate(pixels, width, rows, 20f)!!
        val hinted = ColorEstimator.estimate(pixels, width, rows, 20f, backgroundHint = photo)!!
        assertEquals(white, naive.background) // the bug: white letters taken as background
        assertEquals(photo, hinted.background)
        assertEquals(white, hinted.text)
        assertEquals(black, hinted.outline)
    }

    @Test
    fun `coloured links in a paragraph are not an outline`() {
        val blue = Argb.rgb(51, 102, 204)
        val pixels = IntArray(100 * 5) { i -> if (i % 10 < 2) black else if (i % 10 < 4) blue else white }
        val est = ColorEstimator.estimate(pixels, 100, 5, 20f)!!
        assertNull(est.outline)
    }

    @Test
    fun `median colour of a ring`() {
        val ring = intArrayOf(Argb.rgb(10, 20, 30), Argb.rgb(12, 22, 32), Argb.rgb(250, 250, 250))
        assertEquals(Argb.rgb(12, 22, 32), ColorEstimator.medianColor(ring))
        assertNull(ColorEstimator.medianColor(IntArray(0)))
    }

    @Test
    fun `invalid input returns null`() {
        assertNull(ColorEstimator.estimate(IntArray(3), 10, 10, 20f))
        assertNull(ColorEstimator.estimate(IntArray(0), 0, 0, 20f))
    }

    @Test
    fun `alignment from line edges`() {
        val block = Box(0, 0, 400, 100)
        val left = listOf(Box(10, 0, 390, 20), Box(10, 25, 200, 45))
        val center = listOf(Box(50, 0, 350, 20), Box(120, 25, 280, 45))
        val right = listOf(Box(10, 0, 390, 20), Box(200, 25, 390, 45))
        val ragged = listOf(Box(10, 0, 300, 20), Box(100, 25, 350, 45))
        assertEquals(TextAlign.START, AlignmentEstimator.estimate(left, block))
        assertEquals(TextAlign.CENTER, AlignmentEstimator.estimate(center, block))
        assertEquals(TextAlign.END, AlignmentEstimator.estimate(right, block))
        assertEquals(TextAlign.START, AlignmentEstimator.estimate(ragged, block))
        assertEquals(TextAlign.START, AlignmentEstimator.estimate(left.take(1), block))
    }

    @Test
    fun `text size from median line height`() {
        val lines = listOf(Box(0, 0, 10, 20), Box(0, 0, 10, 30), Box(0, 0, 10, 100))
        assertEquals(30f, TextSizeEstimator.fromLines(lines)!!, 0.01f)
        assertNull(TextSizeEstimator.fromLines(emptyList()))
        assertEquals(20f, TextSizeEstimator.fromBlock(Box(0, 0, 10, 60), "a\nb", 10f, 50f), 0.01f)
        assertEquals(10f, TextSizeEstimator.fromBlock(Box(0, 0, 10, 6), "a", 10f, 50f), 0.01f)
    }

    @Test
    fun `fitter keeps preferred size when it fits`() {
        val r = TextFitter.fit(20f, 8f, 100) { size -> (size * 2).toInt() }
        assertEquals(20f, r.textSizePx, 0.01f)
        assertEquals(40, r.heightPx)
    }

    @Test
    fun `fitter shrinks until it fits`() {
        val r = TextFitter.fit(40f, 8f, 50) { size -> (size * 2).toInt() }
        assertTrue(r.textSizePx <= 25f)
        assertTrue(r.heightPx <= 50)
    }

    @Test
    fun `fitter reports overflow at minimum size`() {
        val r = TextFitter.fit(40f, 10f, 5) { size -> (size * 2).toInt() }
        assertEquals(10f, r.textSizePx, 0.01f)
        assertEquals(20, r.heightPx)
    }
}

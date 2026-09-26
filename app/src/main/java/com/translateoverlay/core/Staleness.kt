package com.translateoverlay.core

import kotlin.math.abs

/**
 * Is the overlay still showing the screen it was made for? The app may change under it without
 * any touch (the overlay takes them): a URL opened by another app, a page scrolled by a script,
 * an ad or a carousel reloading. Events alone can't tell (Chrome fires content changes for its
 * translate prompt too), so the text nodes captured with the translation are compared with the
 * current ones: text and position. Pure logic (unit-tested).
 */
object Staleness {
    /**
     * @property dismiss most of the screen changed (new page, scroll): close the overlay.
     * @property staleBlocks overlay blocks over a region that changed (carousel, ad): hide them.
     */
    data class Verdict(val dismiss: Boolean, val staleBlocks: Set<Int>, val keptShare: Float, val lost: List<String> = emptyList())

    /**
     * @param baseline tree text blocks captured for the translation
     * @param overlayBoxes box of each overlay block
     * @param current tree text blocks now
     * @param tolerancePx how far a block may move and still count as in place
     */
    fun evaluate(baseline: List<TextBlock>, overlayBoxes: List<Box>, current: List<TextBlock>, tolerancePx: Int): Verdict {
        // Nothing to compare (image-only screen, tree not exposed): keep until the app changes.
        if (baseline.isEmpty()) return Verdict(dismiss = false, staleBlocks = emptySet(), keptShare = 1f)
        val byText = current.groupBy { normalize(it.text) }
        val kept = baseline.map { b ->
            byText[normalize(b.text)].orEmpty().any { c -> near(b.box, c.box, tolerancePx) }
        }
        val weights = baseline.map { it.text.length.coerceAtLeast(1) }
        val keptShare = baseline.indices.sumOf { if (kept[it]) weights[it] else 0 }.toFloat() / weights.sum()
        val lost = baseline.indices.filter { !kept[it] }.map { baseline[it].text.take(30) }
        if (keptShare < MIN_KEPT_SHARE) return Verdict(dismiss = true, staleBlocks = emptySet(), keptShare = keptShare, lost = lost)

        val stale = overlayBoxes.indices.filter { i ->
            // Nodes mostly inside the block: a popup that merely overlapped it (Chrome's translate
            // prompt over the menu row) and then went away must not hide it.
            val under = baseline.indices.filter { baseline[it].box.intersectionArea(overlayBoxes[i]) * 2 >= baseline[it].box.area }
            if (under.isEmpty()) return@filter false // e.g. text inside an image: no node to check
            val lost = under.sumOf { if (kept[it]) 0 else weights[it] }
            lost * 2 > under.sumOf { weights[it] }
        }.toSet()
        return Verdict(dismiss = false, staleBlocks = stale, keptShare = keptShare, lost = lost)
    }

    /** Below this share of the captured text still in place, the overlay is for another screen. */
    const val MIN_KEPT_SHARE = 0.6f

    private fun normalize(text: String) = text.replace(Regex("\\s+"), " ").trim()

    private fun near(a: Box, b: Box, tol: Int) =
        abs(a.left - b.left) <= tol && abs(a.top - b.top) <= tol && abs(a.right - b.right) <= tol && abs(a.bottom - b.bottom) <= tol
}

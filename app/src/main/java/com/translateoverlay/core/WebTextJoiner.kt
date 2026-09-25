package com.translateoverlay.core

/**
 * Joins the inline text runs exposed by web pages (one accessibility node per link, bold span…)
 * back into paragraphs, in document order.
 *
 * Two consecutive runs belong to the same paragraph when they share the same parent element and
 * follow each other without a vertical gap, or when the second one continues on the line where the
 * first one ends (the element tree can nest inline runs in ways that are not visible in the
 * accessibility tree). Chrome may expose the texts of several `<p>`/`<h2>` as siblings: without the
 * gap rule they would merge into one block whose box spans the images between them.
 */
object WebTextJoiner {

    /**
     * Web content clipped by an ancestor (collapsed news-flash bodies with `overflow: hidden` on
     * ynet) is reported "visible" by Chrome although most of it lies outside its container.
     */
    fun isClippedAway(own: Box, clip: Box): Boolean =
        !own.isEmpty && own.intersect(clip).area * 10 < own.area * 7

    data class Run(val text: String, val box: Box, val group: Any)

    fun join(runs: List<Run>): List<TextBlock> {
        val out = ArrayList<TextBlock>()
        var i = 0
        while (i < runs.size) {
            val sb = StringBuilder(runs[i].text)
            var box = runs[i].box
            var j = i + 1
            while (j < runs.size && joins(runs[j - 1], runs[j])) {
                // Line breaks between two runs carry no space character in the tree ("order" / "Carnivora").
                val wrapped = runs[j].box.top >= runs[j - 1].box.bottom - minOf(runs[j].box.height, runs[j - 1].box.height) / 4
                if (wrapped && sb.isNotEmpty() && !sb.last().isWhitespace() && runs[j].text.firstOrNull()?.isWhitespace() == false) {
                    sb.append(' ')
                }
                sb.append(runs[j].text)
                box = box.union(runs[j].box)
                j++
            }
            val text = sb.toString().replace(WHITESPACE, " ").trim()
            if (text.isNotEmpty()) out += TextBlock(text, box, BlockSource.NODE)
            i = j
        }
        return out
    }

    private fun joins(prev: Run, next: Run): Boolean =
        continuesLine(prev.box, next.box) ||
            (next.group === prev.group && !separatedByGap(prev.box, next.box) && !sideBySideItems(prev.box, next.box))

    /**
     * Menu/tab items of the same element laid out on one line with visible spacing (ynet's
     * "כותרות  מבזקים  חדשות…" menu, concatenated without spaces in the tree): separate items.
     * Works in both directions (RTL items are laid out right to left).
     */
    fun sideBySideItems(prev: Box, next: Box): Boolean {
        val small = minOf(prev.height, next.height).coerceAtLeast(1)
        val verticalOverlap = minOf(prev.bottom, next.bottom) - maxOf(prev.top, next.top)
        val horizontalGap = maxOf(next.left - prev.right, prev.left - next.right)
        return verticalOverlap * 2 >= small && horizontalGap > maxOf(MIN_GAP_PX, small / 2)
    }

    /**
     * True when [next] starts clearly below [prev]: consecutive lines of a paragraph touch (line
     * height includes leading), separate paragraphs, headings and images leave a margin.
     */
    fun separatedByGap(prev: Box, next: Box): Boolean {
        val lineHeight = minOf(prev.height, next.height).coerceAtLeast(1)
        return next.top - prev.bottom > maxOf(MIN_GAP_PX, lineHeight / 2)
    }

    /** True when [next] starts on the last line of [prev] (runs may span several lines). */
    fun continuesLine(prev: Box, next: Box): Boolean {
        // Tolerance so that runs merely touching vertically (stacked blocks) do not count.
        val tolerance = minOf(prev.height, next.height) / 4
        val sameLine = next.top >= prev.top && next.top < prev.bottom - tolerance && next.bottom > prev.bottom - tolerance
        // Flowing text runs touch (spaces belong to the runs); separate items (tabs, buttons) leave a
        // gap, on either side (RTL runs flow right to left).
        val gap = maxOf(next.left - prev.right, prev.left - next.right)
        return (sameLine && gap <= tolerance) || isSuperscriptNeighbour(prev, next)
    }

    /**
     * Citation marks like "[1]" are raised and smaller: they neither sit on the same line box nor
     * share the paragraph's element, yet belong to it. A clearly smaller run overlapping its
     * neighbour vertically (and not starting below it) is joined.
     */
    fun isSuperscriptNeighbour(prev: Box, next: Box): Boolean {
        val small = minOf(prev.height, next.height)
        val large = maxOf(prev.height, next.height)
        val verticalOverlap = minOf(prev.bottom, next.bottom) - maxOf(prev.top, next.top)
        return small * 5 < large * 4 && verticalOverlap * 3 >= small && next.top < prev.bottom &&
            next.left - prev.right <= large / 3
    }

    private const val MIN_GAP_PX = 6
    private val WHITESPACE = Regex("\\s+")
}

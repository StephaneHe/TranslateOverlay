package com.translateoverlay.core

/**
 * Joins the inline text runs exposed by web pages (one accessibility node per link, bold span…)
 * back into paragraphs, in document order.
 *
 * Two consecutive runs belong to the same paragraph when they share the same parent element, or
 * when the second one continues on the line where the first one ends (the element tree can nest
 * inline runs in ways that are not visible in the accessibility tree).
 */
object WebTextJoiner {

    data class Run(val text: String, val box: Box, val group: Any)

    fun join(runs: List<Run>): List<TextBlock> {
        val out = ArrayList<TextBlock>()
        var i = 0
        while (i < runs.size) {
            val sb = StringBuilder(runs[i].text)
            var box = runs[i].box
            var j = i + 1
            while (j < runs.size && (runs[j].group === runs[j - 1].group || continuesLine(runs[j - 1].box, runs[j].box))) {
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

    /** True when [next] starts on the last line of [prev] (runs may span several lines). */
    fun continuesLine(prev: Box, next: Box): Boolean {
        // Tolerance so that runs merely touching vertically (stacked blocks) do not count.
        val tolerance = minOf(prev.height, next.height) / 4
        val sameLine = next.top >= prev.top && next.top < prev.bottom - tolerance && next.bottom > prev.bottom - tolerance
        // Flowing text runs touch (spaces belong to the runs); separate items (tabs, buttons) leave a gap.
        val gap = next.left - prev.right
        return sameLine && gap <= tolerance
    }

    private val WHITESPACE = Regex("\\s+")
}

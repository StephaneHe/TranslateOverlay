package com.translateoverlay.core

/**
 * Combines text from the accessibility tree (exact text) with OCR results (text inside images).
 *
 * - Accessibility blocks win: an OCR block mostly covered by accessibility blocks is dropped.
 * - OCR lines lying inside an accessibility block are attached to it, giving line geometry
 *   used later to estimate text size and alignment.
 */
object BlockMerger {

    /**
     * @param confirmLatinNodesWithOcr when a Latin OCR pass ran, drop Latin-script node texts with no
     *   OCR text inside their bounds: they are not actually drawn (icon labels, visually hidden text).
     */
    fun merge(
        nodes: List<TextBlock>,
        ocr: List<TextBlock>,
        overlapThreshold: Double = 0.5,
        confirmLatinNodesWithOcr: Boolean = false,
    ): List<TextBlock> {
        val cleanNodes = dedupeNested(nodes)
        val ocrLines = ocr.flatMap { block -> block.lines.ifEmpty { listOf(TextLine(block.text, block.box)) } }

        val enrichedNodes = cleanNodes.mapNotNull { node ->
            val inside = ocrLines.filter { node.box.containsPoint(it.box.centerX, it.box.centerY) }
            when {
                inside.isNotEmpty() -> node.copy(lines = inside.sortedBy { it.box.top })
                confirmLatinNodesWithOcr && isMostlyLatin(node.text) -> null
                else -> node
            }
        }

        val keptOcr = ocr.filter { block ->
            val area = block.box.area
            if (area == 0L) return@filter false
            val covered = cleanNodes.sumOf { it.box.intersectionArea(block.box) }
            covered.toDouble() / area < overlapThreshold
        }

        return (enrichedNodes + keptOcr).sortedWith(compareBy({ it.box.top }, { it.box.left }))
    }

    fun isMostlyLatin(text: String): Boolean {
        var letters = 0
        var latin = 0
        for (ch in text) {
            if (!Character.isLetter(ch)) continue
            letters++
            if (Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.LATIN) latin++
        }
        return letters > 0 && latin * 10 >= letters * 7
    }

    /**
     * Removes exact duplicates and container nodes whose text merely aggregates the text of a
     * smaller node inside them (common with Compose merged semantics), keeping the most precise one.
     */
    fun dedupeNested(nodes: List<TextBlock>): List<TextBlock> {
        val unique = nodes
            .filter { it.text.isNotBlank() && !it.box.isEmpty }
            .distinctBy { it.text.trim() to it.box }
        return unique.filter { outer ->
            unique.none { inner ->
                inner !== outer &&
                    inner.box != outer.box &&
                    outer.box.contains(inner.box) &&
                    outer.text.contains(inner.text.trim())
            }
        }
    }
}

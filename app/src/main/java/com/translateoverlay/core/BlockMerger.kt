package com.translateoverlay.core

/**
 * Combines text from the accessibility tree (exact text) with OCR results (text inside images).
 *
 * - Accessibility blocks win: an OCR block mostly covered by accessibility blocks is dropped.
 * - OCR lines lying inside an accessibility block are attached to it, giving line geometry
 *   used later to estimate text size and alignment.
 */
object BlockMerger {

    fun merge(
        nodes: List<TextBlock>,
        ocr: List<TextBlock>,
        overlapThreshold: Double = 0.5,
    ): List<TextBlock> {
        val cleanNodes = dedupeNested(nodes)
        val ocrLines = ocr.flatMap { block -> block.lines.ifEmpty { listOf(TextLine(block.text, block.box)) } }

        val enrichedNodes = cleanNodes.map { node ->
            val inside = ocrLines.filter { node.box.containsPoint(it.box.centerX, it.box.centerY) }
            if (inside.isEmpty()) node else node.copy(lines = inside.sortedBy { it.box.top })
        }

        val keptOcr = ocr.filter { block ->
            val area = block.box.area
            if (area == 0L) return@filter false
            val covered = cleanNodes.sumOf { it.box.intersectionArea(block.box) }
            covered.toDouble() / area < overlapThreshold
        }

        return (enrichedNodes + keptOcr).sortedWith(compareBy({ it.box.top }, { it.box.left }))
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

package com.translateoverlay.core

/**
 * Combines text from the accessibility tree (exact text) with OCR results (text inside images).
 *
 * - Accessibility blocks win: an OCR block mostly covered by accessibility blocks **reading the same
 *   text** is dropped. Geometry alone is not enough: a node's box may enclose an image (e.g. a web
 *   container whose text sits above and below a picture), and the text inside that image must stay.
 * - OCR lines lying inside an accessibility block and reading its text are attached to it, giving
 *   line geometry used later to estimate text size and alignment.
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
        val ocrLines = ocr.flatMap { block -> block.lines.ifEmpty { listOf(TextLine(block.text, block.box)) } }
        val cleanNodes = resolveStacked(dedupeNested(nodes), ocrLines)

        val enrichedNodes = cleanNodes.mapNotNull { node ->
            val inside = ocrLines.filter {
                node.box.containsPoint(it.box.centerX, it.box.centerY) && TextMatch.matches(it.text, node.text)
            }
            when {
                inside.isNotEmpty() -> node.copy(lines = inside.sortedBy { it.box.top })
                confirmLatinNodesWithOcr && isMostlyLatin(node.text) -> null
                else -> node
            }
        }

        val keptOcr = ocr.filter { block ->
            val area = block.box.area
            if (area == 0L) return@filter false
            val overlapping = cleanNodes.filter { it.box.intersectionArea(block.box) > 0 }
            val covered = overlapping.sumOf { it.box.intersectionArea(block.box) }
            val nodeText = overlapping.joinToString(" ") { it.text }
            val sameText = TextMatch.matches(block.text, nodeText)
            // Same place, other alphabet: a model forced app text into its own script (Chrome's
            // "hébreu vers français" read by Tesseract as Hebrew-looking garbage). Only for nodes
            // hugging the OCR text: an image node (file name, alt text) covering a whole banner
            // says nothing about the text drawn inside it.
            val ocrScript = ScriptDetector.dominant(block.text)
            val nodeScript = ScriptDetector.dominant(nodeText)
            val hugging = overlapping.sumOf { it.box.area } <= area * MAX_HUGGING_RATIO
            val misread = hugging && ocrScript != null && nodeScript != null && ocrScript != nodeScript
            !(covered.toDouble() / area >= overlapThreshold && (sameText || misread))
        }

        return (enrichedNodes + keptOcr).sortedWith(compareBy({ it.box.top }, { it.box.left }))
    }

    /**
     * Text nodes stacked on top of each other (collapsed news-flash bodies on ynet, still "visible"
     * in the tree): only one of them is actually drawn. The one whose words the OCR reads on screen
     * wins; without OCR evidence, the shortest (the visible headline, not the hidden body).
     */
    fun resolveStacked(nodes: List<TextBlock>, ocrLines: List<TextLine>): List<TextBlock> {
        if (ocrLines.isEmpty()) return nodes
        fun evidence(node: TextBlock): Int {
            val nodeWords = TextMatch.words(node.text).toHashSet()
            return ocrLines.filter { node.box.containsPoint(it.box.centerX, it.box.centerY) }
                .sumOf { line -> TextMatch.words(line.text).count { it in nodeWords } }
        }
        val scores = nodes.associateWith(::evidence)
        return nodes.filter { node ->
            nodes.none { other ->
                other !== node && stacked(node.box, other.box) && run {
                    val mine = scores.getValue(node)
                    val theirs = scores.getValue(other)
                    theirs > mine || (theirs == mine && other.text.length < node.text.length)
                }
            }
        }
    }

    private fun stacked(a: Box, b: Box): Boolean {
        val inter = a.intersectionArea(b)
        return inter > 0 && inter * 2 > minOf(a.area, b.area)
    }

    private const val MAX_HUGGING_RATIO = 4

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

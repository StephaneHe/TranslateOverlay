package com.translateoverlay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockMergerTest {
    private fun node(text: String, l: Int, t: Int, r: Int, b: Int) = TextBlock(text, Box(l, t, r, b), BlockSource.NODE)
    private fun ocr(text: String, l: Int, t: Int, r: Int, b: Int) =
        TextBlock(text, Box(l, t, r, b), BlockSource.OCR, listOf(TextLine(text, Box(l, t, r, b))))

    @Test
    fun `ocr block covered by a node is dropped and its line attached to the node`() {
        val nodes = listOf(node("Hello world", 0, 0, 200, 50))
        val ocrBlocks = listOf(ocr("Hel1o world", 5, 10, 190, 40))

        val merged = BlockMerger.merge(nodes, ocrBlocks)

        assertEquals(1, merged.size)
        assertEquals("Hello world", merged[0].text)
        assertEquals(BlockSource.NODE, merged[0].source)
        assertEquals(1, merged[0].lines.size)
    }

    @Test
    fun `ocr block from an image outside nodes is kept`() {
        val nodes = listOf(node("Caption", 0, 0, 200, 50))
        val ocrBlocks = listOf(ocr("SALE 50%", 0, 300, 200, 360))

        val merged = BlockMerger.merge(nodes, ocrBlocks)

        assertEquals(listOf("Caption", "SALE 50%"), merged.map { it.text })
    }

    @Test
    fun `partially overlapping ocr block under threshold is kept`() {
        val nodes = listOf(node("Title", 0, 0, 100, 100))
        val ocrBlocks = listOf(ocr("Banner", 80, 0, 180, 100)) // 20 % covered

        assertEquals(2, BlockMerger.merge(nodes, ocrBlocks).size)
    }

    @Test
    fun `container node aggregating child text is removed`() {
        val nodes = listOf(
            node("Buy now", 0, 0, 300, 100),
            node("Buy now", 20, 20, 120, 60),
            node("Buy now", 20, 20, 120, 60), // exact duplicate
        )

        val merged = BlockMerger.dedupeNested(nodes)

        assertEquals(1, merged.size)
        assertEquals(Box(20, 20, 120, 60), merged[0].box)
    }

    @Test
    fun `result is sorted top to bottom then left to right`() {
        val merged = BlockMerger.merge(
            listOf(node("B", 100, 0, 150, 20), node("C", 0, 50, 50, 70), node("A", 0, 0, 50, 20)),
            emptyList(),
        )
        assertEquals(listOf("A", "B", "C"), merged.map { it.text })
    }

    @Test
    fun `latin node without ocr evidence is dropped when confirmation is on`() {
        val nodes = listOf(node("Download PDF", 0, 0, 40, 40), node("Visible title", 0, 100, 300, 150))
        val ocrBlocks = listOf(ocr("Visible title", 5, 110, 290, 140))

        val confirmed = BlockMerger.merge(nodes, ocrBlocks, confirmLatinNodesWithOcr = true)
        val unconfirmed = BlockMerger.merge(nodes, ocrBlocks, confirmLatinNodesWithOcr = false)

        assertEquals(listOf("Visible title"), confirmed.map { it.text })
        assertEquals(2, unconfirmed.size)
    }

    @Test
    fun `non latin node is kept even without ocr evidence`() {
        val merged = BlockMerger.merge(
            listOf(node("日本語のテキスト", 0, 0, 200, 40)),
            listOf(ocr("Other", 0, 500, 100, 540)),
            confirmLatinNodesWithOcr = true,
        )
        assertEquals(2, merged.size)
    }

    @Test
    fun `mostly latin detection`() {
        assertTrue(BlockMerger.isMostlyLatin("Hello, world!"))
        assertTrue(!BlockMerger.isMostlyLatin("Привет мир"))
        assertTrue(!BlockMerger.isMostlyLatin("12:45"))
    }

    @Test
    fun `text of an image inside a node box is kept when it reads something else`() {
        // Chrome: one text block spanning a paragraph above and below a poster image.
        val nodes = listOf(node("Our store news Read the latest. Thank you for shopping with us.", 48, 315, 1032, 1353))
        val poster = ocr("SUMMER SALE", 110, 710, 800, 781)
        val heading = ocr("Our store neWS", 49, 329, 532, 390)

        val merged = BlockMerger.merge(nodes, listOf(poster, heading))

        assertTrue(merged.any { it.text == "SUMMER SALE" && it.source == BlockSource.OCR })
        assertTrue(merged.none { it.text == "Our store neWS" }) // same text as the node: absorbed
        val enriched = merged.single { it.source == BlockSource.NODE }
        assertEquals(listOf("Our store neWS"), enriched.lines.map { it.text }) // poster line not attached
    }

    @Test
    fun `stacked nodes keep the one the ocr sees`() {
        // ynet news flash: visible headline + collapsed body at the same place.
        val headline = node("הרוג בתאונה בבקעת הירדן, 3 פצועים", 40, 380, 900, 460)
        val body = node("תאונת דרכים בהשתתפות ארבעה כלי רכב התרחשה בכביש 90 ליד מחולה", 40, 380, 1000, 520)
        val seen = TextLine("הרוג בתאונה בבקעת הירדן, 3 פצועים", Box(60, 390, 880, 440), 0.9f)

        val kept = BlockMerger.resolveStacked(listOf(headline, body), listOf(seen))

        assertEquals(listOf(headline), kept)
        // No OCR at all: nothing is dropped.
        assertEquals(2, BlockMerger.resolveStacked(listOf(headline, body), emptyList()).size)
    }

    @Test
    fun `ocr in another alphabet over app text is a misreading`() {
        val nodes = listOf(node("hébreu vers français", 180, 350, 633, 399))
        val garbage = ocr("הבזה ורס פרנסז", 180, 355, 600, 395)
        assertTrue(BlockMerger.merge(nodes, listOf(garbage)).none { it.source == BlockSource.OCR })
    }

    @Test
    fun `text inside an image node named in latin is kept`() {
        // Chrome exposes an <img> without alt as its file name, covering the whole banner.
        val image = node("banner0.png", 0, 215, 1080, 1290)
        val line = ocr("יש הצעות שחייבים לקחת", 192, 798, 890, 858)
        assertTrue(BlockMerger.merge(listOf(image), listOf(line)).any { it.source == BlockSource.OCR })
    }

    @Test
    fun `blank and empty nodes are ignored`() {
        val merged = BlockMerger.merge(listOf(node("  ", 0, 0, 10, 10), node("x", 5, 5, 5, 20)), emptyList())
        assertTrue(merged.isEmpty())
    }
}

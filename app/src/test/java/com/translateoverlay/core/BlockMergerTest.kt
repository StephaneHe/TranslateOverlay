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
    fun `blank and empty nodes are ignored`() {
        val merged = BlockMerger.merge(listOf(node("  ", 0, 0, 10, 10), node("x", 5, 5, 5, 20)), emptyList())
        assertTrue(merged.isEmpty())
    }
}

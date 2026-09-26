package com.translateoverlay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefinementTest {
    private fun item(index: Int, top: Int, source: String = "he", text: String = "t$index") =
        RefineItem(index, text, source, top, 0)

    @Test
    fun `chunks follow the screen from the top, per language`() {
        // Overlay order is not screen order (OCR blocks come after the tree ones).
        val items = listOf(item(0, 900), item(1, 100), item(2, 500), item(3, 50, "en"), item(4, 300), item(5, 700))
        val chunks = RefineChunks.plan(items, itemsPerChunk = 2, maxChars = 1000)
        assertEquals(listOf(listOf(3), listOf(1, 4), listOf(2, 5), listOf(0)), chunks.map { c -> c.map { it.index } })
        assertTrue(chunks.all { c -> c.map { it.source }.toSet().size == 1 })
    }

    @Test
    fun `crowded screen - about three requests, character limit still applies`() {
        val many = (0 until 30).map { item(it, it * 10) }
        assertEquals(3, RefineChunks.plan(many, itemsPerChunk = 6, maxChars = 10_000).size)
        val long = (0 until 4).map { item(it, it, text = "x".repeat(500)) }
        assertEquals(listOf(2, 2), RefineChunks.plan(long, itemsPerChunk = 6, maxChars = 1_200).map { it.size })
    }

    private val ranks = mapOf("Nemotron Ultra" to 0, "Nemotron Super" to 1)

    @Test
    fun `caption while improving, then the engine used`() {
        val p = RefinementProgress("ML Kit", listOf(0, 1, 2), ranks)
        assertEquals("ML Kit · amélioration…", p.caption())
        assertTrue(p.offer(0, "Nemotron Ultra"))
        assertEquals("Nemotron Ultra (1/3) · amélioration…", p.caption())
        p.offer(1, "Nemotron Ultra")
        p.offer(2, "Nemotron Ultra")
        assertTrue(p.isDone)
        assertEquals("Nemotron Ultra", p.caption())
    }

    @Test
    fun `mixed engines and offline leftovers are listed`() {
        val p = RefinementProgress("ML Kit", listOf(0, 1, 2, 3), ranks)
        p.offer(0, "Nemotron Ultra")
        p.offer(1, "Nemotron Super")
        p.failed(listOf(2, 3), "NVIDIA indisponible : service surchargé")
        assertEquals("Nemotron Ultra 1 · Nemotron Super 1 · ML Kit 2", p.caption())
    }

    @Test
    fun `everything failed - offline kept with the reason`() {
        val p = RefinementProgress("ML Kit", listOf(0, 1), ranks)
        p.failed(listOf(0, 1), "NVIDIA indisponible : délai dépassé")
        assertEquals("ML Kit (NVIDIA indisponible : délai dépassé)", p.caption())
    }

    @Test
    fun `a block is never downgraded to a worse engine`() {
        val p = RefinementProgress("ML Kit", listOf(0), ranks)
        assertTrue(p.offer(0, "Nemotron Ultra"))
        assertFalse(p.offer(0, "Nemotron Super"))
        val q = RefinementProgress("ML Kit", listOf(0), ranks)
        assertTrue(q.offer(0, "Nemotron Super"))
        assertTrue(q.offer(0, "Nemotron Ultra"))
    }
}

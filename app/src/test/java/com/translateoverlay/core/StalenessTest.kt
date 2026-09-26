package com.translateoverlay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StalenessTest {
    private fun node(text: String, top: Int, left: Int = 0, right: Int = 1000, height: Int = 60) =
        TextBlock(text, Box(left, top, right, top + height), BlockSource.NODE)

    // A Wikipedia-like screen: title, tabs, hatnote, paragraph.
    private val page = listOf(
        node("Cat", 400), node("Article", 530, right = 150), node("Talk", 530, left = 170, right = 300),
        node("This article is about the species commonly kept as a pet.", 740, height = 100),
        node("The cat (Felis catus), also called domestic cat and house cat, is a small domesticated carnivorous mammal.", 1050, height = 300),
    )
    private val boxes = page.map { it.box }
    private val TOL = 40

    @Test
    fun `same screen - kept`() {
        val v = Staleness.evaluate(page, boxes, page, TOL)
        assertFalse(v.dismiss)
        assertTrue(v.staleBlocks.isEmpty())
        assertEquals(1f, v.keptShare, 0.001f)
    }

    @Test
    fun `popup over the page (Chrome translate prompt) - page unchanged, kept`() {
        // The prompt adds its own nodes and may cover the top: the page's nodes are all still there.
        val now = page + node("Traduire la page ?", 200) + node("hébreu vers français", 260)
        val v = Staleness.evaluate(page, boxes, now, TOL)
        assertFalse(v.dismiss)
        assertTrue(v.staleBlocks.isEmpty())
    }

    @Test
    fun `small jitter (layout settling, sub-pixel) - kept`() {
        val now = page.map { it.copy(box = Box(it.box.left, it.box.top + 12, it.box.right, it.box.bottom + 12)) }
        assertFalse(Staleness.evaluate(page, boxes, now, TOL).dismiss)
    }

    @Test
    fun `page scrolled - dismissed`() {
        val now = page.map { it.copy(box = Box(it.box.left, it.box.top - 300, it.box.right, it.box.bottom - 300)) }
        assertTrue(Staleness.evaluate(page, boxes, now, TOL).dismiss)
    }

    @Test
    fun `new page (click on an article, new URL) - dismissed`() {
        val article = listOf(node("Dog", 400), node("Article", 530, right = 150), node("The dog is a domesticated descendant of the wolf.", 1050, height = 300))
        val v = Staleness.evaluate(page, boxes, article, TOL)
        assertTrue(v.dismiss)
        assertTrue(v.keptShare < Staleness.MIN_KEPT_SHARE)
    }

    @Test
    fun `carousel or ad reloading in one region - only its blocks hidden`() {
        val base = page + node("Promo : 2 mois offerts", 1500, height = 80)
        val now = page + node("Nouvelle offre : -50 %", 1500, height = 80)
        val overlay = base.map { it.box }
        val v = Staleness.evaluate(base, overlay, now, TOL)
        assertFalse(v.dismiss)
        assertEquals(setOf(5), v.staleBlocks)
    }

    @Test
    fun `text inside an image (no node under it) is never hidden alone, no tree - never dismissed`() {
        val imageText = Box(0, 1600, 1000, 1700)
        val v = Staleness.evaluate(page, boxes + imageText, page, TOL)
        assertFalse(5 in v.staleBlocks)
        assertFalse(Staleness.evaluate(emptyList(), listOf(imageText), emptyList(), TOL).dismiss)
    }

    @Test
    fun `popup going away over a block (V30T, ynet menu row) - the block stays`() {
        val prompt = node("Traduire la page ? hébreu vers français", 200, left = 30, right = 860, height = 180)
        val menu = node("כותרות מבזקים חדשות", 350, height = 80)
        val base = page + prompt + menu
        val overlay = base.map { it.box }
        val v = Staleness.evaluate(base, overlay, page + menu, TOL)
        assertFalse(v.dismiss)
        assertFalse(base.lastIndex in v.staleBlocks) // the menu row, overlapped by the prompt
    }

    @Test
    fun `whitespace differences do not count as a change`() {
        val now = page.map { it.copy(text = it.text.replace(" ", "  ") + "\n") }
        assertEquals(1f, Staleness.evaluate(page, boxes, now, TOL).keptShare, 0.001f)
    }
}

package com.translateoverlay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebTextJoinerTest {
    private val p1 = Any()
    private val p2 = Any()
    private val bold = Any()

    private fun run(text: String, group: Any, l: Int, t: Int, r: Int, b: Int) =
        WebTextJoiner.Run(text, Box(l, t, r, b), group)

    @Test
    fun `runs of the same paragraph are joined in order`() {
        val blocks = WebTextJoiner.join(
            listOf(
                run("The ", p1, 0, 0, 60, 50),
                run("cat", p1, 60, 0, 120, 50),
                run(" is a small  mammal.", p1, 0, 0, 1000, 150),
                run("Next paragraph", p2, 0, 200, 800, 250),
            ),
        )
        assertEquals(listOf("The cat is a small mammal.", "Next paragraph"), blocks.map { it.text })
        assertEquals(Box(0, 0, 1000, 150), blocks[0].box)
    }

    @Test
    fun `run of another element continuing on the same line joins the paragraph`() {
        val blocks = WebTextJoiner.join(
            listOf(
                run("The ", p1, 0, 0, 60, 50),
                run("cat", bold, 60, 0, 120, 50), // nested inline element
                run(", also called domestic cat and house cat, is small.", p1, 120, 0, 1000, 150),
            ),
        )
        assertEquals(1, blocks.size)
        assertEquals("The cat, also called domestic cat and house cat, is small.", blocks[0].text)
    }

    @Test
    fun `a space is inserted between runs split by a line break`() {
        val blocks = WebTextJoiner.join(
            listOf(
                run("in the ", p1, 0, 0, 600, 50),
                run("order", p1, 600, 0, 800, 50),
                run("Carnivora", p1, 0, 60, 300, 110),
                run(" also", p1, 300, 60, 450, 110),
            ),
        )
        assertEquals("in the order Carnivora also", blocks.single().text)
    }

    @Test
    fun `same element runs separated by an image are not joined`() {
        // Chrome exposed h2, p, (img), p texts as siblings of the same parent.
        val blocks = WebTextJoiner.join(
            listOf(
                run("Our store news", p1, 48, 315, 540, 395),
                run("Read the latest announcement.", p1, 48, 460, 1030, 570),
                run("Thank you for shopping.", p1, 48, 1295, 980, 1355),
            ),
        )
        assertEquals(3, blocks.size)
        assertTrue(WebTextJoiner.separatedByGap(Box(48, 460, 1030, 570), Box(48, 1295, 980, 1355)))
        assertFalse(WebTextJoiner.separatedByGap(Box(0, 0, 100, 50), Box(0, 52, 100, 102)))
    }

    @Test
    fun `citation superscript does not split a paragraph`() {
        // "... past the sign.[1] In many countries ..." (Wikipedia, raised and smaller "[1]").
        val blocks = WebTextJoiner.join(
            listOf(
                run("and pedestrians before continuing past the sign.", p1, 48, 330, 1000, 460),
                run("[1]", Any(), 150, 395, 190, 425),
                run(" In many countries, the sign is a red octagon.", p2, 190, 400, 1000, 540),
            ),
        )
        assertEquals(1, blocks.size)
        assertTrue(WebTextJoiner.isSuperscriptNeighbour(Box(150, 395, 190, 425), Box(190, 400, 1000, 540)))
        assertFalse(WebTextJoiner.isSuperscriptNeighbour(Box(48, 640, 170, 690), Box(210, 640, 290, 690))) // tabs
    }

    @Test
    fun `rtl menu items on one line are separate blocks`() {
        // ynet menu: items of one element, laid out right to left with spacing.
        val menu = Any()
        val blocks = WebTextJoiner.join(
            listOf(
                run("כותרות", menu, 900, 430, 1050, 468),
                run("מבזקים", menu, 700, 430, 850, 468),
                run("חדשות", menu, 520, 430, 650, 468),
            ),
        )
        assertEquals(listOf("כותרות", "מבזקים", "חדשות"), blocks.map { it.text })
        // Flowing RTL text runs (no gap) still join.
        assertFalse(WebTextJoiner.sideBySideItems(Box(600, 430, 1000, 468), Box(300, 430, 600, 468)))
    }

    @Test
    fun `collapsed content outside its container is clipped away`() {
        // ynet news flash item (container 380..620) with its collapsed body laid out below it.
        val container = Box(0, 380, 1080, 620)
        assertTrue(WebTextJoiner.isClippedAway(Box(40, 600, 1040, 1100), container))
        assertFalse(WebTextJoiner.isClippedAway(Box(40, 400, 900, 470), container)) // headline
        assertFalse(WebTextJoiner.isClippedAway(Box(0, 0, 0, 0), container))
    }

    @Test
    fun `heading followed by paragraph stays separate`() {
        val blocks = WebTextJoiner.join(
            listOf(run("Title", p1, 0, 0, 300, 60), run("Body text", p2, 0, 80, 900, 130)),
        )
        assertEquals(2, blocks.size)
    }

    @Test
    fun `continues line geometry`() {
        assertTrue(WebTextJoiner.continuesLine(Box(0, 0, 100, 50), Box(100, 0, 200, 50)))
        // Next run starts on the last line of a multi-line run.
        assertTrue(WebTextJoiner.continuesLine(Box(0, 0, 1000, 150), Box(400, 100, 1000, 250)))
        assertFalse(WebTextJoiner.continuesLine(Box(0, 0, 100, 50), Box(0, 60, 100, 110)))
        // Tabs "Article" / "Talk": same line but separated by a visible gap.
        assertFalse(WebTextJoiner.continuesLine(Box(48, 640, 170, 690), Box(210, 640, 290, 690)))
    }
}

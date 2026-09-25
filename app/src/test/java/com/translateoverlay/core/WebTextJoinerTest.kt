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

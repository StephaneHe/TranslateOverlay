package com.translateoverlay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class OcrLogicTest {
    private fun line(text: String, confidence: Float) = TextLine(text, Box(0, 0, 100, 20), confidence)

    @Test
    fun `script of characters`() {
        assertEquals(Script.LATIN, ScriptDetector.scriptOf('a'))
        assertEquals(Script.HEBREW, ScriptDetector.scriptOf('ש'))
        assertEquals(Script.HAN, ScriptDetector.scriptOf('中'))
        assertEquals(Script.JAPANESE, ScriptDetector.scriptOf('ひ'))
        assertEquals(Script.KOREAN, ScriptDetector.scriptOf('한'))
        assertNull(ScriptDetector.scriptOf('5'))
    }

    @Test
    fun `hints only report non latin scripts with enough letters`() {
        val hebrewPage = listOf("ברוכים הבאים לתל אביב", "Menu", "חנות")
        assertEquals(setOf(Script.HEBREW), ScriptDetector.hints(hebrewPage))
        assertTrue(ScriptDetector.hints(listOf("Hello world, this is a long English sentence")).isEmpty())
        assertTrue(ScriptDetector.hints(listOf("שלום")).isEmpty()) // too few letters to be a signal
    }

    @Test
    fun `share of a script`() {
        assertEquals(1.0, ScriptDetector.share("שלום עולם", Script.HEBREW), 1e-9)
        assertEquals(0.0, ScriptDetector.share("Hello", Script.HEBREW), 1e-9)
        assertEquals(0.0, ScriptDetector.share("123", Script.LATIN), 1e-9)
    }

    @Test
    fun `hebrew image read by latin model is suspicious, real latin text is not`() {
        // Confidences measured on the V30T: garbage 0.33, real English 0.77-0.92.
        assertTrue(OcrSelection.isSuspicious(line("T272 Dh TOPn yYNn", 0.33f)))
        assertFalse(OcrSelection.isSuspicious(line("SUMMER SALE", 0.79f)))
    }

    @Test
    fun `toolbar symbols forced into hebrew are not a plausible line`() {
        assertFalse(OcrSelection.isPlausibleLine("+ | /0508765ח|0094! 600 = מ", Script.HEBREW))
        assertTrue(OcrSelection.isPlausibleLine("ברוכים הבאים לתל אביב", Script.HEBREW))
        assertTrue(OcrSelection.isPlausibleLine("מבצע 50% היום", Script.HEBREW))
        assertFalse(OcrSelection.isPlausibleLine("SUMMER SALE", Script.HEBREW))
    }

    @Test
    fun `only line shaped regions are re-read`() {
        assertTrue(OcrSelection.isWorthRereading(Box(308, 676, 1032, 735), "T272 Dh TOPn yYNn"))
        assertFalse(OcrSelection.isWorthRereading(Box(49, 155, 95, 201), "G")) // toolbar icon
        assertFalse(OcrSelection.isWorthRereading(Box(0, 0, 60, 60), "ABCD")) // square: icon/logo
    }

    @Test
    fun `best alternative requires confident reading in its own script`() {
        val hebrew = Script.HEBREW to listOf(line("ברוכים הבאים לתל אביב", 0.9f))
        val chineseGarbage = Script.HAN to listOf(line("T 2 7", 0.4f))
        val hebrewOnLatin = Script.HEBREW to listOf(line("SUMMER SALE", 0.9f)) // letters not Hebrew
        assertEquals(0, OcrSelection.best(listOf(hebrew, chineseGarbage)))
        assertEquals(1, OcrSelection.best(listOf(chineseGarbage, hebrew)))
        assertNull(OcrSelection.best(listOf(chineseGarbage, hebrewOnLatin)))
        assertNull(OcrSelection.best(listOf(Script.HEBREW to emptyList())))
    }

    @Test
    fun `text match tolerates ocr mistakes but not different text`() {
        assertTrue(TextMatch.matches("Our store neWS", "Our store news Read the latest"))
        assertTrue(TextMatch.matches("Read the latest announcement from our team below.", "Read the latest announcement from our team below."))
        assertFalse(TextMatch.matches("SUMMER SALE", "Our store news Read the latest announcement from our team below."))
        assertFalse(TextMatch.matches("", "anything"))
    }

    @Test
    fun `all caps source keeps all caps translation`() {
        val fr = Locale.FRENCH
        assertEquals("SOLDES D'ÉTÉ", CaseStyle.apply("SUMMER SALE", "Soldes d'été", fr))
        assertEquals("Soldes d'été", CaseStyle.apply("Summer sale", "Soldes d'été", fr))
        assertEquals("À partir de 5", CaseStyle.apply("5", "À partir de 5", fr))
        assertFalse(CaseStyle.isAllCaps("A"))
        assertFalse(CaseStyle.isAllCaps("שלום")) // no case in Hebrew
    }
}

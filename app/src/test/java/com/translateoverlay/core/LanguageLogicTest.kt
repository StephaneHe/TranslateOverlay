package com.translateoverlay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageLogicTest {

    @Test
    fun `normalize maps legacy and romanised tags`() {
        assertEquals("he", LanguageTags.normalize("iw"))
        assertEquals("tl", LanguageTags.normalize("fil"))
        assertEquals("zh", LanguageTags.normalize("zh"))
        assertEquals("pt", LanguageTags.normalize("pt-BR"))
        assertEquals(UNDETERMINED, LanguageTags.normalize("ja-Latn"))
        assertEquals(UNDETERMINED, LanguageTags.normalize("und"))
        assertEquals(UNDETERMINED, LanguageTags.normalize(null))
        assertEquals(UNDETERMINED, LanguageTags.normalize(""))
    }

    @Test
    fun `same language ignores region`() {
        assertTrue(LanguageTags.sameLanguage("fr", "fr-CA"))
        assertFalse(LanguageTags.sameLanguage("fr", "en"))
    }

    @Test
    fun `dominant language is weighted by text length and ignores und`() {
        val votes = listOf("en" to 10, "de" to 50, UNDETERMINED to 500, "en" to 30)
        assertEquals("de", LanguageVoter.dominant(votes))
        assertNull(LanguageVoter.dominant(listOf(UNDETERMINED to 10)))
    }

    @Test
    fun `resolve falls back to dominant only when undetermined`() {
        assertEquals("de", LanguageVoter.resolve(UNDETERMINED, "de"))
        assertEquals("en", LanguageVoter.resolve("en", "de"))
        assertEquals(UNDETERMINED, LanguageVoter.resolve(UNDETERMINED, null))
    }

    @Test
    fun `short blocks follow the dominant language`() {
        assertEquals("en", LanguageVoter.resolve("cy", "en", textLength = 3))
        assertEquals("de", LanguageVoter.resolve("de", "en", textLength = 80))
        assertEquals("cy", LanguageVoter.resolve("cy", null, textLength = 3))
    }

    @Test
    fun `short block already in the target language is not pulled to the dominant language`() {
        // Chrome's French "Traduire la page ?" bar over a Hebrew page, target French.
        assertEquals("fr", LanguageVoter.resolve("fr", "he", textLength = 18, target = "fr"))
        assertEquals("he", LanguageVoter.resolve("de", "he", textLength = 5, target = "fr"))
    }

    @Test
    fun `short label plausible in the target language is left alone`() {
        // "Partager" on a French phone was identified as Danish, then pulled to English.
        assertEquals("fr", LanguageVoter.resolve("da", "en", textLength = 8, target = "fr", targetPlausible = true))
        assertEquals("fr", LanguageVoter.resolve(UNDETERMINED, "en", textLength = 8, target = "fr", targetPlausible = true))
        assertEquals("en", LanguageVoter.resolve("da", "en", textLength = 8, target = "fr", targetPlausible = false))
        // Long text: plausibility does not override a reliable detection.
        assertEquals("en", LanguageVoter.resolve("en", "en", textLength = 80, target = "fr", targetPlausible = true))
    }

    @Test
    fun `ui in target language when most short labels read as target`() {
        assertTrue(LanguageVoter.uiInTarget(listOf(true, false)))
        assertTrue(LanguageVoter.uiInTarget(listOf(true, true, false)))
        assertFalse(LanguageVoter.uiInTarget(listOf(false, false, true)))
        assertFalse(LanguageVoter.uiInTarget(emptyList()))
    }

    @Test
    fun `dominant of blocks ignores short blocks unless all are short`() {
        val votes = listOf("ro" to 4, "ro" to 5, "ro" to 6, "ro" to 7, "en" to 120)
        assertEquals("en", LanguageVoter.dominantOfBlocks(votes))
        assertEquals("ro", LanguageVoter.dominantOfBlocks(listOf("ro" to 4, "en" to 3)))
    }

    @Test
    fun `translatable filter`() {
        assertTrue(TranslatableFilter.isTranslatable("Hello"))
        assertTrue(TranslatableFilter.isTranslatable("中"))
        assertTrue(TranslatableFilter.isTranslatable("Привет, мир"))
        assertFalse(TranslatableFilter.isTranslatable("12:45"))
        assertFalse(TranslatableFilter.isTranslatable("  "))
        assertFalse(TranslatableFilter.isTranslatable("€ 3,50"))
        assertFalse(TranslatableFilter.isTranslatable("https://example.com/page"))
        assertFalse(TranslatableFilter.isTranslatable("john@doe.com"))
        assertFalse(TranslatableFilter.isTranslatable("en.wikipedia.org/wiki/Cat"))
        assertFalse(TranslatableFilter.isTranslatable("example.com"))
        assertFalse(TranslatableFilter.isTranslatable("localhost:8765/index.html"))
        assertFalse(TranslatableFilter.isTranslatable("A O localhost:8765/he + 33")) // OCR of Chrome's toolbar
        assertTrue(TranslatableFilter.isTranslatable("I am here"))
        assertTrue(TranslatableFilter.isTranslatable("5 € off"))
        assertFalse(TranslatableFilter.isTranslatable("9 de.wikipedia.org/'"))
        assertTrue(TranslatableFilter.isTranslatable("Visit example.com today"))
        assertTrue(TranslatableFilter.isTranslatable("Hello. World"))
        assertFalse(TranslatableFilter.isTranslatable("x"))
    }

    @Test
    fun `ocr lines are joined with hyphenation repair`() {
        assertEquals("Hello wonderful world", OcrText.joinLines(listOf("Hello wonder-", "ful world")))
        assertEquals("Hello world", OcrText.joinLines(listOf("Hello", " ", "world")))
        assertEquals("Well-Known", OcrText.joinLines(listOf("Well-", "Known")).replace(" ", ""))
        assertEquals("你好世界", OcrText.joinLines(listOf("你好", "世界")))
    }
}

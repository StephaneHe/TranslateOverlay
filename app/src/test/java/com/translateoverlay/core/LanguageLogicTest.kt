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

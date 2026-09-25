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
    fun `translatable filter`() {
        assertTrue(TranslatableFilter.isTranslatable("Hello"))
        assertTrue(TranslatableFilter.isTranslatable("中"))
        assertTrue(TranslatableFilter.isTranslatable("Привет, мир"))
        assertFalse(TranslatableFilter.isTranslatable("12:45"))
        assertFalse(TranslatableFilter.isTranslatable("  "))
        assertFalse(TranslatableFilter.isTranslatable("€ 3,50"))
        assertFalse(TranslatableFilter.isTranslatable("https://example.com/page"))
        assertFalse(TranslatableFilter.isTranslatable("john@doe.com"))
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

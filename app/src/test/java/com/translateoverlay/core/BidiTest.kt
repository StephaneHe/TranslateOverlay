package com.translateoverlay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BidiTest {

    @Test
    fun `rtl languages including legacy hebrew code`() {
        assertTrue(Bidi.isRtlLanguage("he"))
        assertTrue(Bidi.isRtlLanguage("iw")) // ML Kit language-id legacy code
        assertTrue(Bidi.isRtlLanguage("ar"))
        assertTrue(Bidi.isRtlLanguage("fa"))
        assertFalse(Bidi.isRtlLanguage("fr"))
        assertFalse(Bidi.isRtlLanguage("und"))
    }

    @Test
    fun `rtl text detection with mixed latin and digits`() {
        assertTrue(Bidi.isRtlText("שלום עולם"))
        assertTrue(Bidi.isRtlText("ה-iPhone 15 הוא טלפון חכם של אפל"))
        assertTrue(Bidi.isRtlText("مرحبا بالعالم"))
        assertFalse(Bidi.isRtlText("Hello world"))
        assertFalse(Bidi.isRtlText("Visit שלום.com and iPhone store"))
        assertFalse(Bidi.isRtlText("12 345"))
    }

    @Test
    fun `left aligned latin source becomes start aligned so a hebrew target is right aligned`() {
        // Laid out with RTL direction, logical START is the right edge: the paragraph is mirrored.
        assertEquals(TextAlign.START, Bidi.logicalAlign(TextAlign.START, measured = true, sourceRtl = false))
        assertEquals(TextAlign.END, Bidi.logicalAlign(TextAlign.END, measured = true, sourceRtl = false))
    }

    @Test
    fun `right aligned hebrew source becomes start aligned so a latin target is left aligned`() {
        assertEquals(TextAlign.START, Bidi.logicalAlign(TextAlign.END, measured = true, sourceRtl = true))
        assertEquals(TextAlign.END, Bidi.logicalAlign(TextAlign.START, measured = true, sourceRtl = true))
    }

    @Test
    fun `centered stays centered and unmeasured uses natural target alignment`() {
        assertEquals(TextAlign.CENTER, Bidi.logicalAlign(TextAlign.CENTER, measured = true, sourceRtl = true))
        assertEquals(TextAlign.CENTER, Bidi.logicalAlign(TextAlign.CENTER, measured = true, sourceRtl = false))
        assertEquals(TextAlign.START, Bidi.logicalAlign(TextAlign.END, measured = false, sourceRtl = false))
        assertEquals(TextAlign.START, Bidi.logicalAlign(TextAlign.START, measured = false, sourceRtl = true))
    }

    @Test
    fun `ocr script coverage`() {
        val latin = setOf(Script.LATIN)
        val hebrew = setOf(Script.HEBREW)
        assertTrue(LanguageScripts.isReadableBy("en", latin))
        assertTrue(LanguageScripts.isReadableBy("fr", latin))
        assertFalse(LanguageScripts.isReadableBy("he", latin))
        assertFalse(LanguageScripts.isReadableBy("iw", latin))
        assertTrue(LanguageScripts.isReadableBy("he", hebrew))
        assertFalse(LanguageScripts.isReadableBy("en", hebrew))
        assertEquals(Script.CYRILLIC, LanguageScripts.scriptOf("ru"))
    }
}

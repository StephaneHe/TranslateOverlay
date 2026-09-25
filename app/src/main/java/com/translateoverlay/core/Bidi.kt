package com.translateoverlay.core

/** Right-to-left handling: which languages/texts are RTL and how alignment mirrors between them. */
object Bidi {
    private val rtlLanguages = setOf("he", "yi", "ar", "fa", "ur", "ps", "sd", "ug", "dv", "ckb")

    fun isRtlLanguage(code: String): Boolean = LanguageTags.normalize(code) in rtlLanguages

    /** True when strong right-to-left characters outnumber strong left-to-right ones. */
    fun isRtlText(text: String): Boolean {
        var rtl = 0
        var ltr = 0
        for (ch in text) {
            when (Character.getDirectionality(ch)) {
                Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> rtl++
                Character.DIRECTIONALITY_LEFT_TO_RIGHT -> ltr++
            }
        }
        return rtl > ltr
    }

    /**
     * Converts the alignment measured on screen (physical: [TextAlign.START] = left edges aligned,
     * [TextAlign.END] = right edges aligned) into the logical alignment of the translation
     * ([TextAlign.START] = where reading starts). Laid out with the target's own direction, this
     * mirrors the source: a left-aligned English paragraph becomes a right-aligned Hebrew one.
     *
     * @param measured false when the alignment could not be measured (single line, no OCR): the
     *   natural alignment of the target (reading start) is used.
     */
    fun logicalAlign(physical: TextAlign, measured: Boolean, sourceRtl: Boolean): TextAlign = when {
        !measured -> TextAlign.START
        physical == TextAlign.CENTER -> TextAlign.CENTER
        !sourceRtl -> physical
        physical == TextAlign.START -> TextAlign.END
        else -> TextAlign.START
    }
}

/** Writing systems, to know which languages an OCR model can actually read. */
enum class Script { LATIN, HEBREW, ARABIC, CYRILLIC, GREEK, HAN, JAPANESE, KOREAN, DEVANAGARI, OTHER }

object LanguageScripts {
    private val byLanguage = mapOf(
        "he" to Script.HEBREW, "yi" to Script.HEBREW,
        "ar" to Script.ARABIC, "fa" to Script.ARABIC, "ur" to Script.ARABIC,
        "ru" to Script.CYRILLIC, "uk" to Script.CYRILLIC, "be" to Script.CYRILLIC,
        "bg" to Script.CYRILLIC, "mk" to Script.CYRILLIC,
        "el" to Script.GREEK,
        "zh" to Script.HAN, "ja" to Script.JAPANESE, "ko" to Script.KOREAN,
        "hi" to Script.DEVANAGARI, "mr" to Script.DEVANAGARI,
        "ka" to Script.OTHER, "hy" to Script.OTHER, "th" to Script.OTHER, "bn" to Script.OTHER,
        "ta" to Script.OTHER, "te" to Script.OTHER, "kn" to Script.OTHER, "gu" to Script.OTHER,
    )

    /** Script of a language; languages not listed are written in Latin script. */
    fun scriptOf(language: String): Script = byLanguage[LanguageTags.normalize(language)] ?: Script.LATIN

    fun isReadableBy(language: String, ocrCovers: Set<Script>): Boolean = scriptOf(language) in ocrCovers
}

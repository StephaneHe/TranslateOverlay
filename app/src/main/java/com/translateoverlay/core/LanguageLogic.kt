package com.translateoverlay.core

const val UNDETERMINED = "und"

/** Normalises language-identification tags to translation-model codes. */
object LanguageTags {
    private val aliases = mapOf("iw" to "he", "fil" to "tl", "ji" to "yi", "in" to "id", "jw" to "jv")

    /** Returns a base language code, or [UNDETERMINED] for unknown/romanised tags (e.g. "ja-Latn"). */
    fun normalize(tag: String?): String {
        if (tag.isNullOrBlank()) return UNDETERMINED
        val parts = tag.trim().lowercase().split('-', '_')
        if (parts.any { it == "latn" } && parts.first() !in latinScriptLanguages) return UNDETERMINED
        val base = parts.first()
        if (base == UNDETERMINED || base.length !in 2..3) return UNDETERMINED
        return aliases[base] ?: base
    }

    fun sameLanguage(a: String, b: String): Boolean = normalize(a) == normalize(b)

    // Languages naturally written in Latin script: a "-Latn" suffix does not mean romanisation.
    private val latinScriptLanguages = setOf("en", "fr", "de", "es", "it", "pt", "nl", "tr", "vi", "id", "ms")
}

/** Picks the dominant language of a screen, used as fallback for short or ambiguous blocks. */
object LanguageVoter {

    fun dominant(votes: List<Pair<String, Int>>): String? =
        votes
            .filter { it.first != UNDETERMINED && it.second > 0 }
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.sum() }
            .maxByOrNull { it.value }
            ?.key

    /**
     * Identification is unreliable on short strings ("Cat" → Welsh, "Talk" → Romanian), which would
     * also trigger useless model downloads: short blocks follow the dominant language of the screen.
     */
    fun resolve(
        detected: String,
        dominant: String?,
        textLength: Int = Int.MAX_VALUE,
        target: String? = null,
        targetPlausible: Boolean = false,
    ): String = when {
        // Already in the target language (e.g. browser UI in the user's language): never re-translate it.
        target != null && LanguageTags.sameLanguage(detected, target) -> detected
        // Short label that reads as the target language too ("Partager", "Modifier" on a French phone,
        // identified as Danish or undetermined): leave the device's own UI alone.
        textLength < MIN_RELIABLE_LENGTH && target != null && targetPlausible -> target
        detected == UNDETERMINED -> dominant ?: UNDETERMINED
        textLength < MIN_RELIABLE_LENGTH && dominant != null -> dominant
        else -> detected
    }

    /**
     * True when most short UI labels read as the target language: the app is displayed in the
     * user's language, so its undetermined labels (dates like "25 sept.") are left alone too.
     */
    fun uiInTarget(shortLabelsPlausibleInTarget: List<Boolean>): Boolean =
        shortLabelsPlausibleInTarget.isNotEmpty() &&
            shortLabelsPlausibleInTarget.count { it } * 2 >= shortLabelsPlausibleInTarget.size

    /**
     * OCR noise: text read in an image but identified as a real language we cannot translate. On
     * ynet, Tesseract turned a tiny Latin weather strip into Hebrew-letter garbage identified as
     * Yiddish ("7 מו 6זוטסזד"): it must be dropped, not assigned to the dominant language.
     */
    fun isOcrNoise(detected: String, translatable: Boolean): Boolean = detected != UNDETERMINED && !translatable

    /** Votes of reliable (long enough) blocks only, unless there are none. */
    fun dominantOfBlocks(votes: List<Pair<String, Int>>): String? =
        dominant(votes.filter { it.second >= MIN_RELIABLE_LENGTH }) ?: dominant(votes)

    const val MIN_RELIABLE_LENGTH = 20
}

/** Decides whether a text block is worth sending to the translator. */
object TranslatableFilter {
    private val urlOrEmail = Regex(
        """^(https?://|www\.)\S+$|^\S+@\S+\.\S+$|^[\w-]+(\.[\w-]+)*\.[a-z]{2,}(/\S*)?$|^[\w.-]+:\d+(/\S*)?$""",
        RegexOption.IGNORE_CASE,
    )
    private val WHITESPACE = Regex("\\s+")

    fun isTranslatable(text: String): Boolean {
        // URL-like tokens are removed first: OCR often glues icon glyphs to an address bar ("9 de.site.org/").
        val tokens = text.trim().split(WHITESPACE).filterNot { it.isEmpty() || urlOrEmail.matches(it) }
        // At least one real word (2+ letters) or one ideograph: isolated letters around icons and
        // numbers ("A O + 33", OCR of a browser toolbar) are noise, not text.
        return tokens.any { token ->
            token.any { Character.isLetter(it) && isIdeographicOrSyllabic(it) } ||
                token.count { Character.isLetter(it) } >= 2
        }
    }

    private fun isIdeographicOrSyllabic(ch: Char): Boolean {
        val script = Character.UnicodeScript.of(ch.code)
        return script == Character.UnicodeScript.HAN ||
            script == Character.UnicodeScript.HIRAGANA ||
            script == Character.UnicodeScript.KATAKANA ||
            script == Character.UnicodeScript.HANGUL
    }
}

/** Joins OCR lines of a paragraph into flowing text, re-attaching hyphenated words. */
object OcrText {
    fun joinLines(lines: List<String>): String {
        val sb = StringBuilder()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (sb.isEmpty()) {
                sb.append(line)
            } else if (sb.length >= 2 && sb.last() == '-' && sb[sb.length - 2].isLetter() &&
                line.first().isLowerCase()
            ) {
                sb.setLength(sb.length - 1)
                sb.append(line)
            } else if (isCjk(sb.last()) && isCjk(line.first())) {
                sb.append(line)
            } else {
                sb.append(' ').append(line)
            }
        }
        return sb.toString()
    }

    private fun isCjk(ch: Char): Boolean {
        val script = Character.UnicodeScript.of(ch.code)
        return script == Character.UnicodeScript.HAN ||
            script == Character.UnicodeScript.HIRAGANA ||
            script == Character.UnicodeScript.KATAKANA
    }
}

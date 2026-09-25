package com.translateoverlay.core

/** Writing system of individual characters, to pick OCR models from what is actually on screen. */
object ScriptDetector {

    fun scriptOf(ch: Char): Script? {
        if (!Character.isLetter(ch)) return null
        return when (Character.UnicodeScript.of(ch.code)) {
            Character.UnicodeScript.LATIN -> Script.LATIN
            Character.UnicodeScript.HEBREW -> Script.HEBREW
            Character.UnicodeScript.ARABIC -> Script.ARABIC
            Character.UnicodeScript.CYRILLIC -> Script.CYRILLIC
            Character.UnicodeScript.GREEK -> Script.GREEK
            Character.UnicodeScript.HAN -> Script.HAN
            Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> Script.JAPANESE
            Character.UnicodeScript.HANGUL -> Script.KOREAN
            Character.UnicodeScript.DEVANAGARI -> Script.DEVANAGARI
            else -> Script.OTHER
        }
    }

    /** Number of letters per script. */
    fun count(text: String): Map<Script, Int> {
        val counts = HashMap<Script, Int>()
        for (ch in text) scriptOf(ch)?.let { counts[it] = (counts[it] ?: 0) + 1 }
        return counts
    }

    /** Non-Latin scripts with at least [minLetters] letters on screen: hints for extra OCR models. */
    fun hints(texts: List<String>, minLetters: Int = 12): Set<Script> {
        val total = HashMap<Script, Int>()
        texts.forEach { t -> count(t).forEach { (s, n) -> total[s] = (total[s] ?: 0) + n } }
        return total.filter { it.key != Script.LATIN && it.key != Script.OTHER && it.value >= minLetters }.keys
    }

    /** Script of most letters of [text], or null when it has no letters. */
    fun dominant(text: String): Script? = count(text).maxByOrNull { it.value }?.key

    /** Share of letters of [text] written in [script] (0 when there are no letters). */
    fun share(text: String, script: Script): Double {
        val counts = count(text)
        val letters = counts.values.sum()
        return if (letters == 0) 0.0 else (counts[script] ?: 0).toDouble() / letters
    }
}

/**
 * Chooses between OCR readings of the same region. The default Latin model returns low-confidence
 * garbage on scripts it cannot read (Hebrew image read as "T272 Dh TOPn yYNn", confidence 0.33,
 * whereas real Latin text scores 0.77–0.92 on device); another model is then tried on the region.
 */
object OcrSelection {
    /** Latin lines below this are suspicious and re-read with other models. */
    const val SUSPICIOUS_BELOW = 0.5f

    /** An alternative reading must reach this score to replace a suspicious one. */
    const val MIN_ALTERNATIVE_SCORE = 0.55

    /**
     * Quality of a reading by a model specialised in [script]: mean line confidence weighted by the
     * share of letters actually in that script (a Hebrew model reading Latin letters is not a win).
     */
    fun score(lines: List<TextLine>, script: Script): Double {
        if (lines.isEmpty()) return 0.0
        val text = lines.joinToString(" ") { it.text }
        val confidence = lines.map { it.confidence.toDouble() }.average()
        return confidence * ScriptDetector.share(text, script)
    }

    fun isSuspicious(line: TextLine): Boolean = line.confidence < SUSPICIOUS_BELOW

    /**
     * Only line-shaped regions with a few characters are worth re-reading with other models: a
     * lone doubtful glyph is an icon ("G", "X" on Wikipedia), and trying 5 models on each of them
     * cost ~2 s per screen on device.
     */
    fun isWorthRereading(box: Box, text: String): Boolean =
        text.count { !it.isWhitespace() } >= MIN_REREAD_CHARS && box.width >= 2 * box.height

    private const val MIN_REREAD_CHARS = 4

    /**
     * When the screen shows another script (e.g. Hebrew on ynet), mixed or small lines that the
     * Latin model reads with middling confidence are usually that script: measured 0.39–0.57 for
     * "…בכפוף לתקנון | Colmobil" and "mercedes-benz.co.il:לפרטים…", vs 0.78–0.92 for real Latin.
     */
    const val DOUBTFUL_WITH_OTHER_SCRIPT = 0.7f

    fun shouldReread(line: TextLine, otherScriptOnScreen: Boolean): Boolean = isSuspiciousWith(line, otherScriptOnScreen)

    fun isSuspiciousWith(line: TextLine, otherScriptOnScreen: Boolean): Boolean =
        line.confidence < (if (otherScriptOnScreen) DOUBTFUL_WITH_OTHER_SCRIPT else SUSPICIOUS_BELOW)

    /** Minimum confidence of a reading by a model specialised in another script. */
    const val MIN_ALTERNATIVE_CONFIDENCE = 0.6f

    /**
     * Whether the reading [alternative] of a region by the [script] model should replace the Latin
     * reading [latin]: it must be text in that script and at least about as confident.
     */
    fun preferAlternative(latin: TextLine, alternative: TextLine, script: Script): Boolean =
        isPlausibleLine(alternative.text, script) &&
            alternative.confidence >= MIN_ALTERNATIVE_CONFIDENCE &&
            alternative.confidence + 0.1f >= latin.confidence

    /**
     * Tesseract reads dark text on a light background best, with glyphs ≳ 30 px: crops of light
     * text on dark banners are inverted and small lines upscaled.
     */
    fun tesseractScale(lineHeightPx: Int): Int = when {
        lineHeightPx <= 0 -> 1
        lineHeightPx < 24 -> 3
        lineHeightPx < 48 -> 2
        else -> 1
    }

    fun shouldInvert(backgroundLuminance: Double): Boolean = backgroundLuminance < 0.45

    /**
     * A line read by a model specialised in [script] is text in that script, not symbols/numbers
     * the model forced into it (Chrome's toolbar read by Tesseract as "+ | /0508765ח|0094! 600 = מ",
     * confidence 0.73): at least 3 letters of the script, and letters make up most of the line.
     */
    fun isPlausibleLine(text: String, script: Script): Boolean {
        val chars = text.count { !it.isWhitespace() }
        val counts = ScriptDetector.count(text)
        val inScript = counts[script] ?: 0
        return inScript >= 3 && inScript * 2 >= counts.values.sum() && counts.values.sum() * 2 >= chars
    }

    /** Index of the best alternative reading, or null if none is good enough. */
    fun best(alternatives: List<Pair<Script, List<TextLine>>>): Int? =
        alternatives.withIndex()
            .map { (i, alt) -> i to score(alt.second, alt.first) }
            .filter { it.second >= MIN_ALTERNATIVE_SCORE }
            .maxByOrNull { it.second }?.first
}

/** Final clean-up of OCR lines coming from several models/passes. */
object OcrLines {
    /** True when the centre of [box] lies in one of [zones] (input fields, system bars). */
    fun inZones(box: Box, zones: List<Box>): Boolean = zones.any { it.containsPoint(box.centerX, box.centerY) }

    /**
     * Overlapping readings of the same region by different models or passes: the best reading wins.
     * Score = confidence plus a small bonus for completeness, so a whole line ("יש הצעות שחייבים
     * לקחת", 0.89) beats a confident fragment of it ("יש הצ", 0.93), while a banner garbled into one
     * line (0.65) still loses against its real line (0.92).
     */
    fun score(line: TextLine): Double =
        line.confidence + minOf(MAX_LENGTH_BONUS, LETTER_BONUS * line.text.count { it.isLetter() })

    fun dedupe(lines: List<TextLine>): List<TextLine> {
        val kept = ArrayList<TextLine>()
        for (line in lines.sortedByDescending(::score)) {
            if (kept.none { overlaps(it.box, line.box) }) kept += line
        }
        return kept
    }

    fun overlaps(a: Box, b: Box): Boolean {
        val inter = a.intersectionArea(b)
        return inter > 0 && inter * 2 > minOf(a.area, b.area)
    }

    private const val LETTER_BONUS = 0.005
    private const val MAX_LENGTH_BONUS = 0.15
}

/** Fuzzy comparison of an OCR reading with accessibility text (OCR makes small mistakes). */
object TextMatch {
    private val nonWord = Regex("[^\\p{L}\\p{N}]+")

    fun words(text: String): List<String> =
        text.lowercase().split(nonWord).filter { it.isNotEmpty() }

    /** Share of the words of [ocr] found in [reference]; 0 when [ocr] has no words. */
    fun coverage(ocr: String, reference: String): Double {
        val ocrWords = words(ocr)
        if (ocrWords.isEmpty()) return 0.0
        val ref = words(reference).toHashSet()
        return ocrWords.count { it in ref }.toDouble() / ocrWords.size
    }

    /** True when [ocr] reads the same text as [reference] (≥ 40 % of its words found). */
    fun matches(ocr: String, reference: String): Boolean = coverage(ocr, reference) >= 0.4
}

/** Keeps the letter case style of the source: an ALL-CAPS sign stays ALL-CAPS once translated. */
object CaseStyle {
    fun isAllCaps(text: String): Boolean {
        val letters = text.filter { it.isLetter() && (it.isUpperCase() || it.isLowerCase()) }
        return letters.length >= 2 && letters.all { it.isUpperCase() }
    }

    fun apply(source: String, translation: String, locale: java.util.Locale): String =
        if (isAllCaps(source)) translation.uppercase(locale) else translation
}

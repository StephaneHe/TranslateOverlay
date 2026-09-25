package com.translateoverlay.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.translateoverlay.core.Argb
import com.translateoverlay.core.BlockSource
import com.translateoverlay.core.Box
import com.translateoverlay.core.ColorEstimator
import com.translateoverlay.core.OcrLines
import com.translateoverlay.core.OcrSelection
import com.translateoverlay.core.OcrText
import com.translateoverlay.core.Script
import com.translateoverlay.core.TextBlock
import com.translateoverlay.core.TextLine
import com.translateoverlay.pipeline.Diag
import com.translateoverlay.settings.OcrScript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/** OCR blocks plus the scripts the models that ran can read (text in other scripts is unreliable). */
data class OcrResult(val blocks: List<TextBlock>, val covers: Set<Script>)

/**
 * On-device OCR on a screenshot; finds text drawn inside images, videos, canvases… ML Kit for its
 * scripts, Tesseract for Hebrew (not supported by ML Kit). In [OcrScript.AUTO] mode the models are
 * chosen from what is on screen, so the user never has to pick an alphabet.
 */
class OcrRecognizer(context: Context) {
    private val clients = HashMap<OcrScript, TextRecognizer>()
    private val tesseract = TesseractOcr(context.applicationContext)
    private val tesseractLock = Mutex()

    private fun client(script: OcrScript): TextRecognizer = clients.getOrPut(script) {
        when (script) {
            OcrScript.AUTO, OcrScript.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            OcrScript.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            OcrScript.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            OcrScript.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            OcrScript.DEVANAGARI -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
            OcrScript.HEBREW -> error("Hebrew is handled by Tesseract")
        }
    }

    /**
     * @param hints non-Latin scripts seen in the accessibility text of the screen (AUTO mode only)
     * @param ignoreZones input fields and system bars: not worth reading (nor re-reading)
     */
    suspend fun recognize(
        bitmap: Bitmap,
        mode: OcrScript,
        hints: Set<Script> = emptySet(),
        ignoreZones: List<Box> = emptyList(),
    ): OcrResult {
        if (mode != OcrScript.AUTO) {
            val blocks = run(bitmap, mode).mapNotNull { b -> b.keepLines { it.confidence >= MANUAL_MIN_CONFIDENCE } }
            return OcrResult(blocks, mode.covers)
        }
        return recognizeAuto(bitmap, hints, ignoreZones)
    }

    /**
     * Latin model on the whole screen, then per line: a line the Latin model doubts is re-read with
     * the model of the other script(s) on screen (hints from the accessibility text, or discovered
     * by trying every model), and the most plausible reading wins. Full-screen passes of those
     * models add the lines the Latin model did not even detect.
     */
    private suspend fun recognizeAuto(bitmap: Bitmap, hints: Set<Script>, ignoreZones: List<Box>): OcrResult {
        val covers = mutableSetOf(Script.LATIN)
        val screen = Box(0, 0, bitmap.width, bitmap.height)
        val latin = run(bitmap, OcrScript.LATIN)
        val latinLines = latin.flatMap { it.lines }.filterNot { OcrLines.inZones(it.box, ignoreZones) }
        val otherScripts = hints.filter { modelFor(it) != null }.toMutableSet()

        val fullBlocks = ArrayList<Pair<Script, TextBlock>>()
        for (script in otherScripts) fullBlocks += fullScreenPass(bitmap, script, covers).map { script to it }
        fun fullLines() = fullBlocks.flatMap { (script, b) -> b.lines.map { script to it } }

        val keptLatin = HashSet<TextLine>()
        val keptFull = HashSet<TextLine>()
        val extra = ArrayList<TextBlock>()
        var rereads = 0
        var unresolved = 0
        for (line in latinLines) {
            val overlapping = fullLines().filter { overlaps(it.second.box, line.box) }
            if (overlapping.isNotEmpty()) {
                if (overlapping.any { (script, alt) -> OcrSelection.preferAlternative(line, alt, script) }) {
                    keptFull += overlapping.map { it.second }
                } else if (!OcrSelection.isSuspicious(line)) {
                    keptLatin += line
                }
                continue
            }
            // Without any hint, trying every model on every doubtful line is costly: give up after a
            // few failures (the Hebrew full-screen fallback below still runs).
            val blindLimitReached = otherScripts.isEmpty() && rereads >= MAX_BLIND_REREADS
            val reread = OcrSelection.shouldReread(line, otherScripts.isNotEmpty()) &&
                OcrSelection.isWorthRereading(line.box, line.text) && rereads < MAX_REREADS && !blindLimitReached
            if (!reread) {
                if (!OcrSelection.isSuspicious(line)) keptLatin += line
                if (blindLimitReached && OcrSelection.isWorthRereading(line.box, line.text) && OcrSelection.isSuspicious(line)) unresolved++
                continue
            }
            rereads++
            val scripts = if (otherScripts.isNotEmpty()) otherScripts.toList() else ALTERNATIVE_SCRIPTS
            val alternatives = scripts.mapNotNull { script ->
                modelFor(script)?.let { model -> script to rereadRegion(bitmap, line, model, screen) }
            }
            val chosen = OcrSelection.best(alternatives)?.let { alternatives[it] }?.takeIf { (script, lines) ->
                lines.any { OcrSelection.preferAlternative(line, it, script) }
            }
            Diag.log {
                "auto OCR reread conf=%.2f \"%s\" -> ".format(line.confidence, line.text.take(40)) +
                    alternatives.joinToString { (sc, l) ->
                        "$sc=%.2f \"%s\"".format(OcrSelection.score(l, sc), l.joinToString(" ") { it.text }.take(40))
                    } + " chosen=${chosen?.first}"
            }
            when {
                chosen != null -> {
                    val (script, lines) = chosen
                    val good = lines.filter { OcrSelection.isPlausibleLine(it.text, script) }
                    extra += TextBlock(OcrText.joinLines(good.map { it.text }), good.map { it.box }.reduce(Box::union), BlockSource.OCR, good)
                    covers += modelFor(script)!!.covers
                    if (script !in otherScripts) {
                        // The Latin model may not even have detected the other lines of that script.
                        otherScripts += script
                        fullBlocks += fullScreenPass(bitmap, script, covers).map { script to it }
                    }
                }
                // No other script on screen: an unusual Latin font; keep it unless hopeless.
                otherScripts.isEmpty() && line.confidence >= MANUAL_MIN_CONFIDENCE -> { keptLatin += line; unresolved++ }
                !OcrSelection.isSuspicious(line) -> { keptLatin += line; unresolved++ }
                else -> unresolved++
            }
        }
        // Several doubtful line-shaped regions that no crop re-read resolved: Hebrew display text on a
        // busy image, which Tesseract only reads with its own page layout (full-screen pass).
        if (Script.HEBREW !in otherScripts && unresolved >= MIN_UNRESOLVED_FOR_HEBREW_PASS) {
            val found = fullScreenPass(bitmap, Script.HEBREW, covers)
            if (found.isNotEmpty()) {
                otherScripts += Script.HEBREW
                fullBlocks += found.map { Script.HEBREW to it }
            }
        }
        // Another script is on screen: middling Latin readings are its glyphs read as Latin garbage
        // ("DpU 2.10 NT!", "IAU 30-27" on ynet banners), not Latin text.
        if (otherScripts.isNotEmpty()) keptLatin.removeAll { OcrSelection.isSuspiciousWith(it, true) }
        // Other-script lines not contradicted by a Latin reading that was kept (overlaps between
        // passes and re-reads are settled by confidence below).
        fullLines().forEach { (_, l) -> if (keptLatin.none { overlaps(it.box, l.box) }) keptFull += l }

        val candidates = latin.mapNotNull { b -> b.keepLines { it in keptLatin } } +
            fullBlocks.mapNotNull { (_, b) -> b.keepLines { it in keptFull } } + extra
        val finalLines = OcrLines.dedupe(candidates.flatMap { it.lines }).toHashSet()
        // remove(): an identical line read by two passes is emitted once.
        val out = candidates.mapNotNull { b -> b.keepLines { finalLines.remove(it) } }
        Diag.log { "auto OCR: ${keptLatin.size} latin + ${keptFull.size} full-pass + ${extra.size} re-read lines, $rereads rereads" }
        return OcrResult(out, covers)
    }

    /** Whole-screen reading with the model of [script], keeping confident lines in that script. */
    private suspend fun fullScreenPass(bitmap: Bitmap, script: Script, covers: MutableSet<Script>): List<TextBlock> {
        val model = modelFor(script) ?: return emptyList()
        val start = SystemClock.uptimeMillis()
        val raw = ArrayList<TextBlock>()
        if (model == OcrScript.HEBREW) {
            for ((scaleDown, sparse) in FULL_PASS_VARIANTS) {
                val t0 = SystemClock.uptimeMillis()
                val scaled = if (scaleDown == 1) bitmap else Bitmap.createScaledBitmap(bitmap, bitmap.width / scaleDown, bitmap.height / scaleDown, true)
                val got = runCatching {
                    tesseractLock.withLock { withContext(Dispatchers.Default) { tesseract.recognize(scaled, sparse = sparse) } }
                }.getOrElse { emptyList() }.map { it.scaledUp(scaleDown) }
                if (scaled !== bitmap) scaled.recycle()
                val ok = got.flatMap { it.lines }.filter { it.confidence >= OcrSelection.MIN_ALTERNATIVE_CONFIDENCE && OcrSelection.isPlausibleLine(it.text, script) }
                Diag.log { "  variant 1/$scaleDown sparse=$sparse: ${ok.size} lines in ${SystemClock.uptimeMillis() - t0} ms: " + ok.joinToString(" | ") { "%.2f %s".format(it.confidence, it.text.take(30)) } }
                raw += got
            }
        } else {
            raw += runCatching { run(bitmap, model) }.getOrElse { emptyList() }
        }
        val blocks = raw.mapNotNull { b ->
            b.keepLines { it.confidence >= OcrSelection.MIN_ALTERNATIVE_CONFIDENCE && OcrSelection.isPlausibleLine(it.text, script) }
        }
        Diag.log { "auto OCR full pass $script: ${blocks.sumOf { it.lines.size }} lines in ${SystemClock.uptimeMillis() - start} ms" }
        covers += model.covers
        return blocks
    }

    /** Re-reads the region of [line] with [model]; crops are prepared for Tesseract (invert, upscale). */
    private suspend fun rereadRegion(bitmap: Bitmap, line: TextLine, model: OcrScript, screen: Box): List<TextLine> {
        val crop = line.box.padded(line.box.height / 3, screen)
        if (crop.isEmpty) return emptyList()
        val raw = Bitmap.createBitmap(bitmap, crop.left, crop.top, crop.width, crop.height)
        val tess = model == OcrScript.HEBREW
        val scale = if (tess) OcrSelection.tesseractScale(line.box.height) else 1
        val prepared = if (tess) prepareForTesseract(raw, scale) else raw
        val lines = runCatching {
            if (tess) {
                tesseractLock.withLock { withContext(Dispatchers.Default) { tesseract.recognize(prepared, singleBlock = true) } }
            } else {
                run(prepared, model)
            }
        }.getOrElse { emptyList() }.flatMap { it.lines }.map { l ->
            val b = l.box
            l.copy(box = Box(crop.left + b.left / scale, crop.top + b.top / scale, crop.left + b.right / scale, crop.top + b.bottom / scale))
        }
        if (prepared !== raw) prepared.recycle()
        raw.recycle()
        return lines
    }

    /** Light-on-dark crops are inverted and small ones upscaled: what Tesseract reads best. */
    private fun prepareForTesseract(src: Bitmap, scale: Int): Bitmap {
        val w = src.width
        val h = src.height
        val border = IntArray(2 * w + 2 * h)
        src.getPixels(border, 0, w, 0, 0, w, 1)
        src.getPixels(border, w, w, 0, h - 1, w, 1)
        for (y in 0 until h) {
            border[2 * w + y] = src.getPixel(0, y)
            border[2 * w + h + y] = src.getPixel(w - 1, y)
        }
        val background = ColorEstimator.medianColor(border) ?: return src
        val invert = OcrSelection.shouldInvert(Argb.luminance(background))
        if (!invert && scale == 1) return src
        val out = Bitmap.createBitmap(w * scale, h * scale, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        if (invert) {
            paint.colorFilter = ColorMatrixColorFilter(ColorMatrix(INVERT))
        }
        Canvas(out).drawBitmap(src, null, Rect(0, 0, out.width, out.height), paint)
        return out
    }

    private fun invert(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix(INVERT)) }
        Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }

    private suspend fun run(bitmap: Bitmap, model: OcrScript): List<TextBlock> {
        if (model == OcrScript.HEBREW) {
            return tesseractLock.withLock { withContext(Dispatchers.Default) { tesseract.recognize(bitmap) } }
        }
        val result = client(model).process(InputImage.fromBitmap(bitmap, 0)).await()
        return result.textBlocks.mapNotNull { block ->
            val box = block.boundingBox?.toBox() ?: return@mapNotNull null
            val lines = block.lines.mapNotNull { line ->
                line.boundingBox?.let { TextLine(line.text, it.toBox(), line.confidence) }
            }
            val text = if (lines.isEmpty()) block.text else OcrText.joinLines(lines.map { it.text })
            TextBlock(text, box, BlockSource.OCR, lines)
        }
    }

    fun close() {
        clients.values.forEach { it.close() }
        tesseract.close()
    }

    private companion object {
        /** Manual modes: lines ML Kit itself doubts are usually glyphs of a script it cannot read. */
        const val MANUAL_MIN_CONFIDENCE = 0.3f
        const val MAX_REREADS = 12
        const val MIN_UNRESOLVED_FOR_HEBREW_PASS = 2
        const val MAX_BLIND_REREADS = 2

        /**
         * Hebrew full-screen passes: (downscale factor, sparse text mode). Measured on 5 real ynet
         * banners: sparse mode finds text on busy images that page layout analysis misses, and
         * downscaled passes read display fonts (100+ px) that full resolution misses. ~1.3 s total.
         */
        val FULL_PASS_VARIANTS = listOf(1 to true, 2 to true, 4 to true)

        val INVERT = floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        )

        /** Scripts tried on doubtful regions when no other script is known on screen. */
        val ALTERNATIVE_SCRIPTS = listOf(Script.HEBREW, Script.HAN, Script.JAPANESE, Script.KOREAN, Script.DEVANAGARI)

        fun modelFor(script: Script): OcrScript? = when (script) {
            Script.HEBREW -> OcrScript.HEBREW
            Script.HAN -> OcrScript.CHINESE
            Script.JAPANESE -> OcrScript.JAPANESE
            Script.KOREAN -> OcrScript.KOREAN
            Script.DEVANAGARI -> OcrScript.DEVANAGARI
            else -> null
        }

        fun overlaps(a: Box, b: Box): Boolean {
            val inter = a.intersectionArea(b)
            return inter > 0 && inter * 2 > minOf(a.area, b.area)
        }
    }
}

/** Same block restricted to the lines matching [keep] (box and text rebuilt), or null if none is left. */
private fun TextBlock.keepLines(keep: (TextLine) -> Boolean): TextBlock? {
    if (lines.isEmpty()) return this
    val kept = lines.filter(keep)
    if (kept.isEmpty()) return null
    if (kept.size == lines.size) return this
    return TextBlock(OcrText.joinLines(kept.map { it.text }), kept.map { it.box }.reduce(Box::union), source, kept)
}

private fun TextBlock.scaledUp(f: Int): TextBlock = if (f == 1) this else TextBlock(
    text, box.times(f), source, lines.map { it.copy(box = it.box.times(f)) },
)

private fun Box.times(f: Int) = Box(left * f, top * f, right * f, bottom * f)

private fun Box.padded(pad: Int, bounds: Box) = Box(left - pad, top - pad, right + pad, bottom + pad).intersect(bounds)

fun Rect.toBox() = Box(left, top, right, bottom)

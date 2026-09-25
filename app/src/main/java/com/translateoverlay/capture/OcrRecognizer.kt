package com.translateoverlay.capture

import android.content.Context
import android.graphics.Bitmap
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
import com.translateoverlay.core.BlockSource
import com.translateoverlay.core.Box
import com.translateoverlay.core.OcrSelection
import com.translateoverlay.core.OcrText
import com.translateoverlay.core.Script
import com.translateoverlay.core.ScriptDetector
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

    /** @param hints non-Latin scripts seen in the accessibility text of the screen (AUTO mode only) */
    suspend fun recognize(bitmap: Bitmap, mode: OcrScript, hints: Set<Script> = emptySet()): OcrResult {
        if (mode != OcrScript.AUTO) {
            val blocks = run(bitmap, mode).mapNotNull { b -> b.keepLines { it.confidence >= MANUAL_MIN_CONFIDENCE } }
            return OcrResult(blocks, mode.covers)
        }
        return recognizeAuto(bitmap, hints)
    }

    private suspend fun recognizeAuto(bitmap: Bitmap, hints: Set<Script>): OcrResult {
        val covers = mutableSetOf(Script.LATIN)
        val latin = run(bitmap, OcrScript.LATIN)
        val out = latin.mapNotNull { b -> b.keepLines { !OcrSelection.isSuspicious(it) } }.toMutableList()

        // 1. Scripts present in the screen's own text: read the whole screen with their model too.
        // Regions the Latin model read confidently are Latin: other models must not re-read them.
        val confidentLatin = latin.flatMap { it.lines }.filterNot(OcrSelection::isSuspicious).map { it.box }
        for (script in hints) out += fullScreenPass(bitmap, script, covers, confidentLatin)

        // 2. Latin lines the Latin model doubts are usually another script: re-read just those regions.
        val screen = Box(0, 0, bitmap.width, bitmap.height)
        val suspicious = latin.mapNotNull { it.keepLines(OcrSelection::isSuspicious) }
            .filter { OcrSelection.isWorthRereading(it.box, it.text) }
            .filter { s -> out.none { it.box.intersectionArea(s.box) * 2 > s.box.area } }
            .take(MAX_SUSPICIOUS_REGIONS)
        val discovered = mutableSetOf<Script>()
        for (region in suspicious) {
            if (out.any { it.box.intersectionArea(region.box) * 2 > region.box.area }) continue // read by a full pass
            val crop = region.box.padded(region.lines.maxOf { it.box.height } / 3, screen)
            if (crop.isEmpty) continue
            val cropBitmap = Bitmap.createBitmap(bitmap, crop.left, crop.top, crop.width, crop.height)
            val alternatives = ArrayList<Pair<Script, List<TextLine>>>()
            val models = ArrayList<OcrScript>()
            for (script in ALTERNATIVE_SCRIPTS) {
                val model = modelFor(script) ?: continue
                val lines = runCatching { run(cropBitmap, model) }.getOrElse { emptyList() }
                    .flatMap { it.lines }.map { it.copy(box = it.box.offset(crop.left, crop.top)) }
                alternatives += script to lines
                models += model
                if (OcrSelection.score(lines, script) >= GOOD_ENOUGH) break
            }
            cropBitmap.recycle()
            val best = OcrSelection.best(alternatives)
            Diag.log {
                "auto OCR suspicious ${Diag.describe(region)} -> " + alternatives.joinToString { (s, l) ->
                    "$s=%.2f \"%s\"".format(OcrSelection.score(l, s), l.joinToString(" ") { it.text }.take(40))
                } + " chosen=${best?.let { alternatives[it].first }}"
            }
            if (best != null) {
                val script = alternatives[best].first
                if (script !in discovered && script !in hints) {
                    // The Latin model may not even have detected the other lines of that script
                    // (2 of 3 Hebrew lines on device): read the whole screen with the right model.
                    discovered += script
                    out += fullScreenPass(bitmap, script, covers, confidentLatin)
                }
                if (out.none { it.box.intersectionArea(region.box) * 2 > region.box.area }) {
                    val lines = alternatives[best].second
                    out += TextBlock(OcrText.joinLines(lines.map { it.text }), lines.map { it.box }.reduce(Box::union), BlockSource.OCR, lines)
                }
                covers += models[best].covers
            } else {
                // Unusual Latin font rather than another script: keep the reading unless it is hopeless.
                region.keepLines { it.confidence >= MANUAL_MIN_CONFIDENCE }?.let { out += it }
            }
        }
        return OcrResult(out, covers)
    }

    /** Whole-screen reading with the model of [script], keeping only confident lines in that script. */
    private suspend fun fullScreenPass(
        bitmap: Bitmap,
        script: Script,
        covers: MutableSet<Script>,
        confidentLatin: List<Box>,
    ): List<TextBlock> {
        val model = modelFor(script) ?: return emptyList()
        val start = SystemClock.uptimeMillis()
        val blocks = runCatching { run(bitmap, model) }.getOrElse { emptyList() }.mapNotNull { b ->
            b.keepLines { line ->
                line.confidence >= OcrSelection.SUSPICIOUS_BELOW && OcrSelection.isPlausibleLine(line.text, script) &&
                    confidentLatin.none { it.intersectionArea(line.box) * 2 > line.box.area }
            }
        }
        Diag.log { "auto OCR full pass $script via $model: ${blocks.size} blocks in ${SystemClock.uptimeMillis() - start} ms" }
        covers += model.covers
        return blocks
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
        const val MAX_SUSPICIOUS_REGIONS = 4
        const val GOOD_ENOUGH = 0.8

        /** Scripts tried on suspicious regions, most likely first. */
        val ALTERNATIVE_SCRIPTS = listOf(Script.HEBREW, Script.HAN, Script.JAPANESE, Script.KOREAN, Script.DEVANAGARI)

        fun modelFor(script: Script): OcrScript? = when (script) {
            Script.HEBREW -> OcrScript.HEBREW
            Script.HAN -> OcrScript.CHINESE
            Script.JAPANESE -> OcrScript.JAPANESE
            Script.KOREAN -> OcrScript.KOREAN
            Script.DEVANAGARI -> OcrScript.DEVANAGARI
            else -> null
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

private fun Box.padded(pad: Int, bounds: Box) = Box(left - pad, top - pad, right + pad, bottom + pad).intersect(bounds)

private fun Box.offset(dx: Int, dy: Int) = Box(left + dx, top + dy, right + dx, bottom + dy)

fun Rect.toBox() = Box(left, top, right, bottom)

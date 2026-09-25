package com.translateoverlay.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
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
import com.translateoverlay.core.OcrText
import com.translateoverlay.core.TextBlock
import com.translateoverlay.core.TextLine
import com.translateoverlay.settings.OcrScript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * On-device OCR on a screenshot; finds text drawn inside images, videos, canvases… ML Kit for its
 * scripts, Tesseract for Hebrew (not supported by ML Kit).
 */
class OcrRecognizer(context: Context) {
    private val clients = HashMap<OcrScript, TextRecognizer>()
    private val tesseract = TesseractOcr(context.applicationContext)
    private val tesseractLock = Mutex()

    private fun client(script: OcrScript): TextRecognizer = clients.getOrPut(script) {
        when (script) {
            OcrScript.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            OcrScript.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            OcrScript.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            OcrScript.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            OcrScript.DEVANAGARI -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
            OcrScript.HEBREW -> error("Hebrew is handled by Tesseract")
        }
    }

    suspend fun recognize(bitmap: Bitmap, script: OcrScript): List<TextBlock> {
        if (script == OcrScript.HEBREW) {
            return tesseractLock.withLock { withContext(Dispatchers.Default) { tesseract.recognize(bitmap) } }
        }
        val result = client(script).process(InputImage.fromBitmap(bitmap, 0)).await()
        return result.textBlocks.mapNotNull { block ->
            val box = block.boundingBox?.toBox() ?: return@mapNotNull null
            val lines = block.lines.filter { it.confidence >= MIN_LINE_CONFIDENCE }.mapNotNull { line ->
                line.boundingBox?.let { TextLine(line.text, it.toBox()) }
            }
            if (block.lines.isNotEmpty() && lines.isEmpty()) return@mapNotNull null
            val text = if (lines.isEmpty()) block.text else OcrText.joinLines(lines.map { it.text })
            TextBlock(text, lines.map { it.box }.reduceOrNull(Box::union) ?: box, BlockSource.OCR, lines)
        }
    }

    fun close() {
        clients.values.forEach { it.close() }
        tesseract.close()
    }

    private companion object {
        /** Lines ML Kit itself doubts are usually glyphs of a script it cannot read. */
        const val MIN_LINE_CONFIDENCE = 0.3f
    }
}

fun Rect.toBox() = Box(left, top, right, bottom)

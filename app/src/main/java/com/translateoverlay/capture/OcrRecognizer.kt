package com.translateoverlay.capture

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
import kotlinx.coroutines.tasks.await

/** ML Kit on-device OCR on a screenshot; finds text drawn inside images, videos, canvases… */
class OcrRecognizer {
    private val clients = HashMap<OcrScript, TextRecognizer>()

    private fun client(script: OcrScript): TextRecognizer = clients.getOrPut(script) {
        when (script) {
            OcrScript.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            OcrScript.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            OcrScript.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            OcrScript.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            OcrScript.DEVANAGARI -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        }
    }

    suspend fun recognize(bitmap: Bitmap, script: OcrScript): List<TextBlock> {
        val result = client(script).process(InputImage.fromBitmap(bitmap, 0)).await()
        return result.textBlocks.mapNotNull { block ->
            val box = block.boundingBox?.toBox() ?: return@mapNotNull null
            val lines = block.lines.mapNotNull { line ->
                line.boundingBox?.let { TextLine(line.text, it.toBox()) }
            }
            val text = if (lines.isEmpty()) block.text else OcrText.joinLines(lines.map { it.text })
            TextBlock(text, box, BlockSource.OCR, lines)
        }
    }

    fun close() = clients.values.forEach { it.close() }
}

fun Rect.toBox() = Box(left, top, right, bottom)

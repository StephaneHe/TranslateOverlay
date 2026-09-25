package com.translateoverlay.capture

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import com.translateoverlay.core.BlockSource
import com.translateoverlay.core.OcrText
import com.translateoverlay.core.TextBlock
import com.translateoverlay.core.TextLine
import java.io.File

/**
 * On-device Hebrew OCR with Tesseract (LSTM, tessdata_fast "heb", bundled in assets): ML Kit has
 * no Hebrew recognizer. Not thread-safe: calls must be serialised by the caller.
 */
class TesseractOcr(private val context: Context, private val language: String = "heb") {
    private var api: TessBaseAPI? = null

    private fun api(): TessBaseAPI = api ?: run {
        // Tesseract expects <dataPath>/tessdata/<lang>.traineddata on the file system.
        val dataPath = File(context.filesDir, DATA_DIR)
        val file = File(dataPath, "tessdata/$language.traineddata")
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            context.assets.open("tessdata/$language.traineddata").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
        }
        TessBaseAPI().also {
            check(it.init(dataPath.absolutePath, language, TessBaseAPI.OEM_LSTM_ONLY)) { "Tesseract init failed" }
            api = it
        }
    }

    /**
     * Blocking; run off the main thread.
     * @param singleBlock true for a crop around one text region (no page layout analysis)
     */
    fun recognize(bitmap: Bitmap, singleBlock: Boolean = false, sparse: Boolean = false): List<TextBlock> {
        val tess = api()
        tess.setPageSegMode(
            when {
                singleBlock -> TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK
                sparse -> TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT
                else -> TessBaseAPI.PageSegMode.PSM_AUTO
            },
        )
        tess.setImage(bitmap)
        tess.getUTF8Text() // runs recognition; results are then read through the iterator
        val iterator = tess.resultIterator ?: return emptyList()
        val blocks = ArrayList<TextBlock>()
        val lines = ArrayList<TextLine>()

        fun flush() {
            if (lines.isEmpty()) return
            val box = lines.map { it.box }.reduce { a, b -> a.union(b) }
            blocks += TextBlock(OcrText.joinLines(lines.map { it.text }), box, BlockSource.OCR, lines.toList())
            lines.clear()
        }

        try {
            iterator.begin()
            do {
                if (iterator.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_PARA)) flush()
                val text = iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)?.trim()
                val confidence = iterator.confidence(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)
                val rect = iterator.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)
                if (!text.isNullOrEmpty() && confidence >= MIN_CONFIDENCE && rect != null) {
                    lines += TextLine(text, rect.toBox(), confidence / 100f)
                }
            } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE))
            flush()
        } finally {
            iterator.delete()
        }
        return blocks
    }

    fun close() {
        api?.recycle()
        api = null
    }

    private companion object {
        /** Tesseract line confidence (0–100); below this, lines are mostly misread icons/images. */
        const val MIN_CONFIDENCE = 60f

        /** Versioned: bump when the bundled traineddata changes, so the new file is extracted. */
        const val DATA_DIR = "tesseract-v2"
    }
}

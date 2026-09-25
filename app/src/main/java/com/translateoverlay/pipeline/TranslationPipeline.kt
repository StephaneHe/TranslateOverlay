package com.translateoverlay.pipeline

import android.graphics.Bitmap
import com.translateoverlay.capture.OcrRecognizer
import com.translateoverlay.core.BlockMerger
import com.translateoverlay.core.LanguageTags
import com.translateoverlay.core.LanguageVoter
import com.translateoverlay.core.TextBlock
import com.translateoverlay.core.TranslatableFilter
import com.translateoverlay.core.TranslatedBlock
import com.translateoverlay.core.UNDETERMINED
import com.translateoverlay.settings.OcrScript
import com.translateoverlay.settings.Settings
import com.translateoverlay.translate.TranslationEngine
import kotlinx.coroutines.withTimeout

sealed interface PipelineOutcome {
    data class Success(val blocks: List<TranslatedBlock>, val sourceLanguages: Set<String>) : PipelineOutcome
    data class NoResult(val message: String) : PipelineOutcome
    data class Failure(val message: String) : PipelineOutcome
}

/** capture results → merge → language detection → model check → translation → style. */
class TranslationPipeline(
    private val engine: TranslationEngine,
    private val ocr: OcrRecognizer,
    private val styles: StyleEstimator,
) {
    suspend fun run(
        nodes: List<TextBlock>,
        screenshot: Bitmap?,
        settings: Settings,
        onProgress: (String) -> Unit,
    ): PipelineOutcome {
        val target = settings.targetLanguage
        val ocrBlocks = if (screenshot != null) runCatching { ocr.recognize(screenshot, settings.ocrScript) }
            .getOrElse { emptyList() } else emptyList()

        // Only the Latin OCR model can vouch for Latin text being actually drawn on screen.
        val confirmWithOcr = ocrBlocks.isNotEmpty() && settings.ocrScript == OcrScript.LATIN
        val blocks = BlockMerger.merge(nodes, ocrBlocks, confirmLatinNodesWithOcr = confirmWithOcr)
            .filter { TranslatableFilter.isTranslatable(it.text) }
        if (blocks.isEmpty()) return PipelineOutcome.NoResult("Aucun texte détecté à l'écran")

        val detected = blocks.map { runCatching { engine.identify(it.text) }.getOrDefault(UNDETERMINED) }
        val lengths = blocks.map { it.text.length }
        val dominant = LanguageVoter.dominantOfBlocks(detected.zip(lengths))
        val languages = detected.mapIndexed { i, lang -> LanguageVoter.resolve(lang, dominant, lengths[i]) }

        val work = blocks.indices.filter { i ->
            val lang = languages[i]
            lang != UNDETERMINED && !LanguageTags.sameLanguage(lang, target) && engine.isSupported(lang)
        }
        if (work.isEmpty()) {
            return PipelineOutcome.NoResult(
                if (dominant != null && LanguageTags.sameLanguage(dominant, target)) {
                    "Le texte est déjà en ${TranslationEngine.displayName(target)}"
                } else {
                    "Langue du texte non reconnue ou non prise en charge"
                },
            )
        }

        val sources = work.map { languages[it] }.toSet()
        val missing = (sources + target) - engine.downloadedModels()
        if (missing.isNotEmpty()) {
            val names = missing.joinToString { TranslationEngine.displayName(it) }
            if (!engine.canDownloadNow(settings.wifiOnlyDownloads)) {
                return PipelineOutcome.Failure(
                    "Modèle(s) de traduction manquant(s) : $names. " +
                        if (settings.wifiOnlyDownloads) "Connectez-vous au Wi-Fi ou autorisez les données mobiles dans les paramètres."
                        else "Aucune connexion Internet.",
                )
            }
            onProgress("Téléchargement du modèle : $names…")
            for (code in missing) {
                withTimeout(DOWNLOAD_TIMEOUT_MS) { engine.download(code, settings.wifiOnlyDownloads) }
            }
        }

        val result = work.mapNotNull { i ->
            val block = blocks[i]
            val lang = languages[i]
            val translation = runCatching { engine.translate(lang, target, block.text) }.getOrNull()
                ?: return@mapNotNull null
            if (translation.isBlank() || translation.trim() == block.text.trim()) return@mapNotNull null
            TranslatedBlock(block, styles.estimate(block, screenshot), lang, translation)
        }
        if (result.isEmpty()) return PipelineOutcome.Failure("La traduction a échoué")
        return PipelineOutcome.Success(result, result.map { it.sourceLanguage }.toSet())
    }

    private companion object {
        const val DOWNLOAD_TIMEOUT_MS = 180_000L
    }
}

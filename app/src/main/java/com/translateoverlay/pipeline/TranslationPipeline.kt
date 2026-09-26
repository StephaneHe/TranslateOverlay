package com.translateoverlay.pipeline

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.translateoverlay.capture.OcrRecognizer
import com.translateoverlay.core.BlockMerger
import com.translateoverlay.core.BlockSource
import com.translateoverlay.core.Box
import com.translateoverlay.core.OcrLines
import com.translateoverlay.core.RefineItem
import com.translateoverlay.core.CaseStyle
import com.translateoverlay.core.LanguageScripts
import com.translateoverlay.core.LanguageTags
import com.translateoverlay.core.LanguageVoter
import com.translateoverlay.core.Script
import com.translateoverlay.core.ScriptDetector
import com.translateoverlay.core.TextBlock
import com.translateoverlay.core.TranslatableFilter
import com.translateoverlay.core.TranslatedBlock
import com.translateoverlay.core.UNDETERMINED
import com.translateoverlay.settings.Settings
import com.translateoverlay.translate.TranslationEngine
import com.translateoverlay.translate.TranslatorRouter
import com.translateoverlay.translate.shortLabel
import kotlinx.coroutines.withTimeout
import java.util.Locale

sealed interface PipelineOutcome {
    /**
     * @property engine label of the engine that translated; [notice] explains a fallback.
     * @property refinement blocks to improve with the online engine once the overlay is shown.
     * @property improved overlay index → online engine label for blocks taken from its cache.
     */
    data class Success(
        val blocks: List<TranslatedBlock>,
        val sourceLanguages: Set<String>,
        val engine: String = "ML Kit",
        val notice: String? = null,
        val refinement: List<RefineItem>? = null,
        val improved: Map<Int, String> = emptyMap(),
        val modelsToDownload: Set<String> = emptySet(),
    ) : PipelineOutcome
    data class NoResult(val message: String) : PipelineOutcome
    data class Failure(val message: String) : PipelineOutcome
}

/** capture results → merge → language detection → model check → translation → style. */
class TranslationPipeline(
    private val engine: TranslationEngine,
    private val router: TranslatorRouter,
    private val ocr: OcrRecognizer,
    private val styles: StyleEstimator,
) {
    suspend fun run(
        nodes: List<TextBlock>,
        screenshot: Bitmap?,
        settings: Settings,
        ignoreZones: List<Box> = emptyList(),
        onProgress: (String) -> Unit,
    ): PipelineOutcome {
        val target = settings.targetLanguage
        val ocrStart = SystemClock.uptimeMillis()
        val hints = ScriptDetector.hints(nodes.map { it.text })
        val ocrResult = if (screenshot != null) runCatching { ocr.recognize(screenshot, settings.ocrScript, hints, ignoreZones) }
            .onFailure { Log.w(TAG, "OCR ${settings.ocrScript} failed", it) }
            .getOrNull() else null
        val ocrBlocks = ocrResult?.blocks.orEmpty().filter { b ->
            !OcrLines.inZones(b.box, ignoreZones).also { if (it) Diag.log { "ocr in ignore zone ${Diag.describe(b)}" } }
        }
        val ocrCovers = ocrResult?.covers ?: settings.ocrScript.covers
        if (screenshot != null) {
            Log.i(TAG, "OCR ${settings.ocrScript}: ${ocrBlocks.size} blocks in ${SystemClock.uptimeMillis() - ocrStart} ms")
        }
        Diag.log { "input: ${nodes.size} nodes, screenshot=${screenshot?.let { "${it.width}x${it.height}" }}, hints=$hints, ocr covers=$ocrCovers" }
        ocrBlocks.forEach { Diag.log { "ocr raw ${Diag.describe(it)}" } }

        // Only the Latin OCR model can vouch for Latin text being actually drawn on screen.
        val confirmWithOcr = ocrBlocks.isNotEmpty() && Script.LATIN in ocrCovers
        val merged = BlockMerger.merge(nodes, ocrBlocks, confirmLatinNodesWithOcr = confirmWithOcr)
        if (Diag.enabled) {
            ocrBlocks.filter { o -> merged.none { it === o } }.forEach { o ->
                val covering = merged.filter { it.source == BlockSource.NODE && it.box.intersectionArea(o.box) > 0 }
                Diag.log { "ocr absorbed ${Diag.describe(o)} by ${covering.map { Diag.describe(it) }}" }
            }
        }
        val blocks = merged.filter { b ->
            TranslatableFilter.isTranslatable(b.text).also { if (!it) Diag.log { "not translatable ${Diag.describe(b)}" } }
        }
        if (blocks.isEmpty()) return PipelineOutcome.NoResult("Aucun texte détecté à l'écran")

        val detected = blocks.map { runCatching { engine.identify(it.text) }.getOrDefault(UNDETERMINED) }
        val lengths = blocks.map { it.text.length }
        val dominant = LanguageVoter.dominantOfBlocks(detected.zip(lengths))
        // App UI (tree) and image text (OCR) often differ in language: a French gallery showing an
        // English meme. Short blocks follow the dominant language of their own source first.
        val dominantBySource = BlockSource.entries.associateWith { source ->
            LanguageVoter.dominantOfBlocks(blocks.indices.filter { blocks[it].source == source }.map { detected[it] to lengths[it] })
        }
        val short = blocks.indices.map { lengths[it] < LanguageVoter.MIN_RELIABLE_LENGTH }
        val plausibleInTarget = blocks.indices.map { i ->
            short[i] && runCatching { engine.plausibleLanguages(blocks[i].text) }
                .getOrDefault(emptySet())
                .also { set -> Diag.log { "candidates \"${blocks[i].text.take(20)}\" = $set" } }
                .any { LanguageTags.sameLanguage(it, target) }
        }
        // Only when the app's own long texts don't already reveal another language (English article).
        val nodeDominant = dominantBySource[BlockSource.NODE]
        val uiInTarget = (nodeDominant == null || LanguageTags.sameLanguage(nodeDominant, target)) && LanguageVoter.uiInTarget(
            blocks.indices.filter { short[it] && blocks[it].source == BlockSource.NODE }
                .map { plausibleInTarget[it] },
        )
        val languages = blocks.indices.map { i ->
            val targetPlausible = plausibleInTarget[i] ||
                (uiInTarget && short[i] && blocks[i].source == BlockSource.NODE && detected[i] == UNDETERMINED)
            LanguageVoter.resolve(detected[i], dominantBySource[blocks[i].source] ?: dominant, lengths[i], target, targetPlausible)
        }
        Diag.log { "uiInTarget=$uiInTarget plausible=${blocks.indices.filter { plausibleInTarget[it] }.map { blocks[it].text.take(20) }}" }

        // OCR of a script the model cannot read (e.g. Hebrew page, Latin OCR) is garbage: keep tree text only.
        val trustOcr = dominant == null || LanguageScripts.isReadableBy(dominant, ocrCovers)
        val work = blocks.indices.filter { i ->
            val lang = languages[i]
            val noise = blocks[i].source == BlockSource.OCR && LanguageVoter.isOcrNoise(detected[i], engine.isSupported(detected[i]))
            (!noise && (trustOcr || blocks[i].source != BlockSource.OCR) &&
                lang != UNDETERMINED && !LanguageTags.sameLanguage(lang, target) && engine.isSupported(lang))
                .also { Diag.log { "lang ${detected[i]}->$lang work=$it trustOcr=$trustOcr ${Diag.describe(blocks[i])}" } }
        }
        Diag.log { "dominant=$dominant target=$target work=${work.size}/${blocks.size}" }
        if (work.isEmpty()) {
            return PipelineOutcome.NoResult(
                if (dominant != null && LanguageTags.sameLanguage(dominant, target)) {
                    "Le texte est déjà en ${TranslationEngine.displayName(target)}"
                } else {
                    "Langue du texte non reconnue ou non prise en charge"
                },
            )
        }

        // Offline translation first (instant, shown at once); the online engine then improves the
        // overlay block by block (see OverlayAccessibilityService.refine). Online answers already
        // in the cache are used right away.
        val provider = settings.provider
        val online = provider.online && router.isReady(provider)
        val translated = HashMap<Int, String?>()
        val engineOf = HashMap<Int, String>()
        if (online) {
            work.forEach { i ->
                router.cached(provider, languages[i], target, blocks[i].text)?.let { (text, engineLabel) ->
                    translated[i] = text
                    engineOf[i] = engineLabel
                }
            }
        }
        val offlineWork = work.filter { it !in translated }
        val modelsMissing = offlineWork.isNotEmpty() &&
            (offlineWork.map { languages[it] }.toSet() + target - engine.downloadedModels()).isNotEmpty()
        if (!(online && modelsMissing)) {
            // Without an online engine, missing ML Kit models are downloaded (or reported) as before.
            if (modelsMissing) ensureMlKitModels(offlineWork.map { languages[it] }.toSet() + target, settings, onProgress)?.let { return it }
            for (i in offlineWork) translated[i] = runCatching { engine.translate(languages[i], target, blocks[i].text) }.getOrNull()
        } else {
            Diag.log { "ML Kit models missing: online translation only" }
        }

        val result = ArrayList<TranslatedBlock>()
        val pending = ArrayList<RefineItem>()
        val improved = HashMap<Int, String>()
        for (i in work) {
            val block = blocks[i]
            val lang = languages[i]
            val translation = translated[i]?.let { CaseStyle.apply(block.text, it, Locale.forLanguageTag(target)) }
            val usable = translation != null && translation.isNotBlank() && !translation.trim().equals(block.text.trim(), ignoreCase = true)
            val improvable = online && i !in engineOf
            if (!usable && !improvable) {
                Diag.log { "unchanged by translation ${Diag.describe(block)}" }
                continue
            }
            val style = styles.estimate(block, screenshot)
            // Garbage OCR lines still give line heights, but not a trustworthy alignment.
            // A block without offline translation stays invisible until the online one arrives.
            result += TranslatedBlock(block, if (trustOcr) style else style.copy(alignMeasured = false), lang, if (usable) translation!! else "")
            if (improvable) pending += RefineItem(result.lastIndex, block.text, lang, block.box.top, block.box.left)
            engineOf[i]?.let { improved[result.lastIndex] = it }
        }
        if (result.isEmpty()) return PipelineOutcome.Failure("La traduction a échoué")
        val cachedEngines = engineOf.values.toSet()
        // Missing key: said discreetly in the caption (no toast at every tap).
        val baseEngine = when {
            provider.online && !online -> "ML Kit (clé ${provider.shortLabel()} à saisir dans Paramètres)"
            online && modelsMissing -> "traduction en ligne"
            else -> "ML Kit"
        }
        return PipelineOutcome.Success(
            result, result.map { it.sourceLanguage }.toSet(),
            engine = if (pending.isEmpty() && cachedEngines.size == 1) cachedEngines.first() else baseEngine,
            refinement = pending.takeIf { it.isNotEmpty() },
            improved = improved,
            // Kept as the last resort when the online engines fail: fetched in the background.
            modelsToDownload = if (online && modelsMissing) offlineWork.map { languages[it] }.toSet() + target else emptySet(),
        )
    }

    /** Makes sure ML Kit models are present (downloading them if allowed); returns a failure otherwise. */
    private suspend fun ensureMlKitModels(codes: Set<String>, settings: Settings, onProgress: (String) -> Unit): PipelineOutcome? {
        val missing = codes - engine.downloadedModels()
        if (missing.isEmpty()) return null
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
        return null
    }

    private companion object {
        const val TAG = "TranslateOverlay"
        const val DOWNLOAD_TIMEOUT_MS = 180_000L
    }
}

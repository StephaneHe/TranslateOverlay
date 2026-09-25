package com.translateoverlay.translate

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.translateoverlay.core.LanguageTags
import com.translateoverlay.core.TranslationCache
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.text.Collator
import java.util.Locale

/**
 * On-device language identification and translation (ML Kit). No API key; translation models
 * (~30 MB each) are downloaded once per language and then work offline.
 */
class TranslationEngine(context: Context) {
    private val appContext = context.applicationContext
    private val languageId = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.5f).build(),
    )
    private val modelManager = RemoteModelManager.getInstance()
    private val translators = HashMap<Pair<String, String>, Translator>()
    private val cache = TranslationCache()
    private val mutex = Mutex()

    // Collator: accent-aware order ("Hébreu" between "Grec" and "Hindi", not after "Hongrois").
    val supportedLanguages: List<String> = TranslateLanguage.getAllLanguages()
        .sortedWith(compareBy(Collator.getInstance(Locale.getDefault())) { displayName(it) })

    fun isSupported(code: String): Boolean = TranslateLanguage.fromLanguageTag(code) != null

    /** Returns a normalised language code, or "und" when unknown. */
    suspend fun identify(text: String): String =
        LanguageTags.normalize(languageId.identifyLanguage(text).await())

    suspend fun downloadedModels(): Set<String> =
        modelManager.getDownloadedModels(TranslateRemoteModel::class.java).await()
            .map { it.language }.toSet()

    suspend fun download(code: String, wifiOnly: Boolean) {
        val conditions = DownloadConditions.Builder().apply { if (wifiOnly) requireWifi() }.build()
        modelManager.download(TranslateRemoteModel.Builder(code).build(), conditions).await()
    }

    suspend fun delete(code: String) {
        mutex.withLock {
            translators.entries.filter { it.key.first == code || it.key.second == code }.forEach {
                it.value.close()
                translators.remove(it.key)
            }
        }
        modelManager.deleteDownloadedModel(TranslateRemoteModel.Builder(code).build()).await()
    }

    /** True when downloading now would respect the "Wi-Fi only" preference. */
    fun canDownloadNow(wifiOnly: Boolean): Boolean {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        return !wifiOnly || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    suspend fun translate(source: String, target: String, text: String): String = mutex.withLock {
        cache.get(source, target, text)?.let { return@withLock it }
        val translator = translators.getOrPut(source to target) {
            Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build(),
            )
        }
        val result = translator.translate(text).await()
        cache.put(source, target, text, result)
        result
    }

    companion object {
        fun displayName(code: String): String {
            val name = Locale.forLanguageTag(code).getDisplayName(Locale.getDefault())
            return name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        }
    }
}

package com.translateoverlay.translate

import com.translateoverlay.core.TranslationCache
import com.translateoverlay.pipeline.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Picks the translation engine chosen in the settings; online engines translate a whole screen
 * in one request and fall back to ML Kit (offline) when the key is missing or the call fails.
 */
class TranslatorRouter(private val mlKit: TranslationEngine, private val secrets: SecretStore) {
    private val cache = TranslationCache(capacity = 2000)

    /** True when [provider] can be used right now (an online engine needs its key). */
    fun isReady(provider: TranslationProvider): Boolean = when (provider) {
        TranslationProvider.MLKIT -> true
        // get(), not has(): a key restored from a backup on another device cannot be decrypted.
        TranslationProvider.AZURE -> secrets.get(SecretStore.AZURE_KEY) != null
        TranslationProvider.GOOGLE_CLOUD -> secrets.get(SecretStore.GOOGLE_KEY) != null
    }

    /**
     * Translates with [provider]; returns null when ML Kit must be used instead (the caller
     * handles ML Kit models), with the failure reason reported through [onFallback].
     */
    suspend fun translateOnline(
        provider: TranslationProvider,
        texts: List<String>,
        source: String,
        target: String,
        azureRegion: String?,
        onFallback: (OnlineFailure) -> Unit,
    ): List<String>? {
        if (!provider.online) return null
        val cached = texts.map { cache.get("${provider.name}:$source", target, it) }
        val missing = texts.indices.filter { cached[it] == null }
        if (missing.isEmpty()) return cached.map { it!! }
        return try {
            val fresh = request(provider, missing.map { texts[it] }, source, target, azureRegion)
            missing.forEachIndexed { k, i -> cache.put("${provider.name}:$source", target, texts[i], fresh[k]) }
            texts.indices.map { i -> cached[i] ?: fresh[missing.indexOf(i)] }
        } catch (e: OnlineTranslationException) {
            Diag.log { "online ${provider.name} failed: ${e.failure} ${e.message}" }
            onFallback(e.failure)
            null
        }
    }

    private suspend fun request(
        provider: TranslationProvider,
        texts: List<String>,
        source: String,
        target: String,
        azureRegion: String?,
    ): List<String> = withContext(Dispatchers.IO) {
        when (provider) {
            TranslationProvider.AZURE -> {
                val key = secrets.get(SecretStore.AZURE_KEY) ?: throw OnlineTranslationException(OnlineFailure.BAD_KEY)
                Batching.chunks(texts, AzureProtocol.MAX_ITEMS, AzureProtocol.MAX_CHARS).flatMap { chunk ->
                    val json = post(AzureProtocol.url(source, target), AzureProtocol.headers(key, azureRegion), AzureProtocol.body(chunk))
                    AzureProtocol.parse(json, chunk.size)
                }
            }
            TranslationProvider.GOOGLE_CLOUD -> {
                val key = secrets.get(SecretStore.GOOGLE_KEY) ?: throw OnlineTranslationException(OnlineFailure.BAD_KEY)
                Batching.chunks(texts, GoogleCloudProtocol.MAX_ITEMS, GoogleCloudProtocol.MAX_CHARS).flatMap { chunk ->
                    val json = post(
                        GoogleCloudProtocol.url(key),
                        mapOf("Content-Type" to "application/json; charset=UTF-8"),
                        GoogleCloudProtocol.body(chunk, source, target),
                    )
                    GoogleCloudProtocol.parse(json, chunk.size)
                }
            }
            TranslationProvider.MLKIT -> error("not an online provider")
        }
    }

    private fun post(url: String, headers: Map<String, String>, body: String): String {
        val conn = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
        } catch (e: IOException) {
            throw OnlineTranslationException(OnlineFailure.NETWORK, e.message)
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val detail = conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(400)
                throw OnlineTranslationException(OnlineFailure.fromHttp(code, detail), "HTTP $code $detail")
            }
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: IOException) {
            throw OnlineTranslationException(OnlineFailure.NETWORK, e.message)
        } finally {
            conn.disconnect()
        }
    }

    /** Settings "Test" button: one short Hebrew phrase through the chosen engine. */
    suspend fun test(provider: TranslationProvider, target: String, azureRegion: String?): String {
        var failure: OnlineFailure? = null
        val out = translateOnline(provider, listOf("בדיקת תרגום"), "he", target, azureRegion) { failure = it }
        return out?.first()?.let { "OK : « $it »" } ?: "Échec : ${failure?.message ?: "moteur hors-ligne"}"
    }

    private companion object {
        const val TIMEOUT_MS = 8_000
    }
}

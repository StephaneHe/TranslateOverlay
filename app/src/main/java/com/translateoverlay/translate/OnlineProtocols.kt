package com.translateoverlay.translate

import org.json.JSONArray
import org.json.JSONObject

/** Translation engines offered in the settings (see docs/TRANSLATION_ENGINES.md). */
enum class TranslationProvider(val label: String, val online: Boolean) {
    MLKIT("ML Kit (hors-ligne, sur l'appareil)", online = false),
    // Not recommended any more (user decision 2026-09-25: no paid key / credit card); kept for later.
    AZURE("Microsoft Azure Translator (en ligne, clé + carte bancaire)", online = true),
    GOOGLE_CLOUD("Google Cloud Translation (en ligne, clé + carte bancaire)", online = true),
}

fun TranslationProvider.shortLabel(): String = when (this) {
    TranslationProvider.MLKIT -> "ML Kit"
    TranslationProvider.AZURE -> "Azure"
    TranslationProvider.GOOGLE_CLOUD -> "Google"
}

/** Why an online request failed; every failure falls back to ML Kit. */
enum class OnlineFailure(val message: String) {
    BAD_KEY("clé API refusée"),
    QUOTA("quota dépassé"),
    NETWORK("réseau indisponible"),
    SERVER("erreur du service"),
    MALFORMED("réponse illisible");

    companion object {
        /**
         * @param body error body: Google answers an invalid key with HTTP 400 "API key not valid"
         *   (checked against the live API), Azure with 401.
         */
        fun fromHttp(code: Int, body: String? = null): OnlineFailure = when {
            body != null && (body.contains("API key not valid", true) || body.contains("API_KEY_INVALID")) -> BAD_KEY
            else -> fromCode(code)
        }

        private fun fromCode(code: Int): OnlineFailure = when (code) {
            400 -> MALFORMED
            401, 403 -> BAD_KEY
            429 -> QUOTA
            else -> SERVER
        }
    }
}

class OnlineTranslationException(val failure: OnlineFailure, detail: String? = null) :
    Exception(detail ?: failure.message)

/** Splits a screen's texts into requests within a service's limits, preserving order. */
object Batching {
    fun chunks(texts: List<String>, maxItems: Int, maxChars: Int): List<List<String>> {
        val out = ArrayList<List<String>>()
        var current = ArrayList<String>()
        var chars = 0
        for (t in texts) {
            if (current.isNotEmpty() && (current.size >= maxItems || chars + t.length > maxChars)) {
                out += current
                current = ArrayList()
                chars = 0
            }
            current += t
            chars += t.length
        }
        if (current.isNotEmpty()) out += current
        return out
    }
}

/** Language codes as each service expects them (ML Kit / BCP-47 base codes on our side). */
object ProviderLanguages {
    fun azure(code: String): String = when (code) {
        "zh" -> "zh-Hans"
        "tl" -> "fil"
        "no" -> "nb"
        else -> code
    }

    fun google(code: String): String = when (code) {
        "zh" -> "zh-CN"
        else -> code // "he" is accepted (legacy "iw" too)
    }
}

/**
 * Microsoft Translator v3: POST https://api.cognitive.microsofttranslator.com/translate
 * ?api-version=3.0&from=he&to=fr, headers Ocp-Apim-Subscription-Key (+ -Region for regional
 * resources), body [{"Text": "..."}]; up to 1000 elements / 50 000 characters per request.
 */
object AzureProtocol {
    const val ENDPOINT = "https://api.cognitive.microsofttranslator.com/translate"
    const val MAX_ITEMS = 1000
    const val MAX_CHARS = 45_000

    fun url(source: String, target: String): String =
        "$ENDPOINT?api-version=3.0&from=${ProviderLanguages.azure(source)}&to=${ProviderLanguages.azure(target)}&textType=plain"

    fun headers(key: String, region: String?): Map<String, String> = buildMap {
        put("Ocp-Apim-Subscription-Key", key)
        if (!region.isNullOrBlank()) put("Ocp-Apim-Subscription-Region", region.trim())
        put("Content-Type", "application/json; charset=UTF-8")
    }

    fun body(texts: List<String>): String =
        JSONArray().apply { texts.forEach { put(JSONObject().put("Text", it)) } }.toString()

    fun parse(json: String, expected: Int): List<String> {
        val arr = runCatching { JSONArray(json) }.getOrElse { throw OnlineTranslationException(OnlineFailure.MALFORMED) }
        if (arr.length() != expected) throw OnlineTranslationException(OnlineFailure.MALFORMED)
        return runCatching {
            (0 until arr.length()).map { i -> arr.getJSONObject(i).getJSONArray("translations").getJSONObject(0).getString("text") }
        }.getOrElse { throw OnlineTranslationException(OnlineFailure.MALFORMED) }
    }
}

/**
 * Google Cloud Translation v2 (Basic): POST https://translation.googleapis.com/language/translate/v2
 * ?key=KEY, body {"q": [...], "source": "he", "target": "fr", "format": "text"}; up to 128
 * segments / 30 000 characters per request (100 k recommended max; we stay well below).
 */
object GoogleCloudProtocol {
    const val ENDPOINT = "https://translation.googleapis.com/language/translate/v2"
    const val MAX_ITEMS = 128
    const val MAX_CHARS = 25_000

    fun url(key: String): String = "$ENDPOINT?key=$key"

    fun body(texts: List<String>, source: String, target: String): String = JSONObject()
        .put("q", JSONArray(texts))
        .put("source", ProviderLanguages.google(source))
        .put("target", ProviderLanguages.google(target))
        .put("format", "text")
        .toString()

    fun parse(json: String, expected: Int): List<String> {
        val list = runCatching { JSONObject(json).getJSONObject("data").getJSONArray("translations") }
            .getOrElse { throw OnlineTranslationException(OnlineFailure.MALFORMED) }
        if (list.length() != expected) throw OnlineTranslationException(OnlineFailure.MALFORMED)
        return runCatching { (0 until list.length()).map { list.getJSONObject(it).getString("translatedText") } }
            .getOrElse { throw OnlineTranslationException(OnlineFailure.MALFORMED) }
    }
}

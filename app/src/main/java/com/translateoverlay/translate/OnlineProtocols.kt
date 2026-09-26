package com.translateoverlay.translate

import org.json.JSONArray
import org.json.JSONObject

/** Translation engines offered in the settings (see docs/TRANSLATION_ENGINES.md). */
enum class TranslationProvider(val label: String, val online: Boolean) {
    MLKIT("ML Kit (hors-ligne, sur l'appareil)", online = false),
    // User decision 2026-09-26: best free engine even if online, the second one as fail-safe.
    NVIDIA("NVIDIA Nemotron (en ligne, gratuit, clé personnelle) — recommandé", online = true),
    // Not recommended any more (user decision 2026-09-25: no paid key / credit card); kept for later.
    AZURE("Microsoft Azure Translator (en ligne, clé + carte bancaire)", online = true),
    GOOGLE_CLOUD("Google Cloud Translation (en ligne, clé + carte bancaire)", online = true),
}

fun TranslationProvider.shortLabel(): String = when (this) {
    TranslationProvider.MLKIT -> "ML Kit"
    TranslationProvider.NVIDIA -> "NVIDIA"
    TranslationProvider.AZURE -> "Azure"
    TranslationProvider.GOOGLE_CLOUD -> "Google"
}

/** Why an online request failed; every failure falls back to the next engine, ML Kit last. */
enum class OnlineFailure(val message: String) {
    NO_KEY("clé absente"),
    BAD_KEY("clé API refusée"),
    QUOTA("quota dépassé"),
    RATE_LIMITED("limite de requêtes de l'application"),
    CIRCUIT_OPEN("service en pause après des erreurs"),
    NETWORK("réseau indisponible"),
    TIMEOUT("délai dépassé"),
    SERVER("service surchargé"),
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

/** @property retryAfterMs server-requested pause (HTTP 429 Retry-After), when given. */
class OnlineTranslationException(
    val failure: OnlineFailure,
    detail: String? = null,
    val retryAfterMs: Long? = null,
) : Exception(detail ?: failure.message)

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

/**
 * NVIDIA API catalog (build.nvidia.com, free trial, no card): OpenAI-compatible
 * POST https://integrate.api.nvidia.com/v1/chat/completions, "Authorization: Bearer KEY",
 * streamed (server-sent events). Measured 2026-09-26 (docs/TRANSLATION_ENGINES.md §7): with
 * reasoning off and one line per block, a 15-block screen takes ~4 s instead of 1–4 min.
 */
object NvidiaProtocol {
    const val ENDPOINT = "https://integrate.api.nvidia.com/v1/chat/completions"
    /** Blocks per request: small requests come back sooner and fail alone. */
    const val ITEMS_PER_REQUEST = 4
    /** Generation, not queueing, dominates a long chunk (6 blocks / ~700 chars: 16 s on 2026-09-26). */
    const val MAX_CHARS = 500

    /** @property label shown in the overlay caption. */
    data class Model(val id: String, val label: String)

    /** chrF++ he→fr 74,4 / en→fr 80,3 / en→he 67,7; 15-block screen p50 4,2 s. */
    val PRIMARY = Model("nvidia/nemotron-3-ultra-550b-a55b", "Nemotron Ultra")
    /** chrF++ 72,4 / 79,5 / 55,3; faster but often "503 Service temporarily overloaded". */
    val FAILSAFE = Model("nvidia/nemotron-3-super-120b-a12b", "Nemotron Super")

    fun headers(key: String): Map<String, String> = mapOf(
        "Authorization" to "Bearer $key",
        "Content-Type" to "application/json; charset=UTF-8",
        "Accept" to "text/event-stream",
    )

    /** English language name for the prompt ("he" → "Hebrew"). */
    fun languageName(code: String): String =
        java.util.Locale.forLanguageTag(code).getDisplayLanguage(java.util.Locale.ENGLISH).ifBlank { code }

    /** One block per line; the answer must have exactly as many lines. */
    fun body(model: String, texts: List<String>, source: String, target: String): String {
        val lines = texts.map { it.replace(Regex("\\s*\\n\\s*"), " ").trim() }
        val chars = lines.sumOf { it.length }
        // Tight output budget: a runaway answer is cut instead of eating the time budget.
        val maxTokens = (32 + 2 * chars + 8 * lines.size).coerceAtMost(2048)
        val messages = JSONArray()
            // Nemotron: reasoning off (it multiplied the latency by 15 in the 2026-09-25 bench).
            .put(JSONObject().put("role", "system").put("content", "/no_think"))
            .put(
                JSONObject().put("role", "system").put(
                    "content",
                    "Translate each line from ${languageName(source)} to ${languageName(target)}. " +
                        "Output only the translations, one per line, same order.",
                ),
            )
            .put(JSONObject().put("role", "user").put("content", lines.joinToString("\n")))
        return JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", 0)
            .put("max_tokens", maxTokens)
            .put("stream", true)
            .put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
            .toString()
    }

    /**
     * One server-sent-events line: returns the content piece (possibly empty), null at the end
     * of the stream; throws on an in-stream error (NVIDIA sends "503 overloaded" with HTTP 200).
     */
    fun parseEvent(line: String): String? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("data:")) return ""
        val data = trimmed.removePrefix("data:").trim()
        if (data == "[DONE]") return null
        val json = runCatching { JSONObject(data) }.getOrElse { throw OnlineTranslationException(OnlineFailure.MALFORMED) }
        json.optJSONObject("error")?.let { err ->
            val code = err.optInt("code", 500)
            throw OnlineTranslationException(OnlineFailure.fromHttp(code), "stream error $code ${err.optString("message")}")
        }
        val choices = json.optJSONArray("choices") ?: return "" // usage / keep-alive chunk
        if (choices.length() == 0) return ""
        // Reasoning ("reasoning_content") is ignored: only the answer is kept.
        return choices.getJSONObject(0).optJSONObject("delta")?.optString("content", "") ?: ""
    }

    fun parseLines(content: String, expected: Int): List<String> {
        val lines = content.replace(Regex("(?s)<think>.*?</think>"), "")
            .lines().map { it.trim() }.filter { it.isNotEmpty() }
        // A single long paragraph may come back in several lines (seen with Nemotron Ultra): one block.
        if (expected == 1 && lines.isNotEmpty()) return listOf(lines.joinToString(" "))
        if (lines.size != expected) throw OnlineTranslationException(OnlineFailure.MALFORMED, "${lines.size} lignes / $expected")
        return lines
    }
}

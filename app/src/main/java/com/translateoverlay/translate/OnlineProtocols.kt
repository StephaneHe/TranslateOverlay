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
 * reasoning off, a 15-block screen in ONE request takes p50 4,2 s / p95 6,9 s (6,0 / 8,7 s in
 * parallel chunks), so a screen is one numbered request whose answer lines are placed on the
 * blocks as they stream in.
 */
object NvidiaProtocol {
    const val ENDPOINT = "https://integrate.api.nvidia.com/v1/chat/completions"

    /**
     * A screen is split only beyond this many source characters (a busy article screen is
     * ~1 500–2 500): the answer budget ([maxTokens]) then stays under [MAX_OUTPUT_TOKENS], well
     * inside the models' context, and one request stays under ~20 s of generation.
     */
    const val MAX_CHARS_PER_REQUEST = 4_000
    const val MAX_OUTPUT_TOKENS = 8_192

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

    /** Tight output budget: a runaway answer is cut instead of eating the time budget. */
    fun maxTokens(texts: List<String>): Int =
        (32 + 2 * texts.sumOf { it.length } + 12 * texts.size).coerceAtMost(MAX_OUTPUT_TOKENS)

    /**
     * Every block of the screen in one message, numbered from 1 ("[3] text"); the answer repeats
     * the numbers, so blocks are placed by id, not by line position.
     * @param source language of all blocks, or null when the screen mixes languages.
     */
    fun body(model: String, texts: List<String>, source: String?, target: String): String {
        val lines = texts.mapIndexed { i, t -> "[${i + 1}] " + t.replace(Regex("\\s*\\n\\s*"), " ").trim() }
        val from = source?.let { "from ${languageName(it)} " } ?: ""
        val messages = JSONArray()
            // Nemotron: reasoning off (it multiplied the latency by 15 in the 2026-09-25 bench).
            .put(JSONObject().put("role", "system").put("content", "/no_think"))
            .put(
                JSONObject().put("role", "system").put(
                    "content",
                    "Translate each numbered line ${from}to ${languageName(target)}. Answer one line per input, " +
                        "starting with the same [number], then the translation only.",
                ),
            )
            .put(JSONObject().put("role", "user").put("content", lines.joinToString("\n")))
        return JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", 0)
            .put("max_tokens", maxTokens(texts))
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
}

/**
 * Turns the streamed answer into (block index, translation) as soon as each line is complete.
 * Tolerant: "[3] …", "3. …", "3) …", "3: …" or "3 - …"; answers in any order; a duplicate id
 * keeps the first answer; an unknown id or a line without id continues the previous block (a
 * long paragraph answered on several lines), which is then re-emitted with the longer text.
 * Blocks never answered are left to the caller (fail-safe, then offline).
 *
 * @param count number of blocks in the request (ids 1..count)
 * @param emit block index (0-based) and its translation so far; may be called again for the same
 *   index when a continuation line arrives.
 */
class NumberedLineParser(private val count: Int, private val emit: (Int, String) -> Unit) {
    private val buffer = StringBuilder()
    private val texts = HashMap<Int, String>()
    private var current: Int? = null
    private var thinking = false

    /** Indices answered so far. */
    val received: Set<Int> get() = texts.keys

    fun feed(piece: String) {
        buffer.append(piece)
        while (true) {
            val nl = buffer.indexOf("\n")
            if (nl < 0) return
            val line = buffer.substring(0, nl)
            buffer.delete(0, nl + 1)
            line(line)
        }
    }

    /** End of the stream: the last line has no newline. */
    fun finish() {
        if (buffer.isNotEmpty()) line(buffer.toString())
        buffer.setLength(0)
    }

    private fun line(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        if (text.startsWith("<think>")) thinking = true
        if (thinking) {
            if (text.contains("</think>")) thinking = false
            return
        }
        val m = ID.matchEntire(text)
        val id = m?.let { (it.groupValues[1].ifEmpty { it.groupValues[3] }).toIntOrNull() }
        val body = m?.let { (it.groupValues[2].ifEmpty { it.groupValues[4] }).trim() }
        if (id != null && id in 1..count) {
            if ((id - 1) in texts) {
                current = null // duplicate: keep the first answer, drop this one and its continuation
                return
            }
            current = id - 1
            if (!body.isNullOrEmpty()) put(id - 1, body)
            return
        }
        // No usable id: continuation of the block being answered.
        val index = current ?: return
        put(index, texts[index]?.let { "$it $text" } ?: text)
    }

    private fun put(index: Int, text: String) {
        texts[index] = text
        emit(index, text)
    }

    private companion object {
        /** "[12] text" (brackets) or "12. text" / "12) text" / "12: text" / "12 - text". */
        val ID = Regex("""^\[(\d{1,3})]\s*(.*)$|^(\d{1,3})\s*[.):\-–]\s+(.*)$""")
    }
}

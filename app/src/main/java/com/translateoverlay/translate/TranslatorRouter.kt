package com.translateoverlay.translate

import android.os.SystemClock
import com.translateoverlay.core.RefineItem
import com.translateoverlay.core.ScreenRequests
import com.translateoverlay.core.TranslationCache
import com.translateoverlay.pipeline.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * Online translation behind the offline one: for the engine chosen in the settings, a chain of
 * models (NVIDIA: primary, then fail-safe) translates the whole screen in ONE streamed request
 * (blocks numbered, answers placed by number as they arrive); blocks the primary did not answer
 * go to the fail-safe in one more request; whatever fails keeps the ML Kit translation shown. Limiter and circuit breakers live as long as the
 * app process and are only touched from the main thread (network calls hop to IO).
 */
class TranslatorRouter(private val mlKit: TranslationEngine, private val secrets: SecretStore) {
    private val cache = TranslationCache(capacity = 2000)
    private val clock: () -> Long = SystemClock::elapsedRealtime
    private val requests = AtomicInteger()

    // The NVIDIA trial allows 40 requests/min for the whole account, which is shared: stay at half.
    private val nvidiaLimiter = RateLimiter(maxRequests = 20, windowMs = 60_000)
    private val nvidiaAccount = CircuitBreaker(failureThreshold = Int.MAX_VALUE)
    private val breakers = HashMap<String, CircuitBreaker>()

    private fun breaker(id: String) = breakers.getOrPut(id) { CircuitBreaker() }

    /** Test hook (set only by the debug build's DebugKeyReceiver): model ids answering "503". */
    @Volatile
    var simulatedDown: Set<String> = emptySet()

    /** True when [provider] can be used right now (an online engine needs its key). */
    fun isReady(provider: TranslationProvider): Boolean = when (provider) {
        TranslationProvider.MLKIT -> true
        // get(), not has(): a key restored from a backup on another device cannot be decrypted.
        else -> keyName(provider)?.let { secrets.get(it) } != null
    }

    fun keyName(provider: TranslationProvider): String? = when (provider) {
        TranslationProvider.MLKIT -> null
        TranslationProvider.NVIDIA -> SecretStore.NVIDIA_KEY
        TranslationProvider.AZURE -> SecretStore.AZURE_KEY
        TranslationProvider.GOOGLE_CLOUD -> SecretStore.GOOGLE_KEY
    }

    /** The key was replaced or deleted: past errors (e.g. "key refused") no longer apply. */
    fun onKeyChanged() {
        breakers.values.forEach { it.reset() }
        nvidiaAccount.reset()
    }

    /** Engine labels best first (the overlay only replaces a block by a better-ranked engine). */
    fun ranks(provider: TranslationProvider): Map<String, Int> =
        models(provider).mapIndexed { i, m -> m.second to i }.toMap()

    /** Best online translation already known for [text], with its engine label. */
    fun cached(provider: TranslationProvider, source: String, target: String, text: String): Pair<String, String>? =
        models(provider).firstNotNullOfOrNull { (id, label) -> cache.get("$id:$source", target, text)?.let { it to label } }

    /** (id, label) of the chain's models, best first. */
    private fun models(provider: TranslationProvider): List<Pair<String, String>> = when (provider) {
        TranslationProvider.MLKIT -> emptyList()
        TranslationProvider.NVIDIA -> listOf(NvidiaProtocol.PRIMARY, NvidiaProtocol.FAILSAFE).map { it.id to it.label }
        else -> listOf(provider.name to provider.shortLabel())
    }

    private fun chain(provider: TranslationProvider, source: String?, target: String, azureRegion: String?): FallbackChain {
        val engines = when (provider) {
            TranslationProvider.NVIDIA -> listOf(NvidiaProtocol.PRIMARY, NvidiaProtocol.FAILSAFE).map { m ->
                ChainEngine(m.id, m.label, breaker(m.id), nvidiaLimiter, nvidiaAccount) { texts, onBlock ->
                    nvidia(m, texts, source, target, onBlock)
                }
            }
            TranslationProvider.AZURE, TranslationProvider.GOOGLE_CLOUD -> listOf(
                ChainEngine(provider.name, provider.shortLabel(), breaker(provider.name), null) { texts, onBlock ->
                    // One language per call for these APIs.
                    classic(provider, texts, source ?: "auto", target, azureRegion).forEachIndexed(onBlock)
                },
            )
            TranslationProvider.MLKIT -> emptyList()
        }
        return FallbackChain(engines, clock, ::delay)
    }

    /**
     * Translates [items] with [provider]'s chain: ONE request for the whole screen (split only
     * beyond [NvidiaProtocol.MAX_CHARS_PER_REQUEST]), then one fail-safe request for the blocks
     * still missing. [onBlock] gets each block as soon as its line has streamed in (possibly
     * again when the line grows), [onFailed] the blocks every engine failed on (their offline
     * translation stays). Call from the main thread.
     */
    suspend fun refine(
        provider: TranslationProvider,
        items: List<RefineItem>,
        target: String,
        azureRegion: String?,
        onBlock: (RefineItem, String, String) -> Unit,
        onFailed: (List<RefineItem>, OnlineFailure) -> Unit,
    ) {
        if (items.isEmpty()) return
        if (!isReady(provider)) return onFailed(items, OnlineFailure.NO_KEY)
        if (!mlKit.canDownloadNow(wifiOnly = false)) return onFailed(items, OnlineFailure.NETWORK)
        val requests = when (provider) {
            TranslationProvider.NVIDIA -> ScreenRequests.plan(items, NvidiaProtocol.MAX_CHARS_PER_REQUEST)
            // These APIs take one source language per call (and split by their own limits).
            else -> items.groupBy { it.source }.values.toList()
        }
        var sent = 0
        coroutineScope {
            for (group in requests) {
                launch {
                    val result = chain(provider, ScreenRequests.commonSource(group), target, azureRegion)
                        .translate(group.map { it.text }) { k, text, engine ->
                            val item = group[k]
                            cache.put("${engine.id}:${item.source}", target, item.text, text)
                            onBlock(item, text, engine.label)
                        }
                    sent += result.requests
                    Diag.log { "refine ${group.size} blocks in ${result.requests} request(s), missing=${result.missing.size} failures=${result.failures}" }
                    if (result.missing.isNotEmpty()) {
                        onFailed(result.missing.map { group[it] }, result.failures.lastOrNull()?.second ?: OnlineFailure.SERVER)
                    }
                }
            }
        }
        Diag.log { "screen: ${items.size} blocks, $sent request(s)" }
    }

    /**
     * Streamed chat completion of a numbered screen; each answer line is handed to [onBlock] on
     * the main thread as soon as it is complete. Short timeouts: the offline text is on screen.
     */
    private suspend fun nvidia(
        model: NvidiaProtocol.Model,
        texts: List<String>,
        source: String?,
        target: String,
        onBlock: (Int, String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val key = secrets.get(SecretStore.NVIDIA_KEY) ?: throw OnlineTranslationException(OnlineFailure.NO_KEY)
        if (model.id in simulatedDown) throw OnlineTranslationException(OnlineFailure.SERVER, "simulated 503 (debug)")
        val n = requests.incrementAndGet()
        val started = SystemClock.elapsedRealtime()
        var status = 0
        var firstTokenMs = -1L
        var firstBlockMs = -1L
        val ready = ArrayList<Pair<Int, String>>()
        val parser = NumberedLineParser(texts.size) { i, t -> ready += i to t }
        var conn: HttpURLConnection? = null
        // Closing the connection is the only way to abort a blocking read (overlay dismissed).
        val onCancel = coroutineContext[Job]?.invokeOnCompletion { conn?.disconnect() }
        suspend fun flush() {
            if (ready.isEmpty()) return
            if (firstBlockMs < 0) firstBlockMs = SystemClock.elapsedRealtime() - started
            val batch = ready.toList()
            ready.clear()
            withContext(Dispatchers.Main) { batch.forEach { (i, t) -> onBlock(i, t) } }
        }
        try {
            val c = open(NvidiaProtocol.ENDPOINT, NvidiaProtocol.headers(key), STREAM_READ_TIMEOUT_MS)
            conn = c
            val body = NvidiaProtocol.body(model.id, texts, source, target)
            Diag.log { "nvidia #$n source=$source target=$target" }
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            status = c.responseCode
            if (status !in 200..299) throw httpFailure(c, status)
            c.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    ensureActive()
                    if (SystemClock.elapsedRealtime() - started > STREAM_TOTAL_TIMEOUT_MS) {
                        throw OnlineTranslationException(OnlineFailure.TIMEOUT)
                    }
                    val line = reader.readLine() ?: break
                    val piece = NvidiaProtocol.parseEvent(line) ?: break
                    if (piece.isNotEmpty() && firstTokenMs < 0) firstTokenMs = SystemClock.elapsedRealtime() - started
                    parser.feed(piece)
                    flush()
                }
            }
            parser.finish()
            flush()
        } catch (e: SocketTimeoutException) {
            flush() // blocks already complete are kept
            throw OnlineTranslationException(OnlineFailure.TIMEOUT, e.message)
        } catch (e: IOException) {
            ensureActive() // cancelled: not a network failure
            flush()
            throw OnlineTranslationException(OnlineFailure.NETWORK, e.message)
        } catch (e: OnlineTranslationException) {
            flush()
            throw e
        } finally {
            onCancel?.dispose()
            conn?.disconnect()
            // Request counter (the NVIDIA quota is shared): never the key, never the text.
            Diag.log {
                "nvidia #$n ${model.id} ${texts.size} blocks HTTP $status ttft=${firstTokenMs}ms " +
                    "firstBlock=${firstBlockMs}ms total=${SystemClock.elapsedRealtime() - started}ms received=${parser.received.size}/${texts.size}"
            }
        }
    }

    private suspend fun classic(
        provider: TranslationProvider,
        texts: List<String>,
        source: String,
        target: String,
        azureRegion: String?,
    ): List<String> = withContext(Dispatchers.IO) {
        when (provider) {
            TranslationProvider.AZURE -> {
                val key = secrets.get(SecretStore.AZURE_KEY) ?: throw OnlineTranslationException(OnlineFailure.NO_KEY)
                Batching.chunks(texts, AzureProtocol.MAX_ITEMS, AzureProtocol.MAX_CHARS).flatMap { chunk ->
                    val json = post(AzureProtocol.url(source, target), AzureProtocol.headers(key, azureRegion), AzureProtocol.body(chunk))
                    AzureProtocol.parse(json, chunk.size)
                }
            }
            TranslationProvider.GOOGLE_CLOUD -> {
                val key = secrets.get(SecretStore.GOOGLE_KEY) ?: throw OnlineTranslationException(OnlineFailure.NO_KEY)
                Batching.chunks(texts, GoogleCloudProtocol.MAX_ITEMS, GoogleCloudProtocol.MAX_CHARS).flatMap { chunk ->
                    val json = post(
                        GoogleCloudProtocol.url(key),
                        mapOf("Content-Type" to "application/json; charset=UTF-8"),
                        GoogleCloudProtocol.body(chunk, source, target),
                    )
                    GoogleCloudProtocol.parse(json, chunk.size)
                }
            }
            else -> error("not a single-request provider")
        }
    }

    private fun open(url: String, headers: Map<String, String>, readTimeoutMs: Int): HttpURLConnection = try {
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = readTimeoutMs
            doOutput = true
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
    } catch (e: IOException) {
        throw OnlineTranslationException(OnlineFailure.NETWORK, e.message)
    }

    private fun httpFailure(conn: HttpURLConnection, code: Int): OnlineTranslationException {
        val detail = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(300) }.getOrNull()
        val retryAfterMs = conn.getHeaderField("Retry-After")?.trim()?.toLongOrNull()?.times(1000)
        return OnlineTranslationException(OnlineFailure.fromHttp(code, detail), "HTTP $code $detail", retryAfterMs)
    }

    private fun post(url: String, headers: Map<String, String>, body: String): String {
        val conn = open(url, headers, CLASSIC_TIMEOUT_MS)
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) throw httpFailure(conn, code)
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: SocketTimeoutException) {
            throw OnlineTranslationException(OnlineFailure.TIMEOUT, e.message)
        } catch (e: IOException) {
            throw OnlineTranslationException(OnlineFailure.NETWORK, e.message)
        } finally {
            conn.disconnect()
        }
    }

    /** Settings "Test" button: one short Hebrew phrase through the chosen engine's chain. */
    suspend fun test(provider: TranslationProvider, target: String, azureRegion: String?): String {
        if (!provider.online) return "Moteur hors-ligne"
        var result = "Échec : ${OnlineFailure.SERVER.message}"
        refine(
            provider, listOf(RefineItem(0, "בדיקת תרגום", "he", 0, 0)), target, azureRegion,
            onBlock = { _, text, engine -> result = "OK ($engine) : « $text »" },
            onFailed = { _, failure -> result = "Échec : ${failure.message}" },
        )
        return result
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val CLASSIC_TIMEOUT_MS = 8_000
        /** Max silence while streaming (first token included): TTFT 0.6–6.5 s measured; beyond, the fail-safe is quicker. */
        const val STREAM_READ_TIMEOUT_MS = 7_000
        /** A whole screen in one answer: ~4 s typical, 7 s p95 (2026-09-26). */
        const val STREAM_TOTAL_TIMEOUT_MS = 30_000L
    }
}

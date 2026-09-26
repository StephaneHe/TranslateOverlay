package com.translateoverlay.translate

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineProtocolsTest {
    // Real ynet headlines (see tools/mt-bench/corpus.json).
    private val ynet = listOf("לכל המבזקים", "הרוג בתאונה בבקעת הירדן, 3 פצועים במצב קשה", "בצעו את המבחן")

    @Test
    fun `azure request`() {
        assertEquals(
            "https://api.cognitive.microsofttranslator.com/translate?api-version=3.0&from=he&to=fr&textType=plain",
            AzureProtocol.url("he", "fr"),
        )
        val body = JSONArray(AzureProtocol.body(ynet))
        assertEquals(3, body.length())
        assertEquals("לכל המבזקים", body.getJSONObject(0).getString("Text"))
        val headers = AzureProtocol.headers("k", " francecentral ")
        assertEquals("k", headers["Ocp-Apim-Subscription-Key"])
        assertEquals("francecentral", headers["Ocp-Apim-Subscription-Region"])
        assertFalse(AzureProtocol.headers("k", "").containsKey("Ocp-Apim-Subscription-Region")) // global resource
    }

    @Test
    fun `azure response`() {
        val json = """[{"translations":[{"text":"Toutes les brèves","to":"fr"}]},
            {"translations":[{"text":"Un mort dans un accident","to":"fr"}]}]"""
        assertEquals(listOf("Toutes les brèves", "Un mort dans un accident"), AzureProtocol.parse(json, 2))
        assertEquals(OnlineFailure.MALFORMED, assertThrows(OnlineTranslationException::class.java) { AzureProtocol.parse(json, 3) }.failure)
        assertEquals(OnlineFailure.MALFORMED, assertThrows(OnlineTranslationException::class.java) { AzureProtocol.parse("{\"error\":1}", 1) }.failure)
        assertEquals(OnlineFailure.MALFORMED, assertThrows(OnlineTranslationException::class.java) { AzureProtocol.parse("[{\"x\":1}]", 1) }.failure)
    }

    @Test
    fun `google cloud request and response`() {
        assertTrue(GoogleCloudProtocol.url("abc").endsWith("/language/translate/v2?key=abc"))
        val body = JSONObject(GoogleCloudProtocol.body(ynet, "he", "fr"))
        assertEquals("he", body.getString("source"))
        assertEquals("fr", body.getString("target"))
        assertEquals("text", body.getString("format")) // no HTML entities in the answer
        assertEquals(3, body.getJSONArray("q").length())
        val json = """{"data":{"translations":[{"translatedText":"Faites le test"}]}}"""
        assertEquals(listOf("Faites le test"), GoogleCloudProtocol.parse(json, 1))
        assertThrows(OnlineTranslationException::class.java) { GoogleCloudProtocol.parse("""{"error":{"code":403}}""", 1) }
    }

    @Test
    fun `language codes per provider`() {
        assertEquals("he", ProviderLanguages.azure("he"))
        assertEquals("zh-Hans", ProviderLanguages.azure("zh"))
        assertEquals("fil", ProviderLanguages.azure("tl"))
        assertEquals("he", ProviderLanguages.google("he"))
        assertEquals("zh-CN", ProviderLanguages.google("zh"))
    }

    @Test
    fun `http errors map to fallback reasons`() {
        assertEquals(OnlineFailure.BAD_KEY, OnlineFailure.fromHttp(401))
        assertEquals(OnlineFailure.BAD_KEY, OnlineFailure.fromHttp(403))
        assertEquals(OnlineFailure.QUOTA, OnlineFailure.fromHttp(429))
        assertEquals(OnlineFailure.SERVER, OnlineFailure.fromHttp(503))
        assertEquals(OnlineFailure.MALFORMED, OnlineFailure.fromHttp(400))
        // Live answers with an invalid key (2026-09-25): Google 400 + message, Azure 401.
        assertEquals(OnlineFailure.BAD_KEY, OnlineFailure.fromHttp(400, """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key."}}"""))
        assertEquals(OnlineFailure.BAD_KEY, OnlineFailure.fromHttp(401, """{"error":{"code":401001,"message":"The request is not authorized because credentials are missing or invalid."}}"""))
    }

    @Test
    fun `batching respects item and character limits and keeps order`() {
        val texts = (1..10).map { "t$it".padEnd(10, 'x') }
        val byItems = Batching.chunks(texts, maxItems = 4, maxChars = 1000)
        assertEquals(listOf(4, 4, 2), byItems.map { it.size })
        assertEquals(texts, byItems.flatten())
        val byChars = Batching.chunks(texts, maxItems = 100, maxChars = 25)
        assertTrue(byChars.all { chunk -> chunk.sumOf { it.length } <= 25 })
        assertEquals(texts, byChars.flatten())
        // A single text longer than the limit still goes out alone rather than being dropped.
        assertEquals(listOf(listOf("x".repeat(50))), Batching.chunks(listOf("x".repeat(50)), 10, 25))
        assertTrue(Batching.chunks(emptyList(), 10, 25).isEmpty())
    }

    @Test
    fun `nvidia request - reasoning off, streamed, one block per line`() {
        val body = JSONObject(NvidiaProtocol.body(NvidiaProtocol.PRIMARY.id, listOf("שורה\nשנייה", ynet[0]), "he", "fr"))
        assertEquals("nvidia/nemotron-3-ultra-550b-a55b", body.getString("model"))
        assertTrue(body.getBoolean("stream"))
        assertFalse(body.getJSONObject("chat_template_kwargs").getBoolean("enable_thinking"))
        val messages = body.getJSONArray("messages")
        assertEquals("/no_think", messages.getJSONObject(0).getString("content"))
        assertTrue(messages.getJSONObject(1).getString("content").startsWith("Translate each line from Hebrew to French."))
        // A block's own line breaks are flattened: one line = one block.
        assertEquals("שורה שנייה\nלכל המבזקים", messages.getJSONObject(2).getString("content"))
        assertTrue(body.getInt("max_tokens") in 40..200)
        assertEquals("Bearer k", NvidiaProtocol.headers("k")["Authorization"])
    }

    @Test
    fun `nvidia stream events`() {
        assertEquals("Tous", NvidiaProtocol.parseEvent("""data: {"choices":[{"delta":{"content":"Tous"}}]}"""))
        assertEquals("", NvidiaProtocol.parseEvent("""data: {"choices":[{"delta":{"reasoning_content":"hmm"}}]}"""))
        assertEquals("", NvidiaProtocol.parseEvent("""data: {"choices":[],"usage":{"total_tokens":12}}"""))
        assertEquals("", NvidiaProtocol.parseEvent(""))
        assertEquals(null, NvidiaProtocol.parseEvent("data: [DONE]"))
        // Live answer of an overloaded model (2026-09-26): HTTP 200, then this event.
        val overloaded = """data: {"error": {"message": "Service temporarily overloaded", "type": "service_unavailable", "code": 503}}"""
        assertEquals(OnlineFailure.SERVER, assertThrows(OnlineTranslationException::class.java) { NvidiaProtocol.parseEvent(overloaded) }.failure)
        val quota = """data: {"error": {"message": "Too many requests", "code": 429}}"""
        assertEquals(OnlineFailure.QUOTA, assertThrows(OnlineTranslationException::class.java) { NvidiaProtocol.parseEvent(quota) }.failure)
    }

    @Test
    fun `nvidia answer lines must match the blocks`() {
        assertEquals(listOf("Toutes les brèves", "Un mort"), NvidiaProtocol.parseLines("Toutes les brèves\n\n Un mort \n", 2))
        assertEquals(listOf("A"), NvidiaProtocol.parseLines("<think>\nx\ny\n</think>\nA", 1))
        assertEquals(OnlineFailure.MALFORMED, assertThrows(OnlineTranslationException::class.java) { NvidiaProtocol.parseLines("A B", 2) }.failure)
        assertEquals(listOf("Le chat. Il chasse."), NvidiaProtocol.parseLines("Le chat.\nIl chasse.", 1))
        assertEquals("Hebrew", NvidiaProtocol.languageName("he"))
        assertEquals("French", NvidiaProtocol.languageName("fr"))
    }
}

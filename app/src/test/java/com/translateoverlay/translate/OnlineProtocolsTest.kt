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
}

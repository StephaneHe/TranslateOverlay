package com.translateoverlay.translate

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResilienceTest {
    private var now = 0L
    private val slept = ArrayList<Long>()
    /** (engine id, texts it received in its one request) */
    private val calls = ArrayList<Pair<String, List<String>>>()
    /** index → "engine:translation" as delivered to the overlay */
    private val shown = HashMap<Int, String>()

    /**
     * @param answers indices (in the texts it is given) the engine answers before [fail] / the end;
     *   null = all of them.
     */
    private fun engine(
        id: String,
        breaker: CircuitBreaker = CircuitBreaker(),
        limiter: RateLimiter? = null,
        account: CircuitBreaker? = null,
        fail: OnlineFailure? = null,
        retryAfterMs: Long? = null,
        answers: Set<Int>? = null,
    ) = ChainEngine(id, id.uppercase(), breaker, limiter, account) { texts, onBlock ->
        calls += id to texts
        texts.forEachIndexed { k, t -> if (answers == null || k in answers) onBlock(k, "$id:$t") }
        if (fail != null) throw OnlineTranslationException(fail, retryAfterMs = retryAfterMs)
    }

    private fun chain(vararg engines: ChainEngine) =
        FallbackChain(engines.toList(), clock = { now }, sleep = { slept += it; now += it })

    private suspend fun FallbackChain.run(texts: List<String>) =
        translate(texts) { i, text, _ -> shown[i] = text }

    @Test
    fun `rate limiter allows 20 starts per sliding minute`() {
        val limiter = RateLimiter(maxRequests = 20, windowMs = 60_000)
        repeat(20) { i ->
            assertEquals(0, limiter.waitMs(i * 1_000L))
            limiter.acquire(i * 1_000L)
        }
        // 21st at t=20 s: wait until the first start (t=0) leaves the window.
        assertEquals(40_000, limiter.waitMs(20_000))
        assertEquals(0, limiter.waitMs(60_000))
    }

    @Test
    fun `circuit breaker opens after consecutive failures, doubles, closes on success`() {
        val b = CircuitBreaker(failureThreshold = 2, baseCooldownMs = 30_000, maxCooldownMs = 100_000)
        b.onFailure(OnlineFailure.SERVER, now = 0)
        assertTrue(b.allows(0))
        b.onFailure(OnlineFailure.TIMEOUT, now = 0)
        assertFalse(b.allows(29_999))
        assertTrue(b.allows(30_000)) // half-open: one trial
        b.onFailure(OnlineFailure.SERVER, now = 30_000)
        b.onFailure(OnlineFailure.SERVER, now = 30_000)
        assertFalse(b.allows(89_999)) // doubled: 60 s
        assertTrue(b.allows(90_000))
        b.onSuccess()
        b.onFailure(OnlineFailure.SERVER, now = 90_000)
        assertTrue(b.allows(90_000)) // counter reset by the success
    }

    @Test
    fun `quota honours Retry-After, bad answers do not trip the breaker, reset clears`() {
        val b = CircuitBreaker()
        b.onFailure(OnlineFailure.QUOTA, now = 0, retryAfterMs = 45_000)
        assertFalse(b.allows(44_000))
        assertTrue(b.allows(45_000))
        repeat(5) { b.onFailure(OnlineFailure.MALFORMED, now = 50_000) }
        assertTrue(b.allows(50_000))
        b.onFailure(OnlineFailure.BAD_KEY, now = 50_000)
        assertFalse(b.allows(60_000))
        b.reset()
        assertTrue(b.allows(60_000))
    }

    @Test
    fun `whole screen in ONE request - fail-safe not called`() = runBlocking {
        val r = chain(engine("ultra"), engine("super")).run(listOf("a", "b", "c"))
        assertEquals(listOf("ultra" to listOf("a", "b", "c")), calls)
        assertEquals(1, r.requests)
        assertTrue(r.missing.isEmpty())
        assertEquals(mapOf(0 to "ultra:a", 1 to "ultra:b", 2 to "ultra:c"), shown)
    }

    @Test
    fun `missing ids go to the fail-safe in ONE grouped request`() = runBlocking {
        // Ultra answers blocks 0 and 2 only (ids 2, 4, 5 missing from its answer).
        val r = chain(engine("ultra", answers = setOf(0, 2)), engine("super")).run(listOf("a", "b", "c", "d", "e"))
        assertEquals(listOf("ultra" to listOf("a", "b", "c", "d", "e"), "super" to listOf("b", "d", "e")), calls)
        assertEquals(2, r.requests)
        assertEquals(mapOf(0 to "ultra:a", 1 to "super:b", 2 to "ultra:c", 3 to "super:d", 4 to "super:e"), shown)
        assertTrue(r.missing.isEmpty())
        assertEquals(listOf("ultra" to OnlineFailure.MALFORMED), r.failures)
    }

    @Test
    fun `failure mid-stream keeps the blocks already shown, the rest to the fail-safe`() = runBlocking {
        val r = chain(engine("ultra", answers = setOf(0), fail = OnlineFailure.TIMEOUT), engine("super")).run(listOf("a", "b", "c"))
        assertEquals("ultra:a", shown[0])
        assertEquals(listOf("b", "c"), calls[1].second)
        assertEquals("super:c", shown[2])
        assertTrue(r.missing.isEmpty())
    }

    @Test
    fun `primary down - fail-safe gets the whole screen in one request`() = runBlocking {
        for (failure in listOf(OnlineFailure.SERVER, OnlineFailure.TIMEOUT, OnlineFailure.NETWORK)) {
            calls.clear()
            val r = chain(engine("ultra", fail = failure, answers = emptySet()), engine("super")).run(listOf("a", "b"))
            assertEquals(listOf("ultra" to listOf("a", "b"), "super" to listOf("a", "b")), calls)
            assertEquals(listOf("ultra" to failure), r.failures)
        }
    }

    @Test
    fun `everything fails - missing blocks keep the offline translation`() = runBlocking {
        val r = chain(
            engine("ultra", fail = OnlineFailure.SERVER, answers = setOf(1)),
            engine("super", fail = OnlineFailure.TIMEOUT, answers = emptySet()),
        ).run(listOf("a", "b", "c"))
        assertEquals(listOf(0, 2), r.missing)
        assertEquals(mapOf(1 to "ultra:b"), shown)
        assertEquals(2, r.requests)
    }

    @Test
    fun `open breaker skips the primary without a request`() = runBlocking {
        val broken = CircuitBreaker(failureThreshold = 1).apply { onFailure(OnlineFailure.SERVER, now = 0) }
        now = 1_000
        val r = chain(engine("ultra", breaker = broken), engine("super")).run(listOf("a"))
        assertEquals(listOf("super"), calls.map { it.first })
        assertEquals(1, r.requests)
        assertEquals(listOf("ultra" to OnlineFailure.CIRCUIT_OPEN), r.failures)
    }

    @Test
    fun `429 pauses the whole account - fail-safe on the same account not called`() = runBlocking {
        val account = CircuitBreaker(failureThreshold = Int.MAX_VALUE)
        val ultra = engine("ultra", account = account, fail = OnlineFailure.QUOTA, retryAfterMs = 20_000, answers = emptySet())
        val superE = engine("super", account = account)
        val r = chain(ultra, superE).run(listOf("a"))
        assertEquals(listOf(0), r.missing)
        assertEquals(listOf("ultra"), calls.map { it.first })
        assertEquals(OnlineFailure.CIRCUIT_OPEN, r.failures.last().second)
        now = 20_000
        assertTrue(chain(engine("ultra", account = account), superE).run(listOf("a")).missing.isEmpty())
    }

    @Test
    fun `rate limiter - short wait is slept, long wait gives up without calling`() = runBlocking {
        val limiter = RateLimiter(maxRequests = 1, windowMs = 60_000)
        limiter.acquire(0)
        now = 58_000 // 2 s left: worth waiting
        chain(engine("ultra", limiter = limiter)).run(listOf("a"))
        assertEquals(listOf(2_000L), slept)
        now = 70_000 // window full again until 120 s: 50 s wait, not worth it
        calls.clear()
        val r2 = chain(engine("ultra", limiter = limiter), engine("super", limiter = limiter)).run(listOf("a"))
        assertEquals(listOf(0), r2.missing)
        assertTrue(calls.isEmpty())
        assertEquals(0, r2.requests)
        assertEquals(listOf("ultra" to OnlineFailure.RATE_LIMITED), r2.failures)
    }
}

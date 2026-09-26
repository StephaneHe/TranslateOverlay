package com.translateoverlay.translate

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResilienceTest {
    private var now = 0L
    private val slept = ArrayList<Long>()
    private val calls = ArrayList<String>()

    private fun engine(
        id: String,
        breaker: CircuitBreaker = CircuitBreaker(),
        limiter: RateLimiter? = null,
        account: CircuitBreaker? = null,
        fail: OnlineFailure? = null,
        retryAfterMs: Long? = null,
    ) = ChainEngine(id, id.uppercase(), breaker, limiter, account) { texts ->
        calls += id
        if (fail != null) throw OnlineTranslationException(fail, retryAfterMs = retryAfterMs)
        texts.map { "$id:$it" }
    }

    private fun chain(vararg engines: ChainEngine) =
        FallbackChain(engines.toList(), clock = { now }, sleep = { slept += it; now += it })

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
    fun `primary answers - fail-safe not called`() = runBlocking {
        val r = chain(engine("ultra"), engine("super")).translate(listOf("a", "b"))
        assertEquals(listOf("ultra:a", "ultra:b"), r.translations)
        assertEquals("ultra", r.engine?.id)
        assertEquals(listOf("ultra"), calls)
    }

    @Test
    fun `primary fails - fail-safe answers`() = runBlocking {
        for (failure in listOf(OnlineFailure.SERVER, OnlineFailure.TIMEOUT, OnlineFailure.NETWORK, OnlineFailure.MALFORMED)) {
            calls.clear()
            val r = chain(engine("ultra", fail = failure), engine("super")).translate(listOf("a"))
            assertEquals(listOf("super:a"), r.translations)
            assertEquals(listOf("ultra" to failure), r.failures)
            assertEquals(listOf("ultra", "super"), calls)
        }
    }

    @Test
    fun `everything fails - null so the offline translation stays`() = runBlocking {
        val r = chain(engine("ultra", fail = OnlineFailure.SERVER), engine("super", fail = OnlineFailure.TIMEOUT)).translate(listOf("a"))
        assertNull(r.translations)
        assertNull(r.engine)
        assertEquals(2, r.failures.size)
    }

    @Test
    fun `open breaker skips the primary without a request`() = runBlocking {
        val broken = CircuitBreaker(failureThreshold = 1).apply { onFailure(OnlineFailure.SERVER, now = 0) }
        now = 1_000
        val r = chain(engine("ultra", breaker = broken), engine("super")).translate(listOf("a"))
        assertEquals("super", r.engine?.id)
        assertEquals(listOf("super"), calls)
        assertEquals(listOf("ultra" to OnlineFailure.CIRCUIT_OPEN), r.failures)
    }

    @Test
    fun `429 pauses the whole account - fail-safe on the same account not called`() = runBlocking {
        val account = CircuitBreaker(failureThreshold = Int.MAX_VALUE)
        val ultra = engine("ultra", account = account, fail = OnlineFailure.QUOTA, retryAfterMs = 20_000)
        val superE = engine("super", account = account)
        val r = chain(ultra, superE).translate(listOf("a"))
        assertNull(r.translations)
        assertEquals(listOf("ultra"), calls)
        assertEquals(OnlineFailure.CIRCUIT_OPEN, r.failures.last().second)
        now = 20_000
        assertEquals("ultra", chain(engine("ultra", account = account), superE).translate(listOf("a")).engine?.id)
    }

    @Test
    fun `rate limiter - short wait is slept, long wait gives up without calling`() = runBlocking {
        val limiter = RateLimiter(maxRequests = 1, windowMs = 60_000)
        limiter.acquire(0)
        now = 58_000 // 2 s left: worth waiting
        val r = chain(engine("ultra", limiter = limiter)).translate(listOf("a"))
        assertEquals(listOf(2_000L), slept)
        assertEquals("ultra", r.engine?.id)
        now = 70_000 // window full again until 120 s: 50 s wait, not worth it
        calls.clear()
        val r2 = chain(engine("ultra", limiter = limiter), engine("super", limiter = limiter)).translate(listOf("a"))
        assertNull(r2.translations)
        assertTrue(calls.isEmpty())
        assertEquals(listOf("ultra" to OnlineFailure.RATE_LIMITED), r2.failures)
    }
}

package com.translateoverlay.translate

/**
 * Online engines are free trials shared with other uses of the same account (NVIDIA: 40 requests
 * per minute for the whole account): the app limits itself, backs off and stops calling a failing
 * service. Pure logic with an injected clock (unit-tested).
 */

/** At most [maxRequests] request starts in any [windowMs] (sliding window). Not thread-safe: main thread. */
class RateLimiter(private val maxRequests: Int, private val windowMs: Long) {
    private val starts = ArrayDeque<Long>()

    /** Milliseconds to wait before a request may start (0 = now). */
    fun waitMs(now: Long): Long {
        while (starts.isNotEmpty() && now - starts.first() >= windowMs) starts.removeFirst()
        return if (starts.size < maxRequests) 0 else starts.first() + windowMs - now
    }

    /** Records a request start; call only when [waitMs] returned 0. */
    fun acquire(now: Long) {
        starts.addLast(now)
    }
}

/**
 * Closed → open after [failureThreshold] consecutive failures (or at once on a quota / key
 * error), for a cooldown that doubles on each re-opening up to [maxCooldownMs]; after the
 * cooldown one trial request is let through (half-open) and a success closes it again.
 */
class CircuitBreaker(
    private val failureThreshold: Int = 2,
    private val baseCooldownMs: Long = 30_000,
    private val maxCooldownMs: Long = 10 * 60_000,
) {
    private var failures = 0
    private var openUntil = 0L
    private var cooldownMs = baseCooldownMs

    fun allows(now: Long): Boolean = now >= openUntil

    fun isOpen(now: Long): Boolean = !allows(now)

    fun onSuccess() {
        failures = 0
        cooldownMs = baseCooldownMs
    }

    fun onFailure(failure: OnlineFailure, now: Long, retryAfterMs: Long? = null) {
        when (failure) {
            // Content-specific (a block the model answered badly): says nothing about the service.
            OnlineFailure.MALFORMED, OnlineFailure.RATE_LIMITED, OnlineFailure.CIRCUIT_OPEN, OnlineFailure.NO_KEY -> return
            OnlineFailure.QUOTA -> open(now, retryAfterMs ?: cooldownMs)
            OnlineFailure.BAD_KEY -> open(now, maxCooldownMs)
            else -> if (++failures >= failureThreshold) open(now, cooldownMs)
        }
    }

    /** Key changed in the settings: forget past errors. */
    fun reset() {
        failures = 0
        openUntil = 0
        cooldownMs = baseCooldownMs
    }

    private fun open(now: Long, forMs: Long) {
        openUntil = now + forMs.coerceIn(1_000, maxCooldownMs)
        cooldownMs = (cooldownMs * 2).coerceAtMost(maxCooldownMs)
        failures = 0
    }
}

/**
 * One online engine in a fallback chain.
 * @property breaker per model; [accountBreaker] is shared by the models of one account (a 429
 *   is an account quota: the fail-safe model on the same account must not be hammered either).
 */
class ChainEngine(
    val id: String,
    val label: String,
    val breaker: CircuitBreaker,
    val limiter: RateLimiter?,
    val accountBreaker: CircuitBreaker? = null,
    val call: suspend (List<String>) -> List<String>,
)

data class ChainResult(
    /** null when every engine failed: the offline translation stays. */
    val translations: List<String>?,
    val engine: ChainEngine?,
    val failures: List<Pair<String, OnlineFailure>>,
)

/** Tries each engine in order (primary, then fail-safe); never throws [OnlineTranslationException]. */
class FallbackChain(
    private val engines: List<ChainEngine>,
    private val clock: () -> Long,
    private val sleep: suspend (Long) -> Unit,
    /** Longer than this, waiting for the rate limiter is not worth it: the offline text is shown. */
    private val maxLimiterWaitMs: Long = 3_000,
) {
    suspend fun translate(texts: List<String>): ChainResult {
        val failures = ArrayList<Pair<String, OnlineFailure>>()
        for (engine in engines) {
            val now = clock()
            if (!engine.breaker.allows(now) || engine.accountBreaker?.allows(now) == false) {
                failures += engine.id to OnlineFailure.CIRCUIT_OPEN
                continue
            }
            engine.limiter?.let { limiter ->
                val wait = limiter.waitMs(now)
                if (wait > maxLimiterWaitMs) {
                    failures += engine.id to OnlineFailure.RATE_LIMITED
                    return ChainResult(null, null, failures) // same limit for the next engines
                }
                if (wait > 0) sleep(wait)
                limiter.acquire(clock())
            }
            try {
                val out = engine.call(texts)
                engine.breaker.onSuccess()
                engine.accountBreaker?.onSuccess()
                return ChainResult(out, engine, failures)
            } catch (e: OnlineTranslationException) {
                failures += engine.id to e.failure
                engine.breaker.onFailure(e.failure, clock(), e.retryAfterMs)
                if (e.failure == OnlineFailure.QUOTA || e.failure == OnlineFailure.BAD_KEY) {
                    engine.accountBreaker?.onFailure(e.failure, clock(), e.retryAfterMs)
                }
            }
        }
        return ChainResult(null, null, failures)
    }
}

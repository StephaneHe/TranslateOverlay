package com.translateoverlay.core

/**
 * Progressive translation: the offline translation (ML Kit) is shown at once, then each block is
 * replaced as the online engine's answer arrives. Pure logic (unit-tested).
 */

/** A block of the overlay waiting for a better translation. @property index position in the overlay. */
data class RefineItem(val index: Int, val text: String, val source: String, val top: Int, val left: Int)

object RefineChunks {
    /**
     * Requests per source language, top of the screen first (read first, and the first chunk to
     * come back), [itemsPerChunk] blocks each (more on a crowded screen: about [targetRequests]
     * requests per language, few of the rate limit) and at most [maxChars] characters.
     */
    fun plan(items: List<RefineItem>, itemsPerChunk: Int, maxChars: Int, targetRequests: Int = 3): List<List<RefineItem>> =
        items.groupBy { it.source }.values
            .sortedBy { group -> group.minOf { it.top } }
            .flatMap { group ->
                val ordered = group.sortedWith(compareBy({ it.top }, { it.left }))
                val perChunk = maxOf(itemsPerChunk, (ordered.size + targetRequests - 1) / targetRequests)
                split(ordered, perChunk, maxChars)
            }
            .sortedBy { chunk -> chunk.first().top }

    private fun split(items: List<RefineItem>, maxItems: Int, maxChars: Int): List<List<RefineItem>> {
        val out = ArrayList<List<RefineItem>>()
        var current = ArrayList<RefineItem>()
        var chars = 0
        for (item in items) {
            if (current.isNotEmpty() && (current.size >= maxItems || chars + item.text.length > maxChars)) {
                out += current
                current = ArrayList()
                chars = 0
            }
            current += item
            chars += item.text.length
        }
        if (current.isNotEmpty()) out += current
        return out
    }
}

/**
 * Which engine translated each block of the overlay, and the caption describing it.
 * @param ranks lower = better; a block is only replaced by a better-ranked engine.
 */
class RefinementProgress(
    private val baseEngine: String,
    pending: Collection<Int>,
    private val ranks: Map<String, Int>,
) {
    private val engineOf = HashMap<Int, String>()
    private val waiting = pending.toMutableSet()
    private val total = pending.size
    private var lastProblem: String? = null

    val isDone: Boolean get() = waiting.isEmpty()

    /** Records [engine]'s answer for [index]; false when the block already has a better one. */
    fun offer(index: Int, engine: String): Boolean {
        waiting -= index
        val current = engineOf[index]
        if (current != null && rank(current) <= rank(engine)) return false
        engineOf[index] = engine
        return true
    }

    /** Every engine failed for these blocks: the offline translation stays. */
    fun failed(indices: Collection<Int>, reason: String) {
        waiting -= indices.toSet()
        lastProblem = reason
    }

    fun caption(): String {
        val counts = engineOf.values.groupingBy { it }.eachCount().entries.sortedBy { rank(it.key) }
        val improved = counts.sumOf { it.value }
        if (!isDone) {
            return if (improved == 0) "$baseEngine · amélioration…"
            else "${counts.first().key} ($improved/$total) · amélioration…"
        }
        val offline = total - improved
        return when {
            improved == 0 -> baseEngine + (lastProblem?.let { " ($it)" } ?: "")
            counts.size == 1 && offline == 0 -> counts.first().key
            else -> (counts.map { "${it.key} ${it.value}" } + listOfNotNull(offline.takeIf { it > 0 }?.let { "$baseEngine $it" }))
                .joinToString(" · ")
        }
    }

    /** Tier of the overlay block [index] (blocks not tracked here keep their offline text). */
    fun tier(index: Int): EngineTier = when (index) {
        in engineOf -> EngineTier.of(engineOf[index], ranks)
        in waiting -> EngineTier.PENDING
        else -> EngineTier.OFFLINE
    }

    private fun rank(engine: String): Int = ranks[engine] ?: Int.MAX_VALUE
}

/**
 * Quality tier of the engine that translated a block, shown on the overlay (colour + shape +
 * letter, so it does not rely on colour alone). Ordered best → worst.
 */
enum class EngineTier(val letter: String) {
    /** The primary online model (NVIDIA: Nemotron Ultra). */
    BEST("U"),
    /** A fail-safe online model (Nemotron Super). */
    FALLBACK("S"),
    /** Offline ML Kit translation (no key, no network, online engines failed, or chosen). */
    OFFLINE("K"),
    /** Offline translation shown, online one still expected. */
    PENDING("…");

    companion object {
        /** @param engine online engine label, null for ML Kit; [ranks]: engine label → 0 for the primary. */
        fun of(engine: String?, ranks: Map<String, Int>): EngineTier = when (engine?.let { ranks[it] }) {
            null -> OFFLINE
            0 -> BEST
            else -> FALLBACK
        }

        /** Whole-screen state: still improving while any block is pending, else the worst block. */
        fun overall(tiers: Collection<EngineTier>): EngineTier = when {
            tiers.isEmpty() -> OFFLINE
            PENDING in tiers -> PENDING
            else -> tiers.maxOf { it }
        }
    }
}

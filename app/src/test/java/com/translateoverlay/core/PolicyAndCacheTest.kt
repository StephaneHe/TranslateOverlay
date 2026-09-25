package com.translateoverlay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyAndCacheTest {
    private val own = "com.translateoverlay"
    private val transient = setOf("com.android.systemui", "com.google.android.inputmethod.latin")

    private fun show(fg: String?, excluded: Set<String> = emptySet(), enabled: Boolean = true, current: Boolean = true) =
        BubbleVisibilityPolicy.shouldShow(enabled, fg, excluded, own, transient, current)

    @Test
    fun `bubble shown on regular apps and hidden on excluded ones`() {
        assertTrue(show("com.twitter.android"))
        assertFalse(show("com.bank.app", excluded = setOf("com.bank.app")))
    }

    @Test
    fun `bubble hidden when disabled and in own app`() {
        assertFalse(show("com.twitter.android", enabled = false))
        assertFalse(show(own))
    }

    @Test
    fun `transient or unknown foreground keeps current state`() {
        assertFalse(show("com.android.systemui", current = false))
        assertTrue(show("com.google.android.inputmethod.latin", current = true))
        assertFalse(show(null, current = false))
    }

    @Test
    fun `cache evicts least recently used entry`() {
        val cache = TranslationCache(capacity = 2)
        cache.put("en", "fr", "a", "A")
        cache.put("en", "fr", "b", "B")
        cache.get("en", "fr", "a") // touch a
        cache.put("en", "fr", "c", "C")
        assertEquals("A", cache.get("en", "fr", "a"))
        assertNull(cache.get("en", "fr", "b"))
        assertEquals("C", cache.get("en", "fr", "c"))
        assertEquals(2, cache.size)
    }

    @Test
    fun `cache is keyed by language pair`() {
        val cache = TranslationCache()
        cache.put("en", "fr", "Hello", "Bonjour")
        assertNull(cache.get("en", "de", "Hello"))
    }

    @Test
    fun `box geometry`() {
        val a = Box(0, 0, 10, 10)
        val b = Box(5, 5, 20, 20)
        assertEquals(25L, a.intersectionArea(b))
        assertEquals(0L, a.intersectionArea(Box(20, 20, 30, 30)))
        assertEquals(Box(0, 0, 20, 20), a.union(b))
        assertTrue(Box(0, 0, 100, 100).contains(a))
        assertTrue(a.containsPoint(0, 0))
        assertFalse(a.containsPoint(10, 10))
    }
}

package com.translateoverlay.core

/** Small LRU cache so re-translating the same screen is instant. Not thread-safe; use from one thread. */
class TranslationCache(private val capacity: Int = 500) {
    private data class Key(val source: String, val target: String, val text: String)

    private val map = object : LinkedHashMap<Key, String>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, String>?) = size > capacity
    }

    fun get(source: String, target: String, text: String): String? = map[Key(source, target, text)]

    fun put(source: String, target: String, text: String, translation: String) {
        map[Key(source, target, text)] = translation
    }

    val size: Int get() = map.size

    fun clear() = map.clear()
}

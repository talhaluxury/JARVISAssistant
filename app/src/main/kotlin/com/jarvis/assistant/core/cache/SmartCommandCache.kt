package com.jarvis.assistant.core.cache

import java.util.concurrent.ConcurrentHashMap

/** Small TTL cache for safe interpretations. Call invalidate when permissions/capabilities change. */
class SmartCommandCache<K, V>(private val ttlMs: Long = 5 * 60_000L, private val maxEntries: Int = 100) {
    private data class Entry<V>(val value: V, val expiresAt: Long)
    private val map = ConcurrentHashMap<K, Entry<V>>()
    fun get(key: K): V? = map[key]?.takeIf { it.expiresAt > System.currentTimeMillis() }?.value
        ?: run { map.remove(key); null }
    fun put(key: K, value: V) { if (map.size >= maxEntries) map.keys.firstOrNull()?.let(map::remove); map[key] = Entry(value, System.currentTimeMillis() + ttlMs) }
    fun invalidate(key: K) { map.remove(key) }
    fun clear() { map.clear() }
}

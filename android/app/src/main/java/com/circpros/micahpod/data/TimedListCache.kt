package com.circpros.micahpod.data

import android.content.SharedPreferences
import com.google.gson.Gson
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory + SharedPreferences list cache with TTL for instant tab switches.
 */
class TimedListCache(
    private val prefs: SharedPreferences,
    private val gson: Gson
) {
    private data class MemoryEntry(val json: String, val fetchedAtMs: Long)

    private val memory = ConcurrentHashMap<String, MemoryEntry>()

    companion object {
        const val TTL_DEFAULT_MS = 10 * 60 * 1000L
        const val TTL_CONTINUE_MS = 2 * 60 * 1000L
        private const val TS_PREFIX = "cache_ts_"
    }

    fun put(key: String, json: String, fetchedAtMs: Long = System.currentTimeMillis()) {
        memory[key] = MemoryEntry(json, fetchedAtMs)
        prefs.edit()
            .putString(key, json)
            .putLong(TS_PREFIX + key, fetchedAtMs)
            .apply()
    }

    fun getJson(key: String): Pair<String, Long>? {
        memory[key]?.let { return it.json to it.fetchedAtMs }
        val json = prefs.getString(key, null) ?: return null
        val ts = prefs.getLong(TS_PREFIX + key, 0L)
        if (ts <= 0L) {
            // Legacy cache without timestamp — treat as immediately stale but usable.
            val now = System.currentTimeMillis()
            memory[key] = MemoryEntry(json, 0L)
            return json to 0L
        }
        memory[key] = MemoryEntry(json, ts)
        return json to ts
    }

    fun <T> get(key: String, clazz: Class<T>): Pair<T, Long>? {
        val (json, ts) = getJson(key) ?: return null
        val parsed = runCatching { gson.fromJson(json, clazz) }.getOrNull() ?: return null
        return parsed to ts
    }

    inline fun <reified T> get(key: String): Pair<T, Long>? = get(key, T::class.java)

    fun ageMs(key: String): Long? {
        val entry = memory[key]
        val ts = entry?.fetchedAtMs ?: prefs.getLong(TS_PREFIX + key, -1L)
        if (ts < 0L && prefs.getString(key, null) == null) return null
        if (ts <= 0L) return Long.MAX_VALUE // legacy / unknown → force refresh
        return (System.currentTimeMillis() - ts).coerceAtLeast(0L)
    }

    fun isFresh(key: String, ttlMs: Long = TTL_DEFAULT_MS): Boolean {
        val age = ageMs(key) ?: return false
        return age < ttlMs
    }

    fun invalidate(key: String) {
        memory.remove(key)
        prefs.edit().remove(key).remove(TS_PREFIX + key).apply()
    }

    fun invalidateKeys(vararg keys: String) {
        keys.forEach { invalidate(it) }
    }
}

package com.circpros.micahpod.playback

import com.circpros.micahpod.data.Episode
import com.circpros.micahpod.data.QueueEpisode
import com.google.gson.Gson
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory queue mirrored to server player_state.queue (same shape as the web app).
 */
class QueueManager(private val gson: Gson) {
    private val items = CopyOnWriteArrayList<QueueEpisode>()
    @Volatile
    var updatedAtUtc: String? = null
        private set

    fun snapshot(): List<QueueEpisode> = items.toList()

    fun size(): Int = items.size

    fun replaceFromServer(queue: List<QueueEpisode>?, queueUpdatedAt: String?) {
        items.clear()
        if (!queue.isNullOrEmpty()) {
            items.addAll(queue.filter { !it.audioUrl.isNullOrBlank() || it.id != null })
        }
        if (!queueUpdatedAt.isNullOrBlank()) {
            updatedAtUtc = queueUpdatedAt
        }
    }

    fun add(episode: Episode, playNext: Boolean = false): Boolean {
        val q = QueueEpisode.fromEpisode(episode)
        if (q.audioUrl.isNullOrBlank() && q.id == null) return false
        val key = q.key()
        val existing = items.indexOfFirst { it.key() == key }
        if (existing >= 0) {
            if (playNext && existing > 0) {
                val moved = items.removeAt(existing)
                items.add(0, moved)
                bumpUpdatedAt()
                return true
            }
            if (playNext && existing == 0) return true
            return false
        }
        if (playNext) items.add(0, q) else items.add(q)
        bumpUpdatedAt()
        return true
    }

    /** Force-prepend (used when a queue advance fails after retries). */
    fun prepend(item: QueueEpisode) {
        val key = item.key()
        val existing = items.indexOfFirst { it.key() == key }
        if (existing >= 0) {
            val moved = items.removeAt(existing)
            items.add(0, moved)
        } else {
            items.add(0, item)
        }
        bumpUpdatedAt()
    }

    fun removeAt(index: Int): QueueEpisode? {
        if (index !in items.indices) return null
        val removed = items.removeAt(index)
        bumpUpdatedAt()
        return removed
    }

    fun clear() {
        items.clear()
        bumpUpdatedAt()
    }

    /** Remove and return the next queued episode, or null if empty. */
    fun popNext(): QueueEpisode? {
        if (items.isEmpty()) return null
        val next = items.removeAt(0)
        bumpUpdatedAt()
        return next
    }

    fun toJsonArray(): String = gson.toJson(snapshot())

    fun clientUpdatedAt(): String {
        val existing = updatedAtUtc
        if (!existing.isNullOrBlank()) return existing
        return nowUtc()
    }

    private fun bumpUpdatedAt() {
        updatedAtUtc = nowUtc()
    }

    private fun nowUtc(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }
}

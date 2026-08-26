package com.circpros.micahpod.playback

import android.content.Context
import android.content.SharedPreferences

/**
 * Survives process death so a paused episode can be restored at the last playhead
 * even before the network bookmark round-trip finishes (Razr cover / cold start).
 */
object LocalPlaybackBookmark {
    private const val PREFS = "micahpod_local_bookmark"
    private const val KEY_EPISODE_ID = "episode_id"
    private const val KEY_POSITION_MS = "position_ms"
    private const val KEY_TITLE = "title"
    private const val KEY_PODCAST = "podcast"
    private const val KEY_AUDIO_URL = "audio_url"
    private const val KEY_ARTWORK_URL = "artwork_url"
    private const val KEY_UPDATED_AT_MS = "updated_at_ms"

    data class Bookmark(
        val episodeId: Int,
        val positionMs: Long,
        val title: String? = null,
        val podcastTitle: String? = null,
        val audioUrl: String? = null,
        val artworkUrl: String? = null,
        val updatedAtMs: Long = 0L
    )

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(
        context: Context,
        episodeId: Int,
        positionMs: Long,
        title: String? = null,
        podcastTitle: String? = null,
        audioUrl: String? = null,
        artworkUrl: String? = null
    ) {
        if (episodeId <= 0 || positionMs < 5_000L) return
        prefs(context).edit()
            .putInt(KEY_EPISODE_ID, episodeId)
            .putLong(KEY_POSITION_MS, positionMs)
            .putString(KEY_TITLE, title)
            .putString(KEY_PODCAST, podcastTitle)
            .putString(KEY_AUDIO_URL, audioUrl)
            .putString(KEY_ARTWORK_URL, artworkUrl)
            .putLong(KEY_UPDATED_AT_MS, System.currentTimeMillis())
            .apply()
    }

    fun read(context: Context): Bookmark? {
        val p = prefs(context)
        val id = p.getInt(KEY_EPISODE_ID, 0)
        val pos = p.getLong(KEY_POSITION_MS, 0L)
        if (id <= 0 || pos < 5_000L) return null
        return Bookmark(
            episodeId = id,
            positionMs = pos,
            title = p.getString(KEY_TITLE, null),
            podcastTitle = p.getString(KEY_PODCAST, null),
            audioUrl = p.getString(KEY_AUDIO_URL, null),
            artworkUrl = p.getString(KEY_ARTWORK_URL, null),
            updatedAtMs = p.getLong(KEY_UPDATED_AT_MS, 0L)
        )
    }

    fun positionForEpisode(context: Context, episodeId: Int): Long? {
        val b = read(context) ?: return null
        return if (b.episodeId == episodeId) b.positionMs else null
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}

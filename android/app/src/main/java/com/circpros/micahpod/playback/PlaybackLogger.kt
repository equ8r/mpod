package com.circpros.micahpod.playback

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Simple append-only playback event log for diagnosing stop/start/progress issues.
 * Also mirrors to Logcat under tag MicahPodPlayback.
 */
object PlaybackLogger {
    private const val TAG = "MicahPodPlayback"
    private const val FILE_NAME = "playback_events.log"
    private const val MAX_BYTES = 256 * 1024
    private val lock = ReentrantLock()
    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun log(
        context: Context,
        event: String,
        reason: String,
        episodeId: String? = null,
        title: String? = null,
        podcast: String? = null,
        positionMs: Long? = null,
        durationMs: Long? = null,
        extra: String? = null
    ) {
        val pos = positionMs?.let { formatClock(it) } ?: "-"
        val dur = durationMs?.takeIf { it > 0 }?.let { formatClock(it) } ?: "-"
        val line = buildString {
            append(timeFmt.format(Date()))
            append(" | ").append(event.uppercase(Locale.US))
            append(" | reason=").append(reason)
            append(" | ep=").append(episodeId ?: "-")
            append(" | \"").append((title ?: "-").replace('|', '/')).append("\"")
            append(" | podcast=\"").append((podcast ?: "-").replace('|', '/')).append("\"")
            append(" | pos=").append(pos)
            append(" | dur=").append(dur)
            if (!extra.isNullOrBlank()) append(" | ").append(extra.replace('\n', ' '))
        }
        Log.i(TAG, line)
        lock.withLock {
            try {
                val file = File(context.filesDir, FILE_NAME)
                if (file.exists() && file.length() > MAX_BYTES) {
                    val keep = file.readText().takeLast(MAX_BYTES / 2)
                    file.writeText(keep)
                }
                file.appendText(line + "\n")
            } catch (e: Exception) {
                Log.w(TAG, "Failed writing playback log", e)
            }
        }
    }

    fun readRecent(context: Context, maxChars: Int = 24_000): String {
        return lock.withLock {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return@withLock "No playback events yet."
            val text = runCatching { file.readText() }.getOrElse { return@withLock "Unable to read log." }
            if (text.length <= maxChars) text else text.takeLast(maxChars)
        }
    }

    fun clear(context: Context) {
        lock.withLock {
            File(context.filesDir, FILE_NAME).delete()
        }
    }

    private fun formatClock(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }
}

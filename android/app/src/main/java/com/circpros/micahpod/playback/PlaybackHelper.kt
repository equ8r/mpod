package com.circpros.micahpod.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.circpros.micahpod.data.Episode
import com.circpros.micahpod.data.ServiceLocator
import com.google.common.util.concurrent.ListenableFuture

object PlaybackHelper {
    private const val TAG = "PlaybackHelper"

    fun playEpisode(
        context: Context,
        episode: Episode,
        startPositionSeconds: Double? = null,
        autoPlay: Boolean = true,
        onReady: ((MediaController) -> Unit)? = null
    ) {
        val audioUrl = episode.audioUrl
        if (audioUrl.isNullOrBlank()) {
            Log.w(TAG, "Episode ${episode.id} has no audio URL")
            return
        }
        ServiceLocator.episodeCache[episode.id] = episode

        val localMs = LocalPlaybackBookmark.positionForEpisode(context, episode.id)
        val requestedMs = ((startPositionSeconds ?: 0.0).coerceAtLeast(0.0) * 1000.0).toLong()
        val startMs = maxOf(requestedMs, localMs ?: 0L)

        val token = SessionToken(context, ComponentName(context, MicahPodMediaService::class.java))
        val future: ListenableFuture<MediaController> =
            MediaController.Builder(context, token).buildAsync()
        future.addListener({
            try {
                val controller = future.get()
                val artUri = (episode.artworkUrl ?: episode.podcastArtwork)?.let { Uri.parse(it) }
                val mediaItem = MediaItem.Builder()
                    .setMediaId("episode:${episode.id}")
                    .setUri(audioUrl)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(episode.title)
                            .setAlbumTitle(episode.podcastTitle)
                            .setArtist(episode.podcastTitle)
                            .setArtworkUri(artUri)
                            .setIsPlayable(true)
                            .build()
                    )
                    .build()
                controller.setMediaItem(mediaItem, startMs)
                controller.prepare()
                if (startMs > 2_500L) {
                    // Paused prepares on some devices ignore startPosition until an explicit seek.
                    controller.seekTo(startMs)
                    val seekAgain = object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING) {
                                val pos = controller.currentPosition.coerceAtLeast(0)
                                if (pos < 5_000L && startMs > 5_000L) {
                                    Log.d(TAG, "Re-seek after prepare to ${startMs}ms (was ${pos}ms)")
                                    controller.seekTo(startMs)
                                }
                                controller.removeListener(this)
                            }
                        }
                    }
                    controller.addListener(seekAgain)
                    LocalPlaybackBookmark.save(
                        context = context,
                        episodeId = episode.id,
                        positionMs = startMs,
                        title = episode.title,
                        podcastTitle = episode.podcastTitle,
                        audioUrl = audioUrl,
                        artworkUrl = episode.artworkUrl ?: episode.podcastArtwork
                    )
                }
                if (autoPlay) {
                    controller.play()
                } else {
                    controller.pause()
                }
                onReady?.invoke(controller)
            } catch (e: Exception) {
                Log.e(TAG, "playEpisode failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun togglePlayPause(controller: MediaController?) {
        val c = controller ?: return
        try {
            when {
                c.isPlaying -> c.pause()
                c.playbackState == Player.STATE_IDLE -> {
                    if (c.mediaItemCount > 0) {
                        c.prepare()
                        c.play()
                    }
                }
                c.playbackState == Player.STATE_ENDED -> {
                    c.seekTo(0)
                    c.play()
                }
                c.mediaItemCount > 0 -> c.play()
                else -> Log.w(TAG, "togglePlayPause: nothing loaded")
            }
        } catch (e: Exception) {
            Log.e(TAG, "togglePlayPause failed", e)
        }
    }

    fun formatClock(seconds: Double): String {
        val total = seconds.toLong().coerceAtLeast(0)
        val hrs = total / 3600
        val mins = (total % 3600) / 60
        val secs = total % 60
        return if (hrs > 0) {
            String.format("%d:%02d:%02d", hrs, mins, secs)
        } else {
            String.format("%d:%02d", mins, secs)
        }
    }
}

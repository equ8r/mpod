package com.circpros.micahpod.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionResult
import com.circpros.micahpod.R
import com.circpros.micahpod.data.Episode
import com.circpros.micahpod.data.Podcast
import com.circpros.micahpod.data.ServiceLocator
import com.circpros.micahpod.repository.RepositoryResult
import com.circpros.micahpod.ui.MainActivity
import com.google.android.gms.cast.framework.CastContext
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

class MicahPodMediaService : MediaLibraryService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var exoPlayer: ExoPlayer
    private var castPlayer: CastPlayer? = null
    private var activeUnderlyingPlayer: Player? = null
    /** Session-facing player: cover/lock-screen prev/next become ±30s (podcast-style). */
    private lateinit var player: Player
    private lateinit var session: MediaLibraryService.MediaLibrarySession
    private val repository by lazy { ServiceLocator.repository }
    private val authManager by lazy { ServiceLocator.authManager }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val playerEventListener = PlayerEventListener()

    private val podcastCache = mutableMapOf<Int, Podcast>()
    private var currentEpisodeId: String? = null
    private var lastProgressPosition: Long = -1L
    private var lastProgressAtMs: Long = 0L
    /** Last solid playhead for the current episode — used to ignore false ENDED/0:00 reloads. */
    private var lastMeaningfulPositionMs: Long = 0L
    private var lastMeaningfulEpisodeId: String? = null
    private var mediaTransitionAtMs: Long = 0L
    private var suppressEndedUntilMs: Long = 0L
    private var queueAdvanceInFlight: com.circpros.micahpod.data.QueueEpisode? = null
    private var queueAdvanceRetryCount: Int = 0

    companion object {
        private const val TAG = "MicahPodMediaService"
        private const val ROOT_ID = "root"
        private const val PODCAST_PREFIX = "podcast:"
        private const val EPISODE_PREFIX = "episode:"
        private const val HEARTBEAT_MS = 30_000L
        private const val SKIP_MS = 30_000L
        /** Auto progress below this is ignored so media-load 0:00 cannot wipe bookmarks. */
        private const val MIN_AUTO_SAVE_MS = 5_000L
        private const val FALSE_ENDED_GUARD_MS = 4_000L
        private const val NEAR_END_TOLERANCE_MS = 15_000L
        private const val QUEUE_RETRY_MAX = 3
        private const val QUEUE_RETRY_DELAY_MS = 2_000L
    }

    /**
     * Cover screens / System UI only offer prev · play/pause · next.
     * Remap prev/next to ±30s so they don't restart/skip tracks.
     */
    private class PodcastSkipPlayer(player: Player) : ForwardingPlayer(player) {
        override fun seekToPrevious() {
            seekBack()
        }

        override fun seekToNext() {
            seekForward()
        }

        override fun seekToPreviousMediaItem() {
            seekBack()
        }

        override fun seekToNextMediaItem() {
            seekForward()
        }

        override fun getAvailableCommands(): Player.Commands {
            return super.getAvailableCommands()
                .buildUpon()
                .add(COMMAND_SEEK_BACK)
                .add(COMMAND_SEEK_FORWARD)
                .add(COMMAND_SEEK_TO_PREVIOUS)
                .add(COMMAND_SEEK_TO_NEXT)
                .build()
        }

        override fun isCommandAvailable(command: @Player.Command Int): Boolean {
            return when (command) {
                COMMAND_SEEK_BACK,
                COMMAND_SEEK_FORWARD,
                COMMAND_SEEK_TO_PREVIOUS,
                COMMAND_SEEK_TO_NEXT -> true
                else -> super.isCommandAvailable(command)
            }
        }
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            if (::player.isInitialized && player.isPlaying) {
                sendProgressIfNeeded(force = true, source = "heartbeat")
            }
            mainHandler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Media service onCreate")

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(SKIP_MS)
            .setSeekForwardIncrementMs(SKIP_MS)
            .build()
        exoPlayer.addListener(playerEventListener)
        exoPlayer.playWhenReady = false

        setupCastPlayer()

        activeUnderlyingPlayer = exoPlayer
        player = PodcastSkipPlayer(exoPlayer)

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        @OptIn(androidx.media3.common.util.UnstableApi::class)
        run {
            val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
                .setNotificationId(0x4D504F44) // "MPOD"
                .build()
            setMediaNotificationProvider(notificationProvider)
        }

        session = MediaLibraryService.MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(sessionActivity)
            .build()

        updateSessionButtons()
        mainHandler.postDelayed(heartbeat, HEARTBEAT_MS)
    }

    private fun setupCastPlayer() {
        try {
            val castContext = CastContext.getSharedInstance(this)
            val remote = CastPlayer(castContext)
            remote.setSessionAvailabilityListener(object : SessionAvailabilityListener {
                override fun onCastSessionAvailable() {
                    mainHandler.post { setActiveUnderlyingPlayer(remote) }
                }

                override fun onCastSessionUnavailable() {
                    mainHandler.post { setActiveUnderlyingPlayer(exoPlayer) }
                }
            })
            remote.addListener(playerEventListener)
            castPlayer = remote
            Log.d(TAG, "CastPlayer ready")
        } catch (t: Throwable) {
            Log.w(TAG, "Cast unavailable on this device", t)
            castPlayer = null
        }
    }

    /**
     * Moves the current queue + playhead between ExoPlayer and CastPlayer when a Cast
     * session starts or ends, then points MediaSession at the active player.
     */
    private fun setActiveUnderlyingPlayer(newPlayer: Player) {
        if (!::session.isInitialized) return
        val previous = activeUnderlyingPlayer
        if (previous === newPlayer) return

        Log.d(TAG, "Switching player to ${newPlayer.javaClass.simpleName}")

        var mediaItems = emptyList<MediaItem>()
        var startIndex = 0
        var startPositionMs = 0L
        var playWhenReady = false
        if (previous != null) {
            val count = previous.mediaItemCount
            if (count > 0) {
                mediaItems = (0 until count).map { previous.getMediaItemAt(it) }
                startIndex = previous.currentMediaItemIndex.coerceIn(0, count - 1)
                startPositionMs = previous.currentPosition.coerceAtLeast(0)
                playWhenReady = previous.playWhenReady
            }
            previous.playWhenReady = false
            previous.stop()
            previous.clearMediaItems()
        }

        activeUnderlyingPlayer = newPlayer
        player = PodcastSkipPlayer(newPlayer)
        session.setPlayer(player)

        if (mediaItems.isNotEmpty()) {
            newPlayer.setMediaItems(mediaItems, startIndex, startPositionMs)
            newPlayer.prepare()
            newPlayer.playWhenReady = playWhenReady
        }
        updateSessionButtons()
    }

    @androidx.media3.common.util.UnstableApi
    private fun updateSessionButtons() {
        if (!::session.isInitialized || !::player.isInitialized) return
        val playPauseIcon = if (player.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        // Prefer prev/next slots so cover + System UI pick them up; they map to ±30s via PodcastSkipPlayer.
        val buttons = ImmutableList.of(
            CommandButton.Builder()
                .setDisplayName("-30s")
                .setIconResId(R.drawable.ic_skip_back_15)
                .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS)
                .build(),
            CommandButton.Builder()
                .setDisplayName(if (player.isPlaying) "Pause" else "Play")
                .setIconResId(playPauseIcon)
                .setPlayerCommand(Player.COMMAND_PLAY_PAUSE)
                .build(),
            CommandButton.Builder()
                .setDisplayName("+30s")
                .setIconResId(R.drawable.ic_skip_forward_30)
                .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT)
                .build()
        )
        session.setCustomLayout(buttons)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibraryService.MediaLibrarySession {
        return session
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep playback alive when the task is swiped away while playing.
        if (!::player.isInitialized || !player.isPlaying) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.d(TAG, "Media service onDestroy")
        mainHandler.removeCallbacks(heartbeat)
        sendProgressIfNeeded(force = true, source = "auto")
        if (::session.isInitialized) {
            session.release()
        }
        castPlayer?.setSessionAvailabilityListener(null)
        castPlayer?.removeListener(playerEventListener)
        castPlayer?.release()
        castPlayer = null
        if (::exoPlayer.isInitialized) {
            exoPlayer.removeListener(playerEventListener)
            exoPlayer.release()
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private inner class LibraryCallback : MediaLibraryService.MediaLibrarySession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
            val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .add(Player.COMMAND_SEEK_BACK)
                .add(Player.COMMAND_SEEK_FORWARD)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .build()
            return MediaSession.ConnectionResult.accept(sessionCommands, playerCommands)
        }

        override fun onGetLibraryRoot(
            session: MediaLibraryService.MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val rootItem = MediaItem.Builder()
                .setMediaId(ROOT_ID)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("MicahPod")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .build()
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
        }

        override fun onGetChildren(
            session: MediaLibraryService.MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            if (!authManager.isLoggedIn()) {
                return Futures.immediateFuture(
                    LibraryResult.ofItemList(ImmutableList.of(), params)
                )
            }
            val future = com.google.common.util.concurrent.SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            serviceScope.launch {
                try {
                    val items = when {
                        parentId == ROOT_ID -> buildPodcastItemsAsync()
                        parentId.startsWith(PODCAST_PREFIX) -> {
                            val podcastId = parentId.removePrefix(PODCAST_PREFIX).toIntOrNull()
                            if (podcastId == null) ImmutableList.of() else buildEpisodeItemsAsync(podcastId)
                        }
                        else -> ImmutableList.of()
                    }
                    future.set(LibraryResult.ofItemList(items, params))
                } catch (e: Exception) {
                    Log.e(TAG, "onGetChildren failed parentId=$parentId", e)
                    future.set(LibraryResult.ofItemList(ImmutableList.of(), params))
                }
            }
            return future
        }

        override fun onGetItem(
            session: MediaLibraryService.MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val item = when {
                mediaId == ROOT_ID -> null
                mediaId.startsWith(PODCAST_PREFIX) -> {
                    val podcastId = mediaId.removePrefix(PODCAST_PREFIX).toIntOrNull()
                    podcastId?.let { podcastCache[it] }?.let { buildPodcastItem(it) }
                }
                mediaId.startsWith(EPISODE_PREFIX) -> {
                    val episodeId = mediaId.removePrefix(EPISODE_PREFIX).toIntOrNull()
                    episodeId?.let { ServiceLocator.episodeCache[it] }?.let { buildEpisodeItem(it) }
                }
                else -> null
            }
            return if (item != null) {
                Futures.immediateFuture(LibraryResult.ofItem(item, null))
            } else {
                Futures.immediateFuture(
                    LibraryResult.ofError(SessionResult.RESULT_ERROR_BAD_VALUE)
                )
            }
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            Log.d(
                TAG,
                "onSetMediaItems count=${mediaItems.size} startIndex=$startIndex startPositionMs=$startPositionMs"
            )
            val resolved = mediaItems.map { item ->
                if (item.localConfiguration != null) item else resolveMediaItem(item)
            }
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(resolved, startIndex, startPositionMs)
            )
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            // Help Android Auto / system resume after focus interruptions.
            val item = player.currentMediaItem
            return if (item != null) {
                Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(
                        listOf(item),
                        0,
                        player.currentPosition.coerceAtLeast(0)
                    )
                )
            } else {
                Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0)
                )
            }
        }
    }

    private suspend fun buildPodcastItemsAsync(): ImmutableList<MediaItem> {
        return when (val result = repository.getPodcasts()) {
            is RepositoryResult.Success -> {
                result.data.forEach { podcastCache[it.id] = it }
                ImmutableList.copyOf(result.data.map { buildPodcastItem(it) })
            }
            is RepositoryResult.Error -> ImmutableList.of()
        }
    }

    private suspend fun buildEpisodeItemsAsync(podcastId: Int): ImmutableList<MediaItem> {
        return when (val result = repository.getEpisodes(podcastId)) {
            is RepositoryResult.Success -> {
                result.data.episodes.forEach { ServiceLocator.episodeCache[it.id] = it }
                ImmutableList.copyOf(result.data.episodes.map { buildEpisodeItem(it) })
            }
            is RepositoryResult.Error -> ImmutableList.of()
        }
    }

    private fun buildPodcastItem(podcast: Podcast): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(podcast.title)
            .setArtworkUri(podcast.artworkUrl?.let { Uri.parse(it) })
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .build()
        return MediaItem.Builder()
            .setMediaId("$PODCAST_PREFIX${podcast.id}")
            .setMediaMetadata(metadata)
            .build()
    }

    private fun buildEpisodeItem(episode: Episode): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(episode.title)
            .setAlbumTitle(episode.podcastTitle)
            .setArtist(episode.podcastTitle)
            .setArtworkUri((episode.artworkUrl ?: episode.podcastArtwork)?.let { Uri.parse(it) })
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .build()
        return MediaItem.Builder()
            .setMediaId("$EPISODE_PREFIX${episode.id}")
            .setUri(episode.audioUrl)
            .setMediaMetadata(metadata)
            .build()
    }

    private fun resolveMediaItem(item: MediaItem): MediaItem {
        if (item.mediaId.startsWith(EPISODE_PREFIX)) {
            val episodeId = item.mediaId.removePrefix(EPISODE_PREFIX).toIntOrNull()
            val episode = episodeId?.let { ServiceLocator.episodeCache[it] }
            if (episode != null) {
                return buildEpisodeItem(episode)
            }
        }
        return item
    }

    private inner class PlayerEventListener : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            Log.d(TAG, "onMediaItemTransition reason=$reason mediaId=${mediaItem?.mediaId}")
            val newId = mediaItem?.mediaId?.removePrefix(EPISODE_PREFIX)
            mediaTransitionAtMs = System.currentTimeMillis()
            // Media replaces often emit a bogus ENDED/IDLE with empty metadata — ignore briefly.
            suppressEndedUntilMs = mediaTransitionAtMs + FALSE_ENDED_GUARD_MS
            if (newId != null && newId != lastMeaningfulEpisodeId) {
                lastMeaningfulPositionMs = 0L
            }
            currentEpisodeId = newId
            lastProgressPosition = -1L
            // Do NOT save here — ExoPlayer often still reports 0ms and that wiped bookmarks.
            logPlaybackEvent(
                event = "transition",
                reason = "media_item_$reason",
                extra = "suppressEndedMs=$FALSE_ENDED_GUARD_MS"
            )
            // Paused cold loads often sit at 0:00 until READY; also retry shortly after transition.
            mainHandler.postDelayed({ maybeRestoreFromMeaningfulBookmark() }, 400)
            mainHandler.postDelayed({ maybeRestoreFromMeaningfulBookmark() }, 1_200)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            Log.d(TAG, "onIsPlayingChanged isPlaying=$isPlaying state=${player.playbackState}")
            logPlaybackEvent(
                event = if (isPlaying) "start" else "stop",
                reason = if (isPlaying) "is_playing_true" else "is_playing_false"
            )
            if (isPlaying && player.playbackState == Player.STATE_READY) {
                rememberMeaningfulPosition()
                sendProgressIfNeeded(force = true, source = "auto", replaceSession = true)
            }
            mainHandler.post { updateSessionButtons() }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            val reasonName = when (reason) {
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> "user_request"
                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "audio_focus_loss"
                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "audio_becoming_noisy"
                Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> "remote"
                Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> "end_of_media_item"
                else -> "play_when_ready_$reason"
            }
            logPlaybackEvent(
                event = if (playWhenReady) "start" else "stop",
                reason = reasonName
            )
            if (!playWhenReady) {
                rememberMeaningfulPosition()
                sendProgressIfNeeded(force = true, source = "auto", replaceSession = true)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            Log.d(TAG, "onPlaybackStateChanged state=$playbackState")
            when (playbackState) {
                Player.STATE_READY -> {
                    maybeRestoreFromMeaningfulBookmark()
                    rememberMeaningfulPosition()
                    sendProgressIfNeeded(force = true, source = "auto", replaceSession = true)
                    // Successful prepare after a queue advance clears retry state.
                    if (queueAdvanceInFlight != null && player.currentPosition >= 0) {
                        queueAdvanceInFlight = null
                        queueAdvanceRetryCount = 0
                    }
                }
                Player.STATE_ENDED -> {
                    handleEndedState()
                }
                Player.STATE_IDLE -> {
                    if (System.currentTimeMillis() < suppressEndedUntilMs) {
                        logPlaybackEvent(event = "stop", reason = "idle_suppressed_after_transition")
                        return
                    }
                    logPlaybackEvent(event = "stop", reason = "idle")
                    sendProgressIfNeeded(force = true, source = "auto")
                }
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                logPlaybackEvent(
                    event = "seek",
                    reason = "user_or_system_seek",
                    extra = "from=${oldPosition.positionMs}ms to=${newPosition.positionMs}ms"
                )
                if (newPosition.positionMs >= MIN_AUTO_SAVE_MS) {
                    lastMeaningfulPositionMs = newPosition.positionMs
                    lastMeaningfulEpisodeId = currentEpisodeId
                    currentEpisodeId?.toIntOrNull()?.let { id ->
                        val meta = player.currentMediaItem?.mediaMetadata
                        LocalPlaybackBookmark.save(
                            context = this@MicahPodMediaService,
                            episodeId = id,
                            positionMs = newPosition.positionMs,
                            title = meta?.title?.toString(),
                            podcastTitle = meta?.artist?.toString() ?: meta?.albumTitle?.toString(),
                            audioUrl = player.currentMediaItem?.localConfiguration?.uri?.toString(),
                            artworkUrl = meta?.artworkUri?.toString()
                        )
                    }
                }
                sendProgressIfNeeded(force = true, source = "auto")
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            Log.e(TAG, "onPlayerError code=${error.errorCodeName}", error)
            logPlaybackEvent(
                event = "stop",
                reason = "error_${error.errorCodeName}",
                extra = error.message
            )
            sendProgressIfNeeded(force = true, source = "auto")
            val pending = queueAdvanceInFlight
            if (pending != null && isRetryableIoError(error)) {
                mainHandler.post { retryQueueAdvance(pending, error.errorCodeName) }
            }
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            Log.d(TAG, "onAudioSessionIdChanged=$audioSessionId")
        }
    }

    private fun isRetryableIoError(error: androidx.media3.common.PlaybackException): Boolean {
        return error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
            || error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
            || error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
            || error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_TIMEOUT
            || error.errorCodeName.contains("IO_", ignoreCase = true)
    }

    private fun rememberMeaningfulPosition() {
        val pos = player.currentPosition
        if (pos >= MIN_AUTO_SAVE_MS && currentEpisodeId != null) {
            lastMeaningfulPositionMs = pos
            lastMeaningfulEpisodeId = currentEpisodeId
            val episodeInt = currentEpisodeId?.toIntOrNull()
            if (episodeInt != null) {
                val meta = player.currentMediaItem?.mediaMetadata
                LocalPlaybackBookmark.save(
                    context = this,
                    episodeId = episodeInt,
                    positionMs = pos,
                    title = meta?.title?.toString(),
                    podcastTitle = meta?.artist?.toString() ?: meta?.albumTitle?.toString(),
                    audioUrl = player.currentMediaItem?.localConfiguration?.uri?.toString(),
                    artworkUrl = meta?.artworkUri?.toString()
                )
            }
        }
    }

    /**
     * If a reload left us at ~0 but we (local prefs or server) know a deeper bookmark, seek there.
     * Critical for paused cold-start / Razr cover where the UI shows the right episode at 0:00.
     */
    private fun maybeRestoreFromMeaningfulBookmark() {
        val episodeId = currentEpisodeId ?: return
        val currentMs = player.currentPosition.coerceAtLeast(0)
        if (currentMs >= MIN_AUTO_SAVE_MS) return

        val episodeInt = episodeId.toIntOrNull() ?: return
        val localPrefMs = LocalPlaybackBookmark.positionForEpisode(this, episodeInt)
        val memoryMs = if (lastMeaningfulEpisodeId == episodeId && lastMeaningfulPositionMs >= MIN_AUTO_SAVE_MS) {
            lastMeaningfulPositionMs
        } else {
            null
        }
        val localTarget = listOfNotNull(localPrefMs, memoryMs).maxOrNull()

        if (localTarget != null && abs(currentMs - localTarget) > 2_500L) {
            Log.d(TAG, "Restoring local bookmark to ${localTarget}ms (was ${currentMs}ms)")
            logPlaybackEvent(
                event = "seek",
                reason = "restore_local_bookmark",
                extra = "to=${localTarget}ms"
            )
            lastMeaningfulPositionMs = localTarget
            lastMeaningfulEpisodeId = episodeId
            player.seekTo(localTarget)
            // Re-check shortly — paused prepares sometimes snap back to 0 before settle.
            mainHandler.postDelayed({ maybeRestoreFromMeaningfulBookmark() }, 750)
            return
        }

        // Ask server for bookmark if local memory is empty/stale.
        serviceScope.launch {
            var targetMs: Long? = null
            val inProgress = repository.getInProgress(limit = 20, forceRefresh = true)
            if (inProgress is RepositoryResult.Success) {
                val match = inProgress.data.firstOrNull { it.resolvedId() == episodeInt }
                val fromProgress = ((match?.positionSeconds ?: 0.0) * 1000.0).toLong()
                if (fromProgress >= MIN_AUTO_SAVE_MS) targetMs = fromProgress
            }
            if (targetMs == null) {
                val stateResult = repository.fetchPlayerState()
                if (stateResult is RepositoryResult.Success) {
                    val parsed = runCatching {
                        com.google.gson.Gson().fromJson(
                            stateResult.data,
                            com.circpros.micahpod.data.PlayerStateResponse::class.java
                        )
                    }.getOrNull()?.state
                    if (parsed?.episodeId == episodeInt) {
                        val fromState = ((parsed.positionSeconds ?: 0.0) * 1000.0).toLong()
                        if (fromState >= MIN_AUTO_SAVE_MS) targetMs = fromState
                    }
                }
            }
            val seekTo = targetMs ?: return@launch
            mainHandler.post {
                if (currentEpisodeId != episodeId) return@post
                val nowPos = player.currentPosition.coerceAtLeast(0)
                if (nowPos < MIN_AUTO_SAVE_MS && abs(nowPos - seekTo) > 2_500L) {
                    Log.d(TAG, "Restoring server bookmark to ${seekTo}ms (was ${nowPos}ms)")
                    logPlaybackEvent(
                        event = "seek",
                        reason = "restore_server_bookmark",
                        extra = "to=${seekTo}ms"
                    )
                    lastMeaningfulPositionMs = seekTo
                    lastMeaningfulEpisodeId = episodeId
                    LocalPlaybackBookmark.save(
                        context = this@MicahPodMediaService,
                        episodeId = episodeInt,
                        positionMs = seekTo
                    )
                    player.seekTo(seekTo)
                    mainHandler.postDelayed({ maybeRestoreFromMeaningfulBookmark() }, 750)
                }
            }
        }
    }

    private fun handleEndedState() {
        val now = System.currentTimeMillis()
        val pos = player.currentPosition.coerceAtLeast(0)
        val duration = player.duration
        val hasDuration = duration > 0 && duration != C.TIME_UNSET
        val nearEnd = hasDuration && pos >= (duration - NEAR_END_TOLERANCE_MS)
        val emptyItem = player.mediaItemCount == 0 || currentEpisodeId.isNullOrBlank()
        val recentlyTransitioned = now < suppressEndedUntilMs

        if (recentlyTransitioned || emptyItem || (!nearEnd && pos < MIN_AUTO_SAVE_MS)) {
            logPlaybackEvent(
                event = "stop",
                reason = "ended_ignored",
                extra = "recentTransition=$recentlyTransitioned empty=$emptyItem pos=${pos}ms dur=${if (hasDuration) duration else -1}"
            )
            // Keep last meaningful bookmark; do not treat this as finished.
            return
        }

        logPlaybackEvent(event = "stop", reason = "ended")
        rememberMeaningfulPosition()
        sendProgressIfNeeded(force = true, source = "auto", replaceSession = true)
        // Genuine end — clear local bookmark so a later reload doesn't jump back into the credits.
        lastMeaningfulPositionMs = 0L
        val finishedId = currentEpisodeId?.toIntOrNull()
        if (finishedId != null && finishedId > 0) {
            LocalPlaybackBookmark.clearEpisode(this, finishedId)
            serviceScope.launch {
                // Mark complete so it drops off Continue; then advance the queue.
                repository.markPlayed(finishedId)
            }
        }
        mainHandler.post { advanceQueueAfterEnded() }
    }

    private fun logPlaybackEvent(event: String, reason: String, extra: String? = null) {
        val meta = player.currentMediaItem?.mediaMetadata
        val duration = player.duration
        PlaybackLogger.log(
            context = this,
            event = event,
            reason = reason,
            episodeId = currentEpisodeId,
            title = meta?.title?.toString(),
            podcast = meta?.artist?.toString() ?: meta?.albumTitle?.toString(),
            positionMs = player.currentPosition.takeIf { it >= 0 },
            durationMs = if (duration > 0 && duration != C.TIME_UNSET) duration else null,
            extra = extra
        )
    }

    private fun advanceQueueAfterEnded() {
        playQueueEpisode(ServiceLocator.queueManager.popNext(), fromRetry = false)
    }

    private fun retryQueueAdvance(
        episode: com.circpros.micahpod.data.QueueEpisode,
        errorCode: String
    ) {
        if (queueAdvanceRetryCount >= QUEUE_RETRY_MAX) {
            PlaybackLogger.log(
                context = this,
                event = "queue",
                reason = "retry_exhausted",
                episodeId = episode.id?.toString(),
                title = episode.title,
                extra = errorCode
            )
            // Put it back at the front so the user can try again later.
            ServiceLocator.queueManager.prepend(episode)
            serviceScope.launch {
                val qm = ServiceLocator.queueManager
                repository.saveQueue(qm.toJsonArray(), qm.clientUpdatedAt())
            }
            queueAdvanceInFlight = null
            queueAdvanceRetryCount = 0
            return
        }
        queueAdvanceRetryCount += 1
        PlaybackLogger.log(
            context = this,
            event = "queue",
            reason = "retry_$queueAdvanceRetryCount",
            episodeId = episode.id?.toString(),
            title = episode.title,
            extra = errorCode
        )
        mainHandler.postDelayed({
            playQueueEpisode(episode, fromRetry = true)
        }, QUEUE_RETRY_DELAY_MS)
    }

    private fun playQueueEpisode(
        next: com.circpros.micahpod.data.QueueEpisode?,
        fromRetry: Boolean
    ) {
        if (next == null) {
            PlaybackLogger.log(
                context = this,
                event = "queue",
                reason = "ended_no_next",
                episodeId = currentEpisodeId,
                title = player.currentMediaItem?.mediaMetadata?.title?.toString()
            )
            return
        }
        if (!fromRetry) {
            queueAdvanceRetryCount = 0
            serviceScope.launch {
                val queue = ServiceLocator.queueManager
                repository.saveQueue(
                    queueJson = queue.toJsonArray(),
                    queueUpdatedAt = queue.clientUpdatedAt(),
                    clearQueue = queue.size() == 0
                )
            }
        }
        queueAdvanceInFlight = next
        val episode = next.toEpisode()
        if (episode.id > 0) {
            ServiceLocator.episodeCache[episode.id] = episode
        }
        // Suppress false ENDED from the media swap into the next queue item.
        suppressEndedUntilMs = System.currentTimeMillis() + FALSE_ENDED_GUARD_MS
        lastMeaningfulPositionMs = 0L
        lastMeaningfulEpisodeId = episode.id.takeIf { it > 0 }?.toString()
        PlaybackLogger.log(
            context = this,
            event = "start",
            reason = if (fromRetry) "queue_retry" else "queue_advance",
            episodeId = episode.id.takeIf { it > 0 }?.toString(),
            title = episode.title,
            podcast = episode.podcastTitle,
            positionMs = 0L,
            durationMs = episode.durationSeconds?.times(1000L)
        )
        if (episode.audioUrl.isNullOrBlank()) {
            PlaybackLogger.log(
                context = this,
                event = "queue",
                reason = "next_missing_audio",
                episodeId = episode.id.toString(),
                title = episode.title
            )
            queueAdvanceInFlight = null
            // Try the following item.
            advanceQueueAfterEnded()
            return
        }
        // Drive the service player directly — more reliable than opening a second controller.
        try {
            val mediaItem = buildEpisodeItem(episode)
            player.setMediaItem(mediaItem, /* startPositionMs= */ 0L)
            player.prepare()
            player.play()
        } catch (t: Throwable) {
            Log.e(TAG, "playQueueEpisode failed; falling back to PlaybackHelper", t)
            PlaybackHelper.playEpisode(
                context = this,
                episode = episode,
                startPositionSeconds = 0.0,
                autoPlay = true
            )
        }
    }

    private fun sendProgressIfNeeded(
        force: Boolean = false,
        source: String = "auto",
        replaceSession: Boolean = false
    ) {
        val episodeId = currentEpisodeId ?: return
        if (!authManager.isLoggedIn()) return
        val position = player.currentPosition
        if (position == C.TIME_UNSET || position < 0) return

        val isManual = source == "manual"
        // Never auto-write near-zero positions — they wipe Continue Listening / bookmarks.
        if (!isManual && position < MIN_AUTO_SAVE_MS) {
            Log.d(TAG, "Skip near-zero auto save episode=$episodeId pos=${position}ms")
            return
        }

        val effectiveReplace = replaceSession && (isManual || position >= MIN_AUTO_SAVE_MS)
        sendPlayerState(episodeId, position, replaceSession = effectiveReplace)

        val duration = player.duration
        val nowMs = System.currentTimeMillis()
        val positionDelta = abs(position - lastProgressPosition)
        val timeDelta = nowMs - lastProgressAtMs
        if (!force && positionDelta < 1000 && timeDelta < 3000) return
        lastProgressPosition = position
        lastProgressAtMs = nowMs
        serviceScope.launch {
            repository.saveProgress(
                episodeId = episodeId,
                positionMs = position,
                durationMs = if (duration > 0 && duration != C.TIME_UNSET) duration else 0L,
                source = source,
                force = false
            )
        }
    }

    private fun sendPlayerState(episodeId: String, positionMs: Long, replaceSession: Boolean = false) {
        if (!authManager.isLoggedIn()) return
        val episodeInt = episodeId.toIntOrNull() ?: return
        val positionSeconds = positionMs / 1000.0
        val payload = mutableMapOf(
            "episode_id" to episodeInt.toString(),
            "position_seconds" to String.format(Locale.US, "%.3f", positionSeconds),
            "speed" to player.playbackParameters.speed.toString(),
            "volume" to player.volume.toString(),
            "client_updated_at" to clientTimestamp(),
            "source" to "auto"
        )
        if (replaceSession) {
            payload["replace_session"] = "1"
        }
        serviceScope.launch {
            val result = repository.savePlayerState(payload)
            Log.d(
                TAG,
                "sendPlayerState episode=$episodeInt pos=${String.format(Locale.US, "%.1f", positionSeconds)}s replace=$replaceSession result=$result"
            )
        }
    }

    private fun clientTimestamp(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return sdf.format(java.util.Date())
    }
}

package com.circpros.micahpod.ui

import android.content.ComponentName
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import coil.load
import com.circpros.micahpod.R
import com.circpros.micahpod.data.PlayerStateResponse
import com.circpros.micahpod.data.ServiceLocator
import com.circpros.micahpod.databinding.ActivityMainBinding
import com.circpros.micahpod.playback.LocalPlaybackBookmark
import com.circpros.micahpod.playback.MicahPodMediaService
import com.circpros.micahpod.playback.PlaybackHelper
import com.circpros.micahpod.repository.RepositoryResult
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.net.Uri

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    private var controller: MediaController? = null
    private val handler = Handler(Looper.getMainLooper())
    private val gson = Gson()
    private var hasRestoredPlayerState = false
    private var bookmarkSyncInFlight = false
    private var pendingBookmarkSeekMs: Long? = null
    private var compactCoverMode = false

    companion object {
        private const val TAG = "MainActivity"
        private const val SEEK_TOLERANCE_MS = 2_500L
        /** Razr cover / flip exterior windows are short; switch to compact player UI. */
        private const val COMPACT_HEIGHT_DP = 520
        /** If the live playhead is already past this, don't re-apply a possibly-stale server bookmark. */
        private const val HEALTHY_LIVE_POSITION_MS = 5_000L
    }

    // Top-level destinations — no back arrow on these
    private val topLevelDestinations = setOf(
        R.id.feedFragment,
        R.id.podcastListFragment,
        R.id.playerFragment,
        R.id.settingsFragment,
        R.id.loginFragment
    )

    private val nowPlayingUpdater = object : Runnable {
        override fun run() {
            updateNowPlaying()
            if (compactCoverMode) updateCoverPlayer()
            maybeApplyPendingBookmarkSeek()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installUncaughtExceptionHandler()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        val navHost =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHost.navController

        if (savedInstanceState == null) {
            val graph = navController.navInflater.inflate(R.navigation.nav_graph)
            val startDestination = if (ServiceLocator.authManager.isLoggedIn()) {
                R.id.feedFragment
            } else {
                R.id.loginFragment
            }
            graph.setStartDestination(startDestination)
            navController.graph = graph
        }

        // Configure AppBar — top-level destinations don't show back arrow
        val appBarConfig = AppBarConfiguration(topLevelDestinations)
        setupActionBarWithNavController(navController, appBarConfig)

        // Bottom nav — handle tab switching manually to preserve back stack
        binding.bottomNav.setOnItemSelectedListener { item ->
            // If already on this tab's top destination, do nothing
            if (navController.currentDestination?.id == item.itemId) return@setOnItemSelectedListener true

            // Pop back to the graph root, then navigate to the selected tab
            val navOptions = NavOptions.Builder()
                .setPopUpTo(navController.graph.id, false)
                .setLaunchSingleTop(true)
                .build()
            navController.navigate(item.itemId, null, navOptions)
            true
        }

        // Keep bottom nav selection in sync when navigating
        navController.addOnDestinationChangedListener { _, destination, _ ->
            applyChromeForDestination(destination.id)
            updateNowPlaying()
            syncBookmarkPosition(reason = "destination")
        }

        // Tap now-playing bar → go to player
        binding.nowPlayingBar.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            if (c.mediaItemCount > 0) {
                val meta = c.mediaMetadata
                val args = Bundle().apply {
                    putInt("episodeId", 0)
                    putString("episodeTitle", meta.title?.toString())
                    putString("artworkUrl", meta.artworkUri?.toString())
                }
                navController.navigate(R.id.playerFragment, args)
            }
        }

        binding.nowPlayingPlayPause.setOnClickListener {
            PlaybackHelper.togglePlayPause(controller)
            updateNowPlaying()
        }

        binding.coverPlayPause.setOnClickListener {
            PlaybackHelper.togglePlayPause(controller)
            updateCoverPlayer()
        }
        binding.coverSkipBack.setOnClickListener {
            controller?.seekToPrevious()
            updateCoverPlayer()
        }
        binding.coverSkipForward.setOnClickListener {
            controller?.seekToNext()
            updateCoverPlayer()
        }

        applyCompactMode(force = true)
        connectMediaController()
        ensureSessionThenRestore()
    }

    private fun ensureSessionThenRestore() {
        if (!ServiceLocator.authManager.isLoggedIn()) return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                ServiceLocator.repository.ensureAuthenticated()
            }
            if (result is RepositoryResult.Error) {
                Log.w(TAG, "ensureAuthenticated failed: ${result.message}")
            } else {
                Log.d(TAG, "Session authenticated")
            }
            syncQueueFromServer()
            syncBookmarkPosition(reason = "authReady")
        }
    }

    private fun syncQueueFromServer() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                ServiceLocator.repository.fetchPlayerState()
            }
            if (result is RepositoryResult.Success) {
                val parsed = runCatching {
                    gson.fromJson(result.data, PlayerStateResponse::class.java)
                }.getOrNull()?.state
                ServiceLocator.queueManager.replaceFromServer(parsed?.queue, parsed?.queueUpdatedAt)
                Log.d(TAG, "Queue synced size=${ServiceLocator.queueManager.size()}")
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyCompactMode(force = true)
    }

    override fun onResume() {
        super.onResume()
        applyCompactMode()
        handler.post(nowPlayingUpdater)
        syncBookmarkPosition(reason = "onResume")
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(nowPlayingUpdater)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(nowPlayingUpdater)
        Log.d(TAG, "Releasing MediaController in MainActivity onDestroy")
        controller?.release()
        controller = null
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp() || super.onSupportNavigateUp()
    }

    private fun connectMediaController() {
        Log.d(TAG, "Connecting MediaController in MainActivity")
        val token = SessionToken(this, ComponentName(this, MicahPodMediaService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener(
            {
                controller = future.get()
                Log.d(TAG, "MediaController connected in MainActivity. mediaItemCount=${controller?.mediaItemCount}")
                controller?.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        Log.d(TAG, "MainActivity controller onIsPlayingChanged=$isPlaying")
                        updateNowPlaying()
                        updateCoverPlayer()
                    }
                    override fun onMediaItemTransition(
                        mediaItem: androidx.media3.common.MediaItem?, reason: Int
                    ) {
                        Log.d(TAG, "MainActivity controller onMediaItemTransition reason=$reason")
                        updateNowPlaying()
                        updateCoverPlayer()
                    }
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            maybeApplyPendingBookmarkSeek()
                        }
                        updateCoverPlayer()
                    }
                })
                updateNowPlaying()
                updateCoverPlayer()
                syncBookmarkPosition(reason = "controllerReady")
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    private fun applyChromeForDestination(destinationId: Int) {
        if (compactCoverMode) {
            binding.toolbar.visibility = View.GONE
            binding.bottomNav.visibility = View.GONE
            binding.nowPlayingBar.visibility = View.GONE
            return
        }
        val isLogin = destinationId == R.id.loginFragment
        val isTopLevel = destinationId in topLevelDestinations
        binding.bottomNav.visibility = if (isLogin) View.GONE else View.VISIBLE
        binding.toolbar.visibility = if (isLogin) View.GONE else View.VISIBLE
        if (isTopLevel && !isLogin) {
            val menuItem = binding.bottomNav.menu.findItem(destinationId)
            if (menuItem != null && !menuItem.isChecked) {
                menuItem.isChecked = true
            }
        }
    }

    private fun isCompactCoverDisplay(): Boolean {
        val bounds = windowManager.currentWindowMetrics.bounds
        val density = resources.displayMetrics.density
        val heightDp = bounds.height() / density
        val widthDp = bounds.width() / density
        return heightDp < COMPACT_HEIGHT_DP || (widthDp <= 420 && heightDp <= 560)
    }

    private fun applyCompactMode(force: Boolean = false) {
        val compact = isCompactCoverDisplay()
        if (!force && compact == compactCoverMode) return
        compactCoverMode = compact
        Log.d(TAG, "applyCompactMode compact=$compact")
        binding.coverPlayerPanel.isVisible = compact
        binding.mainContent.isVisible = !compact
        if (compact) {
            binding.toolbar.visibility = View.GONE
            binding.bottomNav.visibility = View.GONE
            binding.nowPlayingBar.visibility = View.GONE
            updateCoverPlayer()
        } else {
            applyChromeForDestination(navController.currentDestination?.id ?: R.id.feedFragment)
            updateNowPlaying()
        }
    }

    private fun updateCoverPlayer() {
        if (!compactCoverMode) return
        val c = controller
        if (c == null || c.mediaItemCount == 0) {
            binding.coverTitle.text = "Nothing playing"
            binding.coverPosition.text = "Open phone to browse"
            binding.coverArt.setImageDrawable(null)
            binding.coverPlayPause.setIconResource(R.drawable.ic_play)
            return
        }
        val meta = c.currentMediaItem?.mediaMetadata
        binding.coverTitle.text = meta?.title ?: "MicahPod"
        meta?.artworkUri?.let { binding.coverArt.load(it) { crossfade(true) } }
        val pos = PlaybackHelper.formatClock(c.currentPosition.coerceAtLeast(0) / 1000.0)
        val dur = if (c.duration > 0 && c.duration != androidx.media3.common.C.TIME_UNSET) {
            PlaybackHelper.formatClock(c.duration / 1000.0)
        } else {
            "--:--"
        }
        binding.coverPosition.text = "$pos / $dur"
        binding.coverPlayPause.setIconResource(
            if (c.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    private fun updateNowPlaying() {
        if (compactCoverMode) {
            binding.nowPlayingBar.visibility = View.GONE
            return
        }
        val c = controller
        val currentDest = navController.currentDestination?.id

        val hasMedia = c != null && c.mediaItemCount > 0
        val isPlayerOrLogin = currentDest == R.id.playerFragment || currentDest == R.id.loginFragment

        if (!hasMedia || isPlayerOrLogin) {
            binding.nowPlayingBar.visibility = View.GONE
            return
        }

        try {
            val meta = c!!.currentMediaItem?.mediaMetadata
            binding.nowPlayingTitle.text = meta?.title ?: ""
            val artUri = meta?.artworkUri
            if (artUri != null) {
                binding.nowPlayingArt.load(artUri) { crossfade(true) }
            }

            binding.nowPlayingPlayPause.setIconResource(
                if (c.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
            )

            binding.nowPlayingBar.visibility = View.VISIBLE
        } catch (e: Exception) {
            Log.e(TAG, "updateNowPlaying failed", e)
            binding.nowPlayingBar.visibility = View.GONE
        }
    }

    private fun syncBookmarkPosition(reason: String) {
        if (!ServiceLocator.authManager.isLoggedIn()) return
        val c = controller ?: return
        if (bookmarkSyncInFlight) return

        // Tab switches / app resume must not yank a healthy live playhead back to a
        // slightly-stale server bookmark (heartbeat can lag by ~30s).
        // Near-zero reloads after process death MUST still sync.
        if (c.mediaItemCount > 0 && c.currentPosition >= HEALTHY_LIVE_POSITION_MS) {
            Log.d(
                TAG,
                "skip bookmark sync reason=$reason — live pos=${c.currentPosition}ms playing=${c.isPlaying}"
            )
            hasRestoredPlayerState = true
            pendingBookmarkSeekMs = null
            return
        }

        bookmarkSyncInFlight = true
        val repository = ServiceLocator.repository
        lifecycleScope.launch {
            try {
                Log.d(TAG, "syncBookmarkPosition reason=$reason mediaCount=${c.mediaItemCount} playing=${c.isPlaying} pos=${c.currentPosition}")
                val currentId = c.currentMediaItem?.mediaId
                    ?.removePrefix("episode:")
                    ?.toIntOrNull()
                val local = LocalPlaybackBookmark.read(this@MainActivity)

                val inProgress = withContext(Dispatchers.IO) {
                    repository.getInProgress(limit = 20, forceRefresh = true)
                }
                val bookmarks = if (inProgress is RepositoryResult.Success) inProgress.data else emptyList()

                val matching = currentId?.let { id -> bookmarks.firstOrNull { it.resolvedId() == id } }
                if (matching != null) {
                    val serverSec = matching.positionSeconds ?: 0.0
                    val localSec = if (local?.episodeId == matching.resolvedId()) {
                        local.positionMs / 1000.0
                    } else {
                        0.0
                    }
                    applyBookmarkToController(
                        c,
                        matching.resolvedId(),
                        maxOf(serverSec, localSec),
                        matching
                    )
                    hasRestoredPlayerState = true
                    return@launch
                }

                if (c.mediaItemCount == 0) {
                    val newest = bookmarks.firstOrNull()
                    if (newest != null) {
                        val localSec = if (local?.episodeId == newest.resolvedId()) {
                            local.positionMs / 1000.0
                        } else {
                            0.0
                        }
                        val startSec = maxOf(newest.positionSeconds ?: 0.0, localSec)
                        Log.d(TAG, "Loading newest in_progress episode=${newest.resolvedId()} @ ${startSec}s")
                        PlaybackHelper.playEpisode(
                            context = this@MainActivity,
                            episode = newest.copy(id = newest.resolvedId()),
                            startPositionSeconds = startSec,
                            autoPlay = false
                        )
                        pendingBookmarkSeekMs = (startSec * 1000.0).toLong()
                        handler.postDelayed({
                            updateNowPlaying()
                            updateCoverPlayer()
                            maybeApplyPendingBookmarkSeek()
                        }, 400)
                        hasRestoredPlayerState = true
                        return@launch
                    }

                    if (local != null && !local.audioUrl.isNullOrBlank()) {
                        Log.d(TAG, "Loading local bookmark episode=${local.episodeId} @ ${local.positionMs}ms")
                        val localEpisode = com.circpros.micahpod.data.Episode(
                            id = local.episodeId,
                            title = local.title ?: "MicahPod",
                            pubDate = null,
                            durationSeconds = null,
                            audioUrl = local.audioUrl,
                            artworkUrl = local.artworkUrl,
                            podcastTitle = local.podcastTitle,
                            podcastArtwork = local.artworkUrl,
                            description = null,
                            descriptionHtml = null,
                            userRating = null,
                            avgRating = null,
                            completed = false,
                            recScore = null,
                            commentCount = null,
                            tags = null,
                            positionSeconds = local.positionMs / 1000.0,
                            podcastId = null,
                            episodeId = local.episodeId
                        )
                        PlaybackHelper.playEpisode(
                            context = this@MainActivity,
                            episode = localEpisode,
                            startPositionSeconds = local.positionMs / 1000.0,
                            autoPlay = false
                        )
                        pendingBookmarkSeekMs = local.positionMs
                        handler.postDelayed({
                            updateNowPlaying()
                            updateCoverPlayer()
                            maybeApplyPendingBookmarkSeek()
                        }, 400)
                        hasRestoredPlayerState = true
                        return@launch
                    }

                    val result = withContext(Dispatchers.IO) { repository.fetchPlayerState() }
                    if (result is RepositoryResult.Success && c.mediaItemCount == 0) {
                        val parsed = runCatching {
                            gson.fromJson(result.data, PlayerStateResponse::class.java)
                        }.getOrNull()
                        val state = parsed?.state
                        val episodeId = state?.episodeId
                        val audioUrl = state?.audioUrl
                        if (episodeId != null && !audioUrl.isNullOrBlank()) {
                            val serverMs = ((state.positionSeconds ?: 0.0) * 1000.0).toLong().coerceAtLeast(0)
                            val localMs = if (local?.episodeId == episodeId) local.positionMs else 0L
                            val positionMs = maxOf(serverMs, localMs)
                            val mediaItem = MediaItem.Builder()
                                .setMediaId("episode:$episodeId")
                                .setUri(audioUrl)
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle(state.title)
                                        .setAlbumTitle(state.podcastTitle)
                                        .setArtist(state.podcastTitle)
                                        .setArtworkUri(
                                            (state.artworkUrl ?: state.podcastArtwork)?.let { Uri.parse(it) }
                                        )
                                        .setIsPlayable(true)
                                        .build()
                                )
                                .build()
                            c.setMediaItem(mediaItem, positionMs)
                            c.prepare()
                            if (positionMs > SEEK_TOLERANCE_MS) {
                                c.seekTo(positionMs)
                                pendingBookmarkSeekMs = positionMs
                            }
                            c.pause()
                            updateNowPlaying()
                            updateCoverPlayer()
                        }
                    }
                } else if (currentId != null) {
                    val localMs = LocalPlaybackBookmark.positionForEpisode(this@MainActivity, currentId)
                    if (localMs != null && c.currentPosition < HEALTHY_LIVE_POSITION_MS && localMs > SEEK_TOLERANCE_MS) {
                        pendingBookmarkSeekMs = localMs
                        maybeApplyPendingBookmarkSeek()
                    }
                    val result = withContext(Dispatchers.IO) { repository.fetchPlayerState() }
                    if (result is RepositoryResult.Success) {
                        val parsed = runCatching {
                            gson.fromJson(result.data, PlayerStateResponse::class.java)
                        }.getOrNull()?.state
                        if (parsed?.episodeId == currentId) {
                            val serverSec = parsed.positionSeconds ?: 0.0
                            val localSec = (localMs ?: 0L) / 1000.0
                            applyBookmarkToController(
                                c,
                                currentId,
                                maxOf(serverSec, localSec),
                                null
                            )
                        }
                    }
                }
                hasRestoredPlayerState = true
            } finally {
                bookmarkSyncInFlight = false
            }
        }
    }

    private fun applyBookmarkToController(
        c: MediaController,
        episodeId: Int,
        positionSeconds: Double,
        episode: com.circpros.micahpod.data.Episode?
    ) {
        val localMs = LocalPlaybackBookmark.positionForEpisode(this, episodeId) ?: 0L
        val targetMs = maxOf(
            (positionSeconds.coerceAtLeast(0.0) * 1000.0).toLong(),
            localMs
        )
        val currentId = c.currentMediaItem?.mediaId?.removePrefix("episode:")?.toIntOrNull()
        if (currentId == episodeId && c.mediaItemCount > 0) {
            val currentMs = c.currentPosition.coerceAtLeast(0)
            // Never seek backward to a stale bookmark — live playhead is the source of truth
            // once we're past the initial load.
            if (currentMs + SEEK_TOLERANCE_MS >= targetMs && currentMs >= HEALTHY_LIVE_POSITION_MS) {
                Log.d(
                    TAG,
                    "Keep live playhead episode=$episodeId at ${currentMs}ms (bookmark ${targetMs}ms)"
                )
                pendingBookmarkSeekMs = null
                return
            }
            if (targetMs > currentMs + SEEK_TOLERANCE_MS) {
                Log.d(TAG, "Seek forward to bookmark episode=$episodeId from ${currentMs}ms to ${targetMs}ms")
                pendingBookmarkSeekMs = targetMs
                maybeApplyPendingBookmarkSeek()
            } else {
                pendingBookmarkSeekMs = null
            }
            return
        }
        if (episode != null && !episode.audioUrl.isNullOrBlank()) {
            PlaybackHelper.playEpisode(
                context = this,
                episode = episode.copy(id = episodeId),
                startPositionSeconds = targetMs / 1000.0,
                autoPlay = false
            )
            pendingBookmarkSeekMs = targetMs
            handler.postDelayed({
                updateNowPlaying()
                updateCoverPlayer()
                maybeApplyPendingBookmarkSeek()
            }, 400)
        }
    }

    private fun maybeApplyPendingBookmarkSeek() {
        val targetMs = pendingBookmarkSeekMs ?: return
        val c = controller ?: return
        if (c.mediaItemCount == 0) return
        val currentMs = c.currentPosition.coerceAtLeast(0)

        // Already at/past bookmark with a healthy live position — do not yank backward.
        if (currentMs >= HEALTHY_LIVE_POSITION_MS && currentMs + SEEK_TOLERANCE_MS >= targetMs) {
            pendingBookmarkSeekMs = null
            return
        }
        if (kotlin.math.abs(currentMs - targetMs) <= SEEK_TOLERANCE_MS) {
            pendingBookmarkSeekMs = null
            return
        }
        if (c.playbackState == Player.STATE_IDLE) {
            c.prepare()
            return
        }
        if (c.playbackState != Player.STATE_READY && c.playbackState != Player.STATE_BUFFERING) {
            return
        }
        // Only seek forward from a near-zero / behind-bookmark reload.
        if (targetMs > currentMs + SEEK_TOLERANCE_MS) {
            Log.d(TAG, "Applying forward bookmark seek to ${targetMs}ms (was ${currentMs}ms)")
            c.seekTo(targetMs)
            handler.postDelayed({
                val still = controller ?: return@postDelayed
                val pos = still.currentPosition.coerceAtLeast(0)
                if (pendingBookmarkSeekMs != targetMs) return@postDelayed
                if (pos < HEALTHY_LIVE_POSITION_MS && targetMs > HEALTHY_LIVE_POSITION_MS) {
                    Log.d(TAG, "Re-applying forward bookmark seek after reset to ${targetMs}ms")
                    still.seekTo(targetMs)
                } else {
                    pendingBookmarkSeekMs = null
                }
            }, 800)
            updateNowPlaying()
            updateCoverPlayer()
        } else {
            pendingBookmarkSeekMs = null
        }
    }

    private fun installUncaughtExceptionHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception on thread=${thread.name}", throwable)
            try {
                val c = controller
                Log.e(
                    TAG,
                    "Controller state at crash: exists=${c != null} isPlaying=${c?.isPlaying} mediaItemCount=${c?.mediaItemCount}"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to log controller state at crash", e)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}

package com.circpros.micahpod.ui

import android.content.ComponentName
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.navigation.fragment.findNavController
import coil.load
import coil.transform.RoundedCornersTransformation
import com.circpros.micahpod.R
import com.circpros.micahpod.data.ServiceLocator
import com.circpros.micahpod.databinding.FragmentPlayerBinding
import com.circpros.micahpod.playback.LocalPlaybackBookmark
import com.circpros.micahpod.playback.MicahPodMediaService
import com.circpros.micahpod.playback.PlaybackHelper
import com.circpros.micahpod.repository.RepositoryResult
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.net.Uri

class PlayerFragment : Fragment() {
    private var _binding: FragmentPlayerBinding? = null
    private val binding get() = _binding!!
    private var controller: MediaController? = null
    private val handler = Handler(Looper.getMainLooper())
    private val updateInterval = 500L
    private var initialEpisodeId: Int = 0
    private var initialEpisodeTitle: String? = null
    private var initialArtworkUrl: String? = null
    private var initialEpisodeUrl: String? = null
    private var initialPositionSeconds: Double = 0.0
    private var userSeeking = false

    companion object {
        private const val TAG = "PlayerFragment"
    }

    private val progressUpdater = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, updateInterval)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlayerBinding.inflate(inflater, container, false)

        initialEpisodeId = arguments?.getInt("episodeId") ?: 0
        initialEpisodeTitle = arguments?.getString("episodeTitle")
        initialArtworkUrl = arguments?.getString("artworkUrl")
        initialEpisodeUrl = arguments?.getString("episodeUrl")
        initialPositionSeconds = arguments?.getDouble("positionSeconds") ?: 0.0

        binding.episodeTitleText.text = initialEpisodeTitle.orEmpty()
        binding.podcastTitleText.text = ""
        requireActivity().title = ""

        if (!initialArtworkUrl.isNullOrEmpty()) {
            binding.artworkImage.load(initialArtworkUrl) {
                crossfade(true)
                transformations(RoundedCornersTransformation(24f))
            }
        }

        val hasInitialInfo = !initialEpisodeTitle.isNullOrBlank() || !initialArtworkUrl.isNullOrBlank()
        setPlayerVisible(hasInitialInfo)

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.playPauseButton.setOnClickListener {
            ensurePlayableThen {
                PlaybackHelper.togglePlayPause(controller)
                updatePlayPauseIcon()
            }
        }

        binding.skipBackButton.setOnClickListener { seekBy(-30_000) }
        binding.skipForwardButton.setOnClickListener { seekBy(30_000) }

        binding.saveProgressButton.setOnClickListener { saveProgressManual() }
        binding.markPlayedButton.setOnClickListener { markPlayed() }
        binding.queueButton.setOnClickListener {
            findNavController().navigate(R.id.queueFragment)
        }
        updateQueueButtonLabel()
        setupCastButton()

        binding.seekBar.setLabelFormatter { value -> formatTime(value.toLong()) }
        binding.seekBar.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                binding.currentTime.text = formatTime(value.toLong())
            }
        }
        binding.seekBar.addOnSliderTouchListener(object :
            com.google.android.material.slider.Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {
                userSeeking = true
            }
            override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                try {
                    val c = controller ?: return
                    if (!c.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) return
                    if (c.mediaItemCount == 0) return
                    c.seekTo(slider.value.toLong())
                    binding.currentTime.text = formatTime(slider.value.toLong())
                } catch (e: Exception) {
                    Log.e(TAG, "Seekbar stop tracking failed", e)
                } finally {
                    userSeeking = false
                }
            }
        })

        connectController()
    }

    override fun onResume() {
        super.onResume()
        handler.post(progressUpdater)
        updateQueueButtonLabel()
    }

    private fun updateQueueButtonLabel() {
        if (_binding == null) return
        val n = ServiceLocator.queueManager.size()
        binding.queueButton.text = if (n > 0) {
            getString(R.string.queue_with_count, n)
        } else {
            getString(R.string.queue)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(progressUpdater)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacks(progressUpdater)
        try {
            controller?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release controller", e)
        }
        controller = null
        _binding = null
    }

    private fun connectController() {
        val context = requireContext()
        val token = SessionToken(context, ComponentName(context, MicahPodMediaService::class.java))
        val controllerFuture = MediaController.Builder(context, token).buildAsync()
        controllerFuture.addListener(
            {
                if (_binding == null) return@addListener
                try {
                    val mediaController = controllerFuture.get()
                    controller = mediaController
                    mediaController.addListener(object : Player.Listener {
                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            updatePlayPauseIcon()
                        }
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            updatePlayPauseIcon()
                            updateProgress()
                        }
                        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                            refreshMetadata()
                            setPlayerVisible(mediaController.mediaItemCount > 0)
                        }
                        override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
                            updateCastingStatus(deviceInfo)
                        }
                    })
                    updatePlayPauseIcon()
                    updateProgress()
                    refreshMetadata()
                    updateCastingStatus(mediaController.deviceInfo)

                    val hasMedia = mediaController.mediaItemCount > 0
                    val hasInitialInfo = !initialEpisodeTitle.isNullOrBlank() || !initialArtworkUrl.isNullOrBlank()
                    setPlayerVisible(hasMedia || hasInitialInfo)

                    if (!hasMedia && !initialEpisodeUrl.isNullOrBlank() && initialEpisodeId > 0) {
                        loadInitialEpisode(mediaController)
                    } else if (hasMedia && initialPositionSeconds >= 5.0) {
                        val currentId = mediaController.currentMediaItem?.mediaId
                            ?.removePrefix("episode:")
                            ?.toIntOrNull()
                        val targetMs = (initialPositionSeconds * 1000.0).toLong()
                        if (
                            (currentId == null || currentId == initialEpisodeId || initialEpisodeId == 0)
                            && kotlin.math.abs(mediaController.currentPosition - targetMs) > 2500
                        ) {
                            Log.d(TAG, "Seeking player UI to saved position ${targetMs}ms")
                            if (mediaController.playbackState == Player.STATE_IDLE) {
                                mediaController.prepare()
                            }
                            mediaController.seekTo(targetMs)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to connect controller", e)
                }
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    private fun loadInitialEpisode(mediaController: MediaController) {
        val url = initialEpisodeUrl ?: return
        val artUri = initialArtworkUrl?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
        val mediaItem = MediaItem.Builder()
            .setMediaId("episode:$initialEpisodeId")
            .setUri(url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(initialEpisodeTitle)
                    .setArtworkUri(artUri)
                    .setIsPlayable(true)
                    .build()
            )
            .build()
        val startMs = (initialPositionSeconds.coerceAtLeast(0.0) * 1000.0).toLong()
        mediaController.setMediaItem(mediaItem, startMs)
        mediaController.prepare()
        setPlayerVisible(true)
    }

    private fun ensurePlayableThen(action: () -> Unit) {
        val c = controller ?: return
        try {
            if (c.mediaItemCount == 0) {
                if (!initialEpisodeUrl.isNullOrBlank() && initialEpisodeId > 0) {
                    loadInitialEpisode(c)
                } else {
                    binding.saveStatusText.text = "Nothing loaded"
                    return
                }
            }
            if (c.playbackState == Player.STATE_IDLE) {
                c.prepare()
            }
            action()
        } catch (e: Exception) {
            Log.e(TAG, "ensurePlayableThen failed", e)
            Toast.makeText(requireContext(), "Playback error — try Resume again", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupCastButton() {
        try {
            CastContext.getSharedInstance(requireContext())
            CastButtonFactory.setUpMediaRouteButton(requireContext(), binding.castButton)
            binding.castButton.isVisible = true
        } catch (t: Throwable) {
            Log.w(TAG, "Cast button unavailable", t)
            binding.castButton.isVisible = false
            binding.castingStatusText.isVisible = false
        }
    }

    private fun updateCastingStatus(deviceInfo: DeviceInfo) {
        if (_binding == null) return
        val remote = deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE
        if (remote) {
            val name = try {
                CastContext.getSharedInstance(requireContext())
                    .sessionManager
                    .currentCastSession
                    ?.castDevice
                    ?.friendlyName
            } catch (_: Exception) {
                null
            }
            binding.castingStatusText.text = if (!name.isNullOrBlank()) {
                getString(R.string.casting_to, name)
            } else {
                getString(R.string.casting_to, getString(R.string.cast))
            }
            binding.castingStatusText.isVisible = true
        } else {
            binding.castingStatusText.text = ""
            binding.castingStatusText.isVisible = false
        }
    }

    private fun saveProgressManual() {
        val c = controller ?: return
        val mediaId = c.currentMediaItem?.mediaId ?: return
        val episodeId = mediaId.removePrefix("episode:")
        val position = c.currentPosition.coerceAtLeast(0)
        val duration = c.duration
        binding.saveProgressButton.isEnabled = false
        binding.saveStatusText.text = "Saving…"
        viewLifecycleOwner.lifecycleScope.launch {
            val positionSeconds = position / 1000.0
            val result = withContext(Dispatchers.IO) {
                val progressResult = ServiceLocator.repository.saveProgress(
                    episodeId = episodeId,
                    positionMs = position,
                    durationMs = if (duration > 0 && duration != C.TIME_UNSET) duration else 0L,
                    source = "manual",
                    force = true
                )
                if (progressResult is RepositoryResult.Success) {
                    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                    ServiceLocator.repository.savePlayerState(
                        mapOf(
                            "episode_id" to episodeId,
                            "position_seconds" to String.format(java.util.Locale.US, "%.3f", positionSeconds),
                            "source" to "manual",
                            "force" to "1",
                            "replace_session" to "1",
                            "client_updated_at" to sdf.format(java.util.Date())
                        )
                    )
                }
                progressResult
            }
            if (_binding == null) return@launch
            binding.saveProgressButton.isEnabled = true
            when (result) {
                is RepositoryResult.Success -> {
                    binding.saveStatusText.text = getString(R.string.progress_saved)
                    Toast.makeText(requireContext(), R.string.progress_saved, Toast.LENGTH_SHORT).show()
                }
                is RepositoryResult.Error -> {
                    binding.saveStatusText.text = result.message
                }
            }
        }
    }

    private fun markPlayed() {
        val c = controller ?: return
        val mediaId = c.currentMediaItem?.mediaId ?: return
        val episodeId = mediaId.removePrefix("episode:").toIntOrNull() ?: return
        binding.markPlayedButton.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                ServiceLocator.repository.markPlayed(episodeId)
            }
            if (_binding == null) return@launch
            binding.markPlayedButton.isEnabled = true
            when (result) {
                is RepositoryResult.Success -> {
                    LocalPlaybackBookmark.clearEpisode(requireContext(), episodeId)
                    Toast.makeText(requireContext(), R.string.marked_played, Toast.LENGTH_SHORT).show()
                    binding.saveStatusText.text = getString(R.string.marked_played)
                    advanceAfterMarkedPlayed(c)
                }
                is RepositoryResult.Error -> {
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun advanceAfterMarkedPlayed(c: MediaController) {
        val next = ServiceLocator.queueManager.popNext()
        if (next != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    val qm = ServiceLocator.queueManager
                    ServiceLocator.repository.saveQueue(
                        queueJson = qm.toJsonArray(),
                        queueUpdatedAt = qm.clientUpdatedAt(),
                        clearQueue = qm.size() == 0
                    )
                }
            }
            viewLifecycleOwner.lifecycleScope.launch {
                var episode = next.toEpisode()
                if (episode.audioUrl.isNullOrBlank() && episode.id > 0) {
                    val fetched = withContext(Dispatchers.IO) {
                        ServiceLocator.repository.getEpisodeById(episode.id)
                    }
                    if (fetched is RepositoryResult.Success) {
                        episode = fetched.data
                    }
                }
                if (_binding == null) return@launch
                playQueuedEpisode(episode)
            }
            return
        }

        try {
            c.pause()
            if (c.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) {
                c.clearMediaItems()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear player after mark played", e)
        }
        setPlayerVisible(false)
    }

    private fun playQueuedEpisode(episode: com.circpros.micahpod.data.Episode) {
        if (episode.audioUrl.isNullOrBlank()) {
            Toast.makeText(requireContext(), R.string.queue_play_failed, Toast.LENGTH_SHORT).show()
            setPlayerVisible(false)
            return
        }
        val playable = episode.copy(id = episode.resolvedId())
        ServiceLocator.episodeCache[playable.id] = playable
        Toast.makeText(requireContext(), R.string.playing_next_queued, Toast.LENGTH_SHORT).show()
        PlaybackHelper.playEpisode(
            context = requireContext(),
            episode = playable,
            startPositionSeconds = 0.0,
            autoPlay = true
        )
        binding.episodeTitleText.text = playable.title
        binding.podcastTitleText.text = playable.podcastTitle.orEmpty()
        (playable.artworkUrl ?: playable.podcastArtwork)?.let { url ->
            binding.artworkImage.load(url) {
                crossfade(true)
                transformations(RoundedCornersTransformation(24f))
            }
        }
        setPlayerVisible(true)
    }

    private fun refreshMetadata() {
        val c = controller ?: return
        if (_binding == null || c.mediaItemCount == 0) return
        val meta = c.mediaMetadata
        binding.episodeTitleText.text = meta.title?.toString() ?: initialEpisodeTitle.orEmpty()
        binding.podcastTitleText.text = (meta.albumTitle ?: meta.artist)?.toString().orEmpty()
        meta.artworkUri?.let { uri ->
            binding.artworkImage.load(uri) {
                crossfade(true)
                transformations(RoundedCornersTransformation(24f))
            }
        }
    }

    private fun updatePlayPauseIcon() {
        try {
            val playing = controller?.isPlaying == true
            binding.playPauseButton.setIconResource(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update play/pause icon", e)
        }
    }

    private fun seekBy(offsetMs: Long) {
        try {
            val c = controller ?: return
            if (!c.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) return
            if (c.mediaItemCount == 0) return
            val position = c.currentPosition
            if (position == C.TIME_UNSET) return
            val duration = c.duration
            val target = position + offsetMs
            val clamped = if (duration > 0) target.coerceIn(0, duration) else maxOf(0, target)
            c.seekTo(clamped)
        } catch (e: Exception) {
            Log.e(TAG, "Seek by failed", e)
        }
    }

    private fun updateProgress() {
        try {
            val c = controller ?: return
            if (_binding == null) return
            if (userSeeking) return
            val duration = c.duration
            val position = c.currentPosition
            if (position != C.TIME_UNSET) {
                binding.currentTime.text = formatTime(position)
            }
            if (duration <= 0 || duration == C.TIME_UNSET) return
            val seekBar = binding.seekBar
            val newTo = duration.toFloat().coerceAtLeast(1f)
            val newVal = position.coerceIn(0, duration).toFloat()
            // Material Slider throws if value is outside [valueFrom, valueTo] while
            // shrinking valueTo (common when switching episodes / clearing media).
            val from = seekBar.valueFrom
            if (newTo >= seekBar.valueTo) {
                seekBar.valueTo = newTo
                seekBar.value = newVal.coerceIn(from, seekBar.valueTo)
            } else {
                seekBar.value = newVal.coerceIn(from, minOf(seekBar.valueTo, newTo))
                seekBar.valueTo = newTo
                seekBar.value = newVal.coerceIn(from, seekBar.valueTo)
            }
            binding.totalTime.text = formatTime(duration)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to update progress", t)
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }

    private fun setPlayerVisible(hasMedia: Boolean) {
        binding.playerContent.isVisible = hasMedia
        binding.emptyStateText.isVisible = !hasMedia
    }
}

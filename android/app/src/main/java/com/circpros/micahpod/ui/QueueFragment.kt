package com.circpros.micahpod.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.circpros.micahpod.R
import com.circpros.micahpod.data.PlayerStateResponse
import com.circpros.micahpod.data.ServiceLocator
import com.circpros.micahpod.databinding.FragmentQueueBinding
import com.circpros.micahpod.playback.PlaybackHelper
import com.circpros.micahpod.repository.RepositoryResult
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class QueueFragment : Fragment() {
    private var _binding: FragmentQueueBinding? = null
    private val binding get() = _binding!!
    private val repository = ServiceLocator.repository
    private val queueManager = ServiceLocator.queueManager
    private val gson = Gson()

    private val adapter = QueueAdapter(
        onPlay = { item, index -> playNow(item, index) },
        onRemove = { index -> removeAt(index) }
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentQueueBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().title = getString(R.string.queue)
        binding.queueRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.queueRecycler.adapter = adapter
        binding.queueSwipeRefresh.setOnRefreshListener { refreshFromServer() }
        binding.queueClearButton.setOnClickListener { clearQueue() }
        renderLocal()
        refreshFromServer()
    }

    override fun onResume() {
        super.onResume()
        renderLocal()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun renderLocal() {
        val items = queueManager.snapshot()
        adapter.submitList(items)
        binding.queueCountText.text = getString(R.string.queue_count, items.size)
        binding.queueEmptyText.isVisible = items.isEmpty()
        binding.queueRecycler.isVisible = items.isNotEmpty()
    }

    private fun refreshFromServer() {
        binding.queueSwipeRefresh.isRefreshing = true
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { repository.fetchPlayerState() }
            if (_binding == null) return@launch
            if (result is RepositoryResult.Success) {
                val state = runCatching {
                    gson.fromJson(result.data, PlayerStateResponse::class.java)
                }.getOrNull()?.state
                queueManager.replaceFromServer(state?.queue, state?.queueUpdatedAt)
            }
            renderLocal()
            binding.queueSwipeRefresh.isRefreshing = false
        }
    }

    private fun persistQueue(cleared: Boolean = false) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                repository.saveQueue(
                    queueJson = queueManager.toJsonArray(),
                    queueUpdatedAt = queueManager.clientUpdatedAt(),
                    clearQueue = cleared || queueManager.size() == 0
                )
            }
            if (result is RepositoryResult.Error) {
                Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
            }
            renderLocal()
        }
    }

    private fun removeAt(index: Int) {
        queueManager.removeAt(index)
        persistQueue()
    }

    private fun clearQueue() {
        queueManager.clear()
        persistQueue(cleared = true)
        Toast.makeText(requireContext(), R.string.queue_cleared, Toast.LENGTH_SHORT).show()
    }

    private fun playNow(item: com.circpros.micahpod.data.QueueEpisode, index: Int) {
        queueManager.removeAt(index)
        persistQueue()
        val episode = item.toEpisode()
        if (episode.id > 0) {
            ServiceLocator.episodeCache[episode.id] = episode
        }
        if (episode.audioUrl.isNullOrBlank()) {
            Toast.makeText(requireContext(), "No audio URL", Toast.LENGTH_SHORT).show()
            return
        }
        PlaybackHelper.playEpisode(
            context = requireContext(),
            episode = episode,
            startPositionSeconds = 0.0,
            autoPlay = true
        )
        val args = bundleOf(
            "episodeId" to episode.id,
            "episodeTitle" to episode.title,
            "episodeUrl" to (episode.audioUrl ?: ""),
            "artworkUrl" to (episode.artworkUrl ?: episode.podcastArtwork ?: ""),
            "positionSeconds" to 0.0
        )
        findNavController().navigate(R.id.playerFragment, args)
    }
}

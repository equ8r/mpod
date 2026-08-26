package com.circpros.micahpod.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.circpros.micahpod.R
import com.circpros.micahpod.data.ServiceLocator
import com.circpros.micahpod.data.TimedListCache
import com.circpros.micahpod.databinding.FragmentEpisodeListBinding
import com.circpros.micahpod.repository.MicahPodRepository
import com.circpros.micahpod.repository.RepositoryResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EpisodeListFragment : Fragment() {
    private var _binding: FragmentEpisodeListBinding? = null
    private val binding get() = _binding!!
    private val repository = ServiceLocator.repository
    private var podcastTitle: String? = null
    private var podcastId: Int = -1
    private var loadGeneration = 0

    private val adapter = EpisodeAdapter { episode ->
        ServiceLocator.episodeCache[episode.id] = episode
        val args = bundleOf("episodeId" to episode.id)
        findNavController().navigate(R.id.episodeDetailFragment, args)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentEpisodeListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.episodeRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.episodeRecycler.adapter = adapter
        binding.episodeSwipeRefresh.setOnRefreshListener {
            if (podcastId != -1) loadEpisodes(forceRefresh = true)
        }

        podcastId = requireArguments().getInt("podcastId", -1)
        podcastTitle = requireArguments().getString("podcastTitle")
        if (podcastTitle != null) {
            requireActivity().title = podcastTitle
        }

        if (podcastId == -1) {
            binding.episodeError.text = getString(R.string.error_generic)
            binding.episodeError.isVisible = true
            binding.episodeProgress.isVisible = false
            return
        }

        loadEpisodes()
    }

    override fun onResume() {
        super.onResume()
        if (podcastId != -1) loadEpisodes(forceRefresh = false)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun loadEpisodes(forceRefresh: Boolean = false) {
        val generation = ++loadGeneration
        val cacheKey = MicahPodRepository.episodesCacheKey(podcastId)
        val cached = repository.peekEpisodes(cacheKey)
        val fresh = repository.isCacheFresh(cacheKey, TimedListCache.TTL_DEFAULT_MS)

        if (!cached.isNullOrEmpty()) {
            showEpisodes(cached)
            binding.episodeProgress.isVisible = false
            binding.episodeError.isVisible = false
            if (!forceRefresh && fresh) {
                binding.episodeSwipeRefresh.isRefreshing = false
                return
            }
            binding.episodeSwipeRefresh.isRefreshing = true
        } else {
            binding.episodeProgress.isVisible = true
            binding.episodeRecycler.isVisible = false
            binding.episodeError.isVisible = false
            binding.episodeSwipeRefresh.isRefreshing = false
        }

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                repository.getEpisodes(podcastId, forceRefresh = true)
            }
            if (_binding == null || generation != loadGeneration) return@launch

            when (result) {
                is RepositoryResult.Success -> showEpisodes(result.data)
                is RepositoryResult.Error -> {
                    if (adapter.itemCount == 0) {
                        binding.episodeError.text = result.message
                        binding.episodeError.isVisible = true
                        binding.episodeRecycler.isVisible = false
                    }
                }
            }
            binding.episodeProgress.isVisible = false
            binding.episodeSwipeRefresh.isRefreshing = false
        }
    }

    private fun showEpisodes(episodes: List<com.circpros.micahpod.data.Episode>) {
        val resolved = episodes.map { episode ->
            if (episode.podcastTitle.isNullOrBlank() && !podcastTitle.isNullOrBlank()) {
                episode.copy(podcastTitle = podcastTitle)
            } else {
                episode
            }
        }
        resolved.forEach { ServiceLocator.episodeCache[it.id] = it }
        adapter.submitList(resolved)
        binding.episodeRecycler.isVisible = true
        binding.episodeError.isVisible = false
    }
}

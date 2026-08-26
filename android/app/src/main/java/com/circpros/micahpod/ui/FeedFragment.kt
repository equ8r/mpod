package com.circpros.micahpod.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.circpros.micahpod.R
import com.circpros.micahpod.data.Episode
import com.circpros.micahpod.data.ServiceLocator
import com.circpros.micahpod.data.TimedListCache
import com.circpros.micahpod.databinding.FragmentFeedBinding
import com.circpros.micahpod.playback.PlaybackHelper
import com.circpros.micahpod.repository.MicahPodRepository
import com.circpros.micahpod.repository.RepositoryResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FeedFragment : Fragment() {
    private var _binding: FragmentFeedBinding? = null
    private val binding get() = _binding!!
    private val repository = ServiceLocator.repository
    private val handler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null
    private var loadGeneration = 0

    private var currentSort = "newest"
    private var currentQuery: String? = null
    private var recommendedOnly = false
    private var continueOnly = false

    private val adapter = FeedAdapter(
        onEpisodeClick = { episode ->
            if (continueOnly) resumeEpisode(episode) else openEpisodeDetail(episode)
        }
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFeedBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.feedRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.feedRecycler.adapter = adapter
        binding.feedSwipeRefresh.setOnRefreshListener { loadFeed(forceRefresh = true) }

        binding.searchInput.addTextChangedListener {
            searchRunnable?.let { handler.removeCallbacks(it) }
            searchRunnable = Runnable {
                currentQuery = it?.toString()?.trim()?.takeIf { q -> q.isNotEmpty() }
                if (continueOnly) {
                    continueOnly = false
                    binding.chipContinue.isChecked = false
                    binding.chipNewest.isChecked = true
                }
                loadFeed()
            }
            handler.postDelayed(searchRunnable!!, 400)
        }
        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchRunnable?.let { handler.removeCallbacks(it) }
                currentQuery = binding.searchInput.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                loadFeed()
                true
            } else false
        }

        binding.chipContinue.setOnClickListener {
            continueOnly = true
            recommendedOnly = false
            currentSort = "newest"
            binding.chipNewest.isChecked = false
            binding.chipOldest.isChecked = false
            binding.chipRecommended.isChecked = false
            binding.chipContinue.isChecked = true
            loadFeed()
        }
        binding.chipNewest.setOnClickListener {
            continueOnly = false
            currentSort = "newest"
            recommendedOnly = false
            binding.chipContinue.isChecked = false
            binding.chipOldest.isChecked = false
            binding.chipRecommended.isChecked = false
            binding.chipNewest.isChecked = true
            loadFeed()
        }
        binding.chipOldest.setOnClickListener {
            continueOnly = false
            currentSort = "oldest"
            recommendedOnly = false
            binding.chipContinue.isChecked = false
            binding.chipNewest.isChecked = false
            binding.chipRecommended.isChecked = false
            binding.chipOldest.isChecked = true
            loadFeed()
        }
        binding.chipRecommended.setOnClickListener {
            continueOnly = false
            recommendedOnly = true
            currentSort = "newest"
            binding.chipContinue.isChecked = false
            binding.chipNewest.isChecked = false
            binding.chipOldest.isChecked = false
            binding.chipRecommended.isChecked = true
            loadFeed()
        }

        loadFeed()
    }

    override fun onResume() {
        super.onResume()
        // Soft refresh only when Continue (changes often) or cache is stale.
        loadFeed(forceRefresh = false)
    }

    override fun onDestroyView() {
        searchRunnable?.let { handler.removeCallbacks(it) }
        super.onDestroyView()
        _binding = null
    }

    private fun currentCacheKey(): String = MicahPodRepository.feedCacheKey(
        sort = currentSort,
        query = currentQuery,
        recommended = recommendedOnly,
        continueOnly = continueOnly
    )

    private fun currentTtlMs(): Long =
        if (continueOnly) TimedListCache.TTL_CONTINUE_MS else TimedListCache.TTL_DEFAULT_MS

    private fun loadFeed(forceRefresh: Boolean = false) {
        val cacheKey = currentCacheKey()
        val generation = ++loadGeneration
        val cached = repository.peekEpisodes(cacheKey)
        val fresh = repository.isCacheFresh(cacheKey, currentTtlMs())

        if (!cached.isNullOrEmpty()) {
            showEpisodes(cached)
            binding.feedProgress.isVisible = false
            binding.feedError.isVisible = false
            if (!forceRefresh && fresh) {
                binding.feedSwipeRefresh.isRefreshing = false
                return
            }
            binding.feedSwipeRefresh.isRefreshing = true
        } else {
            binding.feedProgress.isVisible = true
            binding.feedRecycler.isVisible = false
            binding.feedError.isVisible = false
            binding.feedSwipeRefresh.isRefreshing = false
        }

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                when {
                    continueOnly -> repository.getInProgress(50, forceRefresh = true)
                    currentQuery != null || recommendedOnly || currentSort != "newest" -> {
                        repository.getFilteredFeed(
                            sort = currentSort,
                            query = currentQuery,
                            recommended = recommendedOnly,
                            forceRefresh = true
                        )
                    }
                    else -> repository.getFeed(forceRefresh = true)
                }
            }
            if (_binding == null || generation != loadGeneration) return@launch
            when (result) {
                is RepositoryResult.Success -> showEpisodes(result.data)
                is RepositoryResult.Error -> {
                    if (adapter.itemCount == 0) {
                        binding.feedError.text = result.message
                        binding.feedError.isVisible = true
                        binding.feedRecycler.isVisible = false
                    }
                }
            }
            binding.feedProgress.isVisible = false
            binding.feedSwipeRefresh.isRefreshing = false
        }
    }

    private fun showEpisodes(episodes: List<Episode>) {
        val resolved = episodes.map { ep ->
            val id = ep.resolvedId()
            ep.copy(id = id).also { ServiceLocator.episodeCache[id] = it }
        }
        adapter.submitList(resolved)
        binding.feedRecycler.isVisible = true
        if (resolved.isEmpty()) {
            binding.feedError.text = when {
                continueOnly -> "Nothing in progress. Play an episode to see it here."
                currentQuery != null -> "No results for \"$currentQuery\""
                else -> "No episodes yet. Subscribe to some podcasts!"
            }
            binding.feedError.isVisible = true
        } else {
            binding.feedError.isVisible = false
        }
    }

    private fun resumeEpisode(episode: Episode) {
        val id = episode.resolvedId()
        ServiceLocator.episodeCache[id] = episode.copy(id = id)
        PlaybackHelper.playEpisode(
            context = requireContext(),
            episode = episode.copy(id = id),
            startPositionSeconds = episode.positionSeconds,
            autoPlay = true
        )
        val args = bundleOf(
            "episodeId" to id,
            "episodeTitle" to episode.title,
            "episodeUrl" to (episode.audioUrl ?: ""),
            "artworkUrl" to (episode.artworkUrl ?: episode.podcastArtwork ?: ""),
            "positionSeconds" to (episode.positionSeconds ?: 0.0)
        )
        findNavController().navigate(R.id.playerFragment, args)
    }

    private fun openEpisodeDetail(episode: Episode) {
        ServiceLocator.episodeCache[episode.id] = episode
        val args = bundleOf("episodeId" to episode.id)
        findNavController().navigate(R.id.episodeDetailFragment, args)
    }
}

package com.circpros.micahpod.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.circpros.micahpod.R
import com.circpros.micahpod.data.Episode
import com.circpros.micahpod.data.ServiceLocator
import com.circpros.micahpod.data.TimedListCache
import com.circpros.micahpod.databinding.FragmentFeedBinding
import com.circpros.micahpod.playback.LocalPlaybackBookmark
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
    private var playedFilter: String? = null

    private val adapter = FeedAdapter(
        onEpisodeClick = { episode ->
            if (continueOnly) resumeEpisode(episode) else openEpisodeDetail(episode)
        },
        onMarkComplete = { episode -> confirmMarkComplete(episode) }
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
        ItemTouchHelper(ContinueSwipeCallback(isEnabled = { continueOnly }))
            .attachToRecyclerView(binding.feedRecycler)
        binding.feedSwipeRefresh.setOnRefreshListener {
            loadFeed(forceRefresh = true, showSpinner = true)
        }
        // DiffUtil + ItemTouchHelper often crash with "Inconsistency detected" if an
        // item animator runs mid-swipe during a feed refresh.
        binding.feedRecycler.itemAnimator = null

        binding.searchInput.addTextChangedListener {
            searchRunnable?.let { handler.removeCallbacks(it) }
            searchRunnable = Runnable {
                currentQuery = it?.toString()?.trim()?.takeIf { q -> q.isNotEmpty() }
                if (continueOnly || playedFilter != null) {
                    selectChip(sort = "newest")
                    return@Runnable
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
            selectChip(continueMode = true)
        }
        binding.chipNewest.setOnClickListener {
            selectChip(sort = "newest")
        }
        binding.chipOldest.setOnClickListener {
            selectChip(sort = "oldest")
        }
        binding.chipRecommended.setOnClickListener {
            selectChip(recommended = true)
        }
        binding.chipUnplayed.setOnClickListener {
            selectChip(sort = "unplayed", played = "0")
        }
        binding.chipPlayed.setOnClickListener {
            selectChip(sort = "played", played = "1")
        }
        binding.emptyBrowsePodcasts.setOnClickListener {
            findNavController().navigate(R.id.podcastListFragment)
        }
        binding.emptyOpenSettings.setOnClickListener {
            findNavController().navigate(R.id.settingsFragment)
        }

        loadFeed()
    }

    private fun selectChip(
        sort: String = "newest",
        recommended: Boolean = false,
        continueMode: Boolean = false,
        played: String? = null
    ) {
        continueOnly = continueMode
        recommendedOnly = recommended
        currentSort = sort
        playedFilter = played
        adapter.continueMode = continueMode
        binding.chipContinue.isChecked = continueMode
        binding.chipNewest.isChecked = !continueMode && !recommended && played == null && sort == "newest"
        binding.chipOldest.isChecked = !continueMode && !recommended && played == null && sort == "oldest"
        binding.chipRecommended.isChecked = recommended
        binding.chipUnplayed.isChecked = played == "0"
        binding.chipPlayed.isChecked = played == "1"
        loadFeed()
    }

    private fun confirmMarkComplete(episode: Episode) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.mark_complete_confirm_title)
            .setMessage(R.string.mark_complete_confirm_body)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.mark_complete) { _, _ -> markComplete(episode) }
            .show()
    }

    private fun markComplete(episode: Episode) {
        val id = episode.resolvedId()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                repository.markPlayed(id)
            }
            if (_binding == null) return@launch
            when (result) {
                is RepositoryResult.Success -> {
                    LocalPlaybackBookmark.clearEpisode(requireContext(), id)
                    Toast.makeText(requireContext(), R.string.marked_played, Toast.LENGTH_SHORT).show()
                    loadFeed(forceRefresh = true)
                }
                is RepositoryResult.Error -> {
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Soft background refresh when cache is stale — no swipe spinner (avoids
        // "refreshing then crash" from ItemTouchHelper + list diff races).
        loadFeed(forceRefresh = false, showSpinner = false)
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
        continueOnly = continueOnly,
        played = playedFilter
    )

    private fun currentTtlMs(): Long =
        if (continueOnly) TimedListCache.TTL_CONTINUE_MS else TimedListCache.TTL_DEFAULT_MS

    private fun loadFeed(forceRefresh: Boolean = false, showSpinner: Boolean = forceRefresh) {
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
            binding.feedSwipeRefresh.isRefreshing = showSpinner
        } else {
            binding.feedProgress.isVisible = true
            binding.feedRecycler.isVisible = false
            binding.feedError.isVisible = false
            binding.feedSwipeRefresh.isRefreshing = false
        }

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                when {
                    continueOnly -> repository.getInProgress(50, forceRefresh = true).let { r ->
                        when (r) {
                            is RepositoryResult.Success -> RepositoryResult.Success(
                                com.circpros.micahpod.data.FeedPage(episodes = r.data)
                            )
                            is RepositoryResult.Error -> RepositoryResult.Error(r.message)
                        }
                    }
                    else -> repository.getFilteredFeed(
                        sort = currentSort,
                        query = currentQuery,
                        recommended = recommendedOnly,
                        played = playedFilter,
                        forceRefresh = true
                    )
                }
            }
            if (_binding == null || generation != loadGeneration) return@launch
            when (result) {
                is RepositoryResult.Success -> showEpisodes(
                    result.data.episodes,
                    libraryEmpty = result.data.libraryEmpty,
                    feedHiddenOnly = result.data.feedHiddenOnly
                )
                is RepositoryResult.Error -> {
                    if (adapter.itemCount == 0) {
                        binding.feedError.text = result.message
                        binding.feedError.isVisible = true
                        binding.feedRecycler.isVisible = false
                        binding.emptyLibraryCta.isVisible = false
                    }
                }
            }
            binding.feedProgress.isVisible = false
            binding.feedSwipeRefresh.isRefreshing = false
        }
    }

    private fun showEpisodes(
        episodes: List<Episode>,
        libraryEmpty: Boolean = false,
        feedHiddenOnly: Boolean = false
    ) {
        val resolved = episodes.map { ep ->
            val id = ep.resolvedId()
            ep.copy(id = id).also { ServiceLocator.episodeCache[id] = it }
        }
        // Clear any in-progress swipe reveals before DiffUtil runs.
        for (i in 0 until binding.feedRecycler.childCount) {
            val child = binding.feedRecycler.getChildAt(i) ?: continue
            val vh = binding.feedRecycler.getChildViewHolder(child)
            adapter.resetReveal(vh)
        }
        adapter.continueMode = continueOnly
        try {
            adapter.submitList(resolved)
        } catch (t: Throwable) {
            // Last-resort recovery from rare RecyclerView inconsistency crashes.
            android.util.Log.e("FeedFragment", "submitList failed; resetting adapter", t)
            binding.feedRecycler.adapter = null
            binding.feedRecycler.adapter = adapter
            adapter.submitList(resolved)
        }
        binding.feedRecycler.isVisible = resolved.isNotEmpty()
        if (resolved.isNotEmpty()) {
            binding.feedError.isVisible = false
            binding.emptyLibraryCta.isVisible = false
            return
        }

        val defaultEmpty = !continueOnly && playedFilter == null && currentQuery == null
        if (defaultEmpty && (libraryEmpty || feedHiddenOnly || resolved.isEmpty())) {
            binding.emptyLibraryTitle.text = if (feedHiddenOnly) {
                getString(R.string.feed_hidden_title)
            } else {
                getString(R.string.empty_library_title)
            }
            binding.emptyLibraryBody.text = if (feedHiddenOnly) {
                getString(R.string.feed_hidden_body)
            } else {
                getString(R.string.empty_library_body)
            }
            binding.emptyLibraryCta.isVisible = true
            binding.feedError.isVisible = false
        } else {
            binding.emptyLibraryCta.isVisible = false
            binding.feedError.text = when {
                continueOnly -> "Nothing in progress. Play an episode to see it here."
                playedFilter == "0" -> "No unplayed episodes found."
                playedFilter == "1" -> "No played episodes found."
                currentQuery != null -> "No results for \"$currentQuery\""
                else -> getString(R.string.empty_library_body)
            }
            binding.feedError.isVisible = true
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

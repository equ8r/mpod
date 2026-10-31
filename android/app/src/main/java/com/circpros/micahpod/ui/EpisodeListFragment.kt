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
import androidx.recyclerview.widget.RecyclerView
import com.circpros.micahpod.R
import com.circpros.micahpod.data.Episode
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
    private var currentSort = "newest"

    private val loadedEpisodes = mutableListOf<Episode>()
    private var currentPage = 0
    private var totalEpisodes = 0
    private var perPage = PAGE_SIZE
    private var hasMore = false
    private var loadingMore = false
    private var initialLoadDone = false

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
        binding.episodeRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                val lastVisible = lm.findLastVisibleItemPosition()
                if (lastVisible >= adapter.itemCount - LOAD_MORE_THRESHOLD) {
                    loadNextPage()
                }
            }
        })
        binding.episodeSwipeRefresh.setOnRefreshListener {
            if (podcastId != -1) loadEpisodes(forceRefresh = true)
        }
        binding.chipEpisodeNewest.setOnClickListener { setSort("newest") }
        binding.chipEpisodeOldest.setOnClickListener { setSort("oldest") }

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
        if (podcastId != -1 && !initialLoadDone) {
            loadEpisodes(forceRefresh = false)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setSort(sort: String) {
        if (currentSort == sort) return
        currentSort = sort
        binding.chipEpisodeNewest.isChecked = sort == "newest"
        binding.chipEpisodeOldest.isChecked = sort == "oldest"
        loadedEpisodes.clear()
        adapter.submitList(emptyList())
        initialLoadDone = false
        hasMore = false
        currentPage = 0
        loadEpisodes(forceRefresh = true)
    }

    private fun cacheKey(): String = MicahPodRepository.episodesCacheKey(podcastId, currentSort)

    private fun loadEpisodes(forceRefresh: Boolean = false) {
        val generation = ++loadGeneration
        val key = cacheKey()
        val cached = repository.peekEpisodes(key)
        val fresh = repository.isCacheFresh(key, TimedListCache.TTL_DEFAULT_MS)

        if (!forceRefresh && !cached.isNullOrEmpty() && loadedEpisodes.isEmpty()) {
            replaceEpisodes(cached)
            hasMore = cached.size >= PAGE_SIZE
            currentPage = 1
            binding.episodeProgress.isVisible = false
            binding.episodeError.isVisible = false
            if (fresh) {
                binding.episodeSwipeRefresh.isRefreshing = false
                initialLoadDone = true
                fetchPage(page = 1, forceNetwork = false, generation = generation, replace = true)
                return
            }
            binding.episodeSwipeRefresh.isRefreshing = true
        } else if (loadedEpisodes.isEmpty()) {
            binding.episodeProgress.isVisible = true
            binding.episodeRecycler.isVisible = false
            binding.episodeError.isVisible = false
            binding.episodeSwipeRefresh.isRefreshing = false
        } else if (forceRefresh) {
            binding.episodeSwipeRefresh.isRefreshing = true
        }

        fetchPage(page = 1, forceNetwork = true, generation = generation, replace = true)
    }

    private fun loadNextPage() {
        if (!hasMore || loadingMore || podcastId == -1) return
        val next = currentPage + 1
        if (next <= 1) return
        fetchPage(page = next, forceNetwork = true, generation = loadGeneration, replace = false)
    }

    private fun fetchPage(
        page: Int,
        forceNetwork: Boolean,
        generation: Int,
        replace: Boolean
    ) {
        if (!replace) {
            if (loadingMore) return
            loadingMore = true
            binding.episodeLoadMoreProgress.isVisible = true
        }

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                repository.getEpisodes(
                    podcastId = podcastId,
                    page = page,
                    perPage = PAGE_SIZE,
                    sort = currentSort,
                    forceRefresh = forceNetwork
                )
            }
            if (_binding == null || generation != loadGeneration) return@launch

            when (result) {
                is RepositoryResult.Success -> {
                    val pageData = result.data
                    perPage = pageData.perPage
                    totalEpisodes = pageData.total
                    currentPage = pageData.page
                    hasMore = when {
                        pageData.total > 0 -> pageData.hasMore
                        pageData.episodes.size >= pageData.perPage -> true
                        else -> false
                    }
                    if (replace) {
                        replaceEpisodes(pageData.episodes)
                    } else {
                        appendEpisodes(pageData.episodes)
                    }
                    initialLoadDone = true
                    updateFooter()
                }
                is RepositoryResult.Error -> {
                    if (adapter.itemCount == 0) {
                        binding.episodeError.text = result.message
                        binding.episodeError.isVisible = true
                        binding.episodeRecycler.isVisible = false
                        binding.episodeFooter.isVisible = false
                    }
                }
            }
            loadingMore = false
            binding.episodeProgress.isVisible = false
            binding.episodeLoadMoreProgress.isVisible = false
            binding.episodeSwipeRefresh.isRefreshing = false
        }
    }

    private fun replaceEpisodes(episodes: List<Episode>) {
        loadedEpisodes.clear()
        loadedEpisodes.addAll(resolve(episodes))
        publishList()
    }

    private fun appendEpisodes(episodes: List<Episode>) {
        val existingIds = loadedEpisodes.mapTo(mutableSetOf()) { it.id }
        val fresh = resolve(episodes).filter { it.id !in existingIds }
        if (fresh.isEmpty()) {
            hasMore = false
            updateFooter()
            return
        }
        loadedEpisodes.addAll(fresh)
        publishList()
    }

    private fun resolve(episodes: List<Episode>): List<Episode> {
        return episodes.map { episode ->
            val resolved = if (episode.podcastTitle.isNullOrBlank() && !podcastTitle.isNullOrBlank()) {
                episode.copy(podcastTitle = podcastTitle)
            } else {
                episode
            }
            ServiceLocator.episodeCache[resolved.id] = resolved
            resolved
        }
    }

    private fun publishList() {
        adapter.submitList(loadedEpisodes.toList())
        binding.episodeRecycler.isVisible = true
        binding.episodeError.isVisible = false
        updateFooter()
    }

    private fun updateFooter() {
        val total = totalEpisodes
        val shown = loadedEpisodes.size
        if (shown == 0) {
            binding.episodeFooter.isVisible = false
            return
        }
        binding.episodeFooter.isVisible = true
        binding.episodeCountText.text = if (total > 0) {
            getString(R.string.episodes_shown_of_total, shown, total)
        } else {
            getString(R.string.episodes_shown, shown)
        }
        binding.episodeLoadMoreHint.isVisible = hasMore
        binding.episodeLoadMoreHint.text = getString(R.string.scroll_for_older_episodes)
    }

    companion object {
        private const val PAGE_SIZE = 50
        private const val LOAD_MORE_THRESHOLD = 8
    }
}

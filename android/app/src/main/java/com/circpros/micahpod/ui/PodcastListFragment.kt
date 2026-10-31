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
import com.circpros.micahpod.data.Podcast
import com.circpros.micahpod.data.ServiceLocator
import com.circpros.micahpod.databinding.FragmentPodcastListBinding
import com.circpros.micahpod.repository.MicahPodRepository
import com.circpros.micahpod.repository.RepositoryResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PodcastListFragment : Fragment() {
    private var _binding: FragmentPodcastListBinding? = null
    private val binding get() = _binding!!
    private val repository = ServiceLocator.repository
    private var loadGeneration = 0

    private val adapter = PodcastAdapter(
        onPodcastClick = { podcast -> openEpisodes(podcast) },
        onSubscriptionToggle = { podcast, subscribe -> toggleSubscription(podcast, subscribe) }
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPodcastListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().title = getString(R.string.podcasts)
        binding.podcastRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.podcastRecycler.adapter = adapter
        binding.podcastSwipeRefresh.setOnRefreshListener { loadPodcasts(forceRefresh = true) }
        loadPodcasts()
    }

    override fun onResume() {
        super.onResume()
        loadPodcasts(forceRefresh = false)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun loadPodcasts(forceRefresh: Boolean = false) {
        val generation = ++loadGeneration
        val cachedPodcasts = repository.peekPodcasts()
        val cachedSubs = repository.peekSubscriptions()?.map { it.id }?.toSet().orEmpty()
        val fresh = repository.isCacheFresh(MicahPodRepository.KEY_PODCASTS) &&
            repository.isCacheFresh(MicahPodRepository.KEY_SUBSCRIPTIONS)

        if (!cachedPodcasts.isNullOrEmpty()) {
            showPodcasts(cachedPodcasts, emptyList(), cachedSubs)
            binding.podcastProgress.isVisible = false
            binding.podcastError.isVisible = false
            if (!forceRefresh && fresh) {
                binding.podcastSwipeRefresh.isRefreshing = false
                return
            }
            binding.podcastSwipeRefresh.isRefreshing = true
        } else {
            binding.podcastProgress.isVisible = true
            binding.podcastRecycler.isVisible = false
            binding.podcastError.isVisible = false
            binding.podcastSwipeRefresh.isRefreshing = false
        }

        lifecycleScope.launch {
            val podcastsResult = withContext(Dispatchers.IO) {
                repository.getPodcasts(forceRefresh = true)
            }
            val subscriptionsResult = withContext(Dispatchers.IO) {
                repository.getSubscriptions(forceRefresh = true)
            }
            val communityResult = withContext(Dispatchers.IO) {
                repository.getCommunityPodcasts()
            }
            if (_binding == null || generation != loadGeneration) return@launch

            when (podcastsResult) {
                is RepositoryResult.Success -> {
                    val subscribedIds = if (subscriptionsResult is RepositoryResult.Success) {
                        subscriptionsResult.data.map { it.id }.toSet()
                    } else {
                        cachedSubs
                    }
                    val community = if (communityResult is RepositoryResult.Success) {
                        communityResult.data
                    } else {
                        emptyList()
                    }
                    showPodcasts(podcastsResult.data, community, subscribedIds)
                    binding.podcastError.isVisible = false
                }
                is RepositoryResult.Error -> {
                    if (adapter.itemCount == 0) {
                        binding.podcastError.text = podcastsResult.message
                        binding.podcastError.isVisible = true
                        binding.podcastRecycler.isVisible = false
                    }
                }
            }

            binding.podcastProgress.isVisible = false
            binding.podcastSwipeRefresh.isRefreshing = false
        }
    }

    private fun showPodcasts(
        podcasts: List<Podcast>,
        community: List<Podcast> = emptyList(),
        subscribedIds: Set<Int>
    ) {
        val featured = podcasts.map { podcast ->
            PodcastAdapter.PodcastItem(
                podcast = podcast,
                isSubscribed = subscribedIds.contains(podcast.id),
                sectionLabel = null
            )
        }
        val others = community
            .filter { c -> featured.none { it.podcast.id == c.id } }
            .mapIndexed { index, podcast ->
                PodcastAdapter.PodcastItem(
                    podcast = podcast,
                    isSubscribed = subscribedIds.contains(podcast.id),
                    sectionLabel = if (index == 0) getString(R.string.other_people_like) else null
                )
            }
        adapter.submitList(featured + others)
        binding.podcastRecycler.isVisible = true
    }

    private fun openEpisodes(podcast: Podcast) {
        val args = bundleOf(
            "podcastId" to podcast.id,
            "podcastTitle" to podcast.title
        )
        findNavController().navigate(R.id.action_podcasts_to_episodes, args)
    }

    private fun toggleSubscription(podcast: Podcast, subscribe: Boolean) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                repository.updateSubscription(podcast.id, subscribe)
            }

            when (result) {
                is RepositoryResult.Success -> {
                    val updated = adapter.currentList.map { item ->
                        if (item.podcast.id == podcast.id) {
                            item.copy(isSubscribed = subscribe)
                        } else {
                            item
                        }
                    }
                    adapter.submitList(updated)
                }
                is RepositoryResult.Error -> {
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

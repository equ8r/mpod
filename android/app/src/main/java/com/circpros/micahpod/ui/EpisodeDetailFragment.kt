package com.circpros.micahpod.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.circpros.micahpod.R
import com.circpros.micahpod.data.Episode
import com.circpros.micahpod.data.ServiceLocator
import com.circpros.micahpod.databinding.FragmentEpisodeDetailBinding
import com.circpros.micahpod.playback.PlaybackHelper
import com.circpros.micahpod.repository.RepositoryResult
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EpisodeDetailFragment : Fragment() {
    private var _binding: FragmentEpisodeDetailBinding? = null
    private val binding get() = _binding!!
    private val repository = ServiceLocator.repository
    private val commentAdapter = CommentAdapter()
    private var episodeId: Int = 0
    private var episode: Episode? = null
    private var currentRating: Int = 0
    private var descriptionExpanded = false
    private lateinit var stars: List<TextView>

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentEpisodeDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        episodeId = arguments?.getInt("episodeId") ?: 0
        episode = ServiceLocator.episodeCache[episodeId]

        stars = listOf(binding.star1, binding.star2, binding.star3, binding.star4, binding.star5)

        binding.commentsRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.commentsRecycler.adapter = commentAdapter

        if (episode == null) {
            // Episode not in cache, show error and go back
            Toast.makeText(requireContext(), "Episode not found", Toast.LENGTH_SHORT).show()
            findNavController().popBackStack()
            return
        }

        val ep = episode!!
        binding.detailTitle.text = ep.title
        binding.detailPodcast.text = ep.podcastTitle ?: ""

        val date = ep.pubDate?.substringBefore("T")?.takeIf { it.isNotBlank() }
            ?: ep.pubDate?.substringBefore(" ")
        val dur = ep.durationSeconds?.let {
            val h = it / 3600
            val m = (it % 3600) / 60
            if (h > 0) "${h}h ${m}m" else "${m} min"
        }
        binding.detailMeta.text = listOfNotNull(date, dur).joinToString(" • ")

        val art = ep.artworkUrl ?: ep.podcastArtwork
        binding.detailArtwork.load(art) { crossfade(true) }

        // Badges
        val showBadges = ep.completed == true || (ep.recScore != null && ep.recScore >= 0.7)
        binding.badgeRow.isVisible = showBadges
        binding.playedBadge.isVisible = ep.completed == true
        binding.recBadge.isVisible = ep.recScore != null && ep.recScore >= 0.7

        // Description
        val descText = (ep.description ?: "").replace(Regex("<[^>]*>"), "").trim()
        binding.detailDescription.text = descText.ifEmpty { "(No description available)" }
        binding.readMoreButton.isVisible = descText.length > 200
        binding.readMoreButton.setOnClickListener {
            descriptionExpanded = !descriptionExpanded
            binding.detailDescription.maxLines = if (descriptionExpanded) Int.MAX_VALUE else 6
            binding.readMoreButton.text = if (descriptionExpanded) "Show less" else "Read more"
        }

        // Inline tags from episode object
        if (!ep.tags.isNullOrEmpty()) {
            binding.tagsHeader.isVisible = true
            binding.tagsChipGroup.isVisible = true
            renderTags(ep.tags)
        }

        // Rating
        currentRating = ep.userRating ?: 0
        updateStars(currentRating)

        // Star click listeners
        stars.forEachIndexed { index, star ->
            star.setOnClickListener { submitRating(index + 1) }
        }

        binding.detailPlayButton.setOnClickListener {
            episode?.let { playEpisode(it) }
        }

        binding.detailQueueButton.setOnClickListener {
            val ep = episode ?: return@setOnClickListener
            if (ep.audioUrl.isNullOrBlank()) {
                Toast.makeText(requireContext(), "No audio URL for this episode", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val added = ServiceLocator.queueManager.add(ep)
            if (!added) {
                Toast.makeText(requireContext(), "Already in queue", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lifecycleScope.launch {
                val qm = ServiceLocator.queueManager
                val result = withContext(Dispatchers.IO) {
                    repository.saveQueue(
                        queueJson = qm.toJsonArray(),
                        queueUpdatedAt = qm.clientUpdatedAt()
                    )
                }
                if (result is RepositoryResult.Error) {
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(
                        requireContext(),
                        "Added to queue (${ServiceLocator.queueManager.size()})",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        binding.postCommentButton.setOnClickListener {
            val body = binding.commentInput.text?.toString()?.trim() ?: ""
            if (body.isNotEmpty()) postComment(body)
        }

        // Load tags from API if not inline
        if (episode?.tags.isNullOrEmpty()) loadTags()
        loadComments()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun updateStars(rating: Int) {
        val activeColor = MaterialColors.getColor(requireView(), com.google.android.material.R.attr.colorPrimary)
        val inactiveColor = MaterialColors.getColor(requireView(), com.google.android.material.R.attr.colorOutline)
        stars.forEachIndexed { index, star ->
            star.setTextColor(if (index < rating) activeColor else inactiveColor)
        }
    }

    private fun submitRating(rating: Int) {
        currentRating = rating
        updateStars(rating)
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { repository.postRating(episodeId, rating) }
        }
    }

    private fun renderTags(tags: List<com.circpros.micahpod.data.Tag>) {
        binding.tagsChipGroup.removeAllViews()
        tags.forEach { tag ->
            val chip = Chip(requireContext())
            chip.text = tag.name
            chip.isCheckable = false
            chip.isClickable = true
            if (tag.type == "guest") {
                chip.chipIcon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_podcasts)
                chip.isChipIconVisible = true
            }
            chip.setOnClickListener {
                // Navigate back to feed filtered by this tag
                // TODO: pass tag filter to feed
                Toast.makeText(requireContext(), "Tag: ${tag.name}", Toast.LENGTH_SHORT).show()
            }
            binding.tagsChipGroup.addView(chip)
        }
    }

    private fun loadTags() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { repository.getTags(episodeId) }
            if (result is RepositoryResult.Success && result.data.isNotEmpty()) {
                binding.tagsHeader.isVisible = true
                binding.tagsChipGroup.isVisible = true
                renderTags(result.data)
            }
        }
    }

    private fun loadComments() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { repository.getComments(episodeId) }
            if (result is RepositoryResult.Success) {
                val count = result.data.size
                binding.commentCountText.text = if (count > 0) "$count comment${if (count != 1) "s" else ""}" else ""
                if (result.data.isEmpty()) {
                    binding.noCommentsText.isVisible = true
                    binding.commentsRecycler.isVisible = false
                } else {
                    binding.noCommentsText.isVisible = false
                    binding.commentsRecycler.isVisible = true
                    commentAdapter.submitList(result.data)
                }
            }
        }
    }

    private fun postComment(body: String) {
        binding.postCommentButton.isEnabled = false
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                repository.postComment(episodeId, body)
            }
            binding.postCommentButton.isEnabled = true
            when (result) {
                is RepositoryResult.Success -> {
                    binding.commentInput.setText("")
                    Toast.makeText(requireContext(), "Comment posted", Toast.LENGTH_SHORT).show()
                    loadComments()
                }
                is RepositoryResult.Error -> {
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun playEpisode(episode: Episode) {
        val context = requireContext()
        if (episode.audioUrl.isNullOrBlank()) {
            Toast.makeText(context, "Episode has no audio URL", Toast.LENGTH_SHORT).show()
            return
        }

        PlaybackHelper.playEpisode(
            context = context,
            episode = episode,
            startPositionSeconds = episode.positionSeconds,
            autoPlay = true
        )

        val args = bundleOf(
            "episodeId" to episode.id,
            "episodeTitle" to episode.title,
            "episodeUrl" to episode.audioUrl,
            "artworkUrl" to (episode.artworkUrl ?: episode.podcastArtwork ?: ""),
            "positionSeconds" to (episode.positionSeconds ?: 0.0)
        )
        findNavController().navigate(R.id.playerFragment, args)
    }
}

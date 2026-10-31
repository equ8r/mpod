package com.circpros.micahpod.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.circpros.micahpod.data.Episode
import com.circpros.micahpod.databinding.ItemFeedEpisodeBinding

class FeedAdapter(
    private val onEpisodeClick: (Episode) -> Unit,
    private val onMarkComplete: ((Episode) -> Unit)? = null
) : ListAdapter<Episode, FeedAdapter.FeedViewHolder>(FeedDiff) {

    /** When true, Continue rows can reveal "Mark complete". Applied on next bind/submitList. */
    var continueMode: Boolean = false

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FeedViewHolder {
        val binding = ItemFeedEpisodeBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return FeedViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FeedViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    fun episodeAt(position: Int): Episode? =
        if (position in 0 until itemCount) getItem(position) else null

    fun resetReveal(holder: RecyclerView.ViewHolder) {
        if (holder is FeedViewHolder) holder.resetReveal()
    }

    inner class FeedViewHolder(
        private val binding: ItemFeedEpisodeBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(episode: Episode) {
            resetReveal()
            binding.feedMarkCompleteAction.isVisible = continueMode && onMarkComplete != null
            binding.feedEpisodeTitle.text = episode.title
            binding.feedPodcastTitle.text = episode.podcastTitle ?: ""

            val date = episode.pubDate?.let { relativeTime(it) } ?: ""
            val dur = episode.durationSeconds?.let {
                val h = it / 3600
                val m = (it % 3600) / 60
                if (h > 0) "${h}h ${m}m" else "${m} min"
            } ?: ""
            val progress = episode.positionSeconds?.takeIf { it >= 30 }?.let {
                "At ${com.circpros.micahpod.playback.PlaybackHelper.formatClock(it)}"
            }
            binding.feedMeta.text = listOf(progress, date, dur).filter { !it.isNullOrEmpty() }.joinToString(" • ")

            val art = episode.artworkUrl ?: episode.podcastArtwork
            binding.feedArtwork.load(art) { crossfade(true) }

            val comments = episode.commentCount ?: 0
            binding.feedCommentCount.isVisible = comments > 0
            if (comments > 0) {
                binding.feedCommentCount.text = "💬 $comments"
            }

            val played = episode.completed == true
            binding.feedPlayedBanner.isVisible = played
            binding.feedPlayedBadge.isVisible = false
            binding.feedForegroundCard.alpha = if (played) 0.92f else 1f

            binding.feedForegroundCard.setOnClickListener { onEpisodeClick(episode) }
            binding.feedMarkCompleteAction.setOnClickListener {
                onMarkComplete?.invoke(episode)
                resetReveal()
            }
        }

        fun resetReveal() {
            binding.feedForegroundCard.translationX = 0f
        }

        fun foreground() = binding.feedForegroundCard

        fun revealWidth(): Float =
            if (binding.feedMarkCompleteAction.isVisible) {
                binding.feedMarkCompleteAction.width.toFloat().coerceAtLeast(
                    binding.feedMarkCompleteAction.resources.displayMetrics.density * 120f
                )
            } else {
                0f
            }
    }

    private fun relativeTime(dateString: String): String {
        return try {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            val date = fmt.parse(dateString.substringBefore("T").substringBefore(" "))
                ?: return dateString.substringBefore("T")
            val diffMs = System.currentTimeMillis() - date.time
            val days = (diffMs / 86400000).toInt()
            when {
                days < 1 -> "Today"
                days == 1 -> "Yesterday"
                days < 7 -> "$days days ago"
                days < 30 -> "${days / 7} week${if (days / 7 != 1) "s" else ""} ago"
                else -> "${days / 30} month${if (days / 30 != 1) "s" else ""} ago"
            }
        } catch (e: Exception) {
            dateString.substringBefore("T").substringBefore(" ")
        }
    }

    private object FeedDiff : DiffUtil.ItemCallback<Episode>() {
        override fun areItemsTheSame(oldItem: Episode, newItem: Episode) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Episode, newItem: Episode) = oldItem == newItem
    }
}

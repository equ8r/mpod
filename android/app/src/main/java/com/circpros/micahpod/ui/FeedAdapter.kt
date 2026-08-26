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
    private val onEpisodeClick: (Episode) -> Unit
) : ListAdapter<Episode, FeedAdapter.FeedViewHolder>(FeedDiff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FeedViewHolder {
        val binding = ItemFeedEpisodeBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return FeedViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FeedViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class FeedViewHolder(
        private val binding: ItemFeedEpisodeBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(episode: Episode) {
            binding.feedEpisodeTitle.text = episode.title
            binding.feedPodcastTitle.text = episode.podcastTitle ?: ""

            // Relative time / progress
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

            // Comment count
            val comments = episode.commentCount ?: 0
            binding.feedCommentCount.isVisible = comments > 0
            if (comments > 0) {
                binding.feedCommentCount.text = "💬 $comments"
            }

            // Played badge
            binding.feedPlayedBadge.isVisible = episode.completed == true

            binding.root.setOnClickListener { onEpisodeClick(episode) }
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

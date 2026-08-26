package com.circpros.micahpod.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.circpros.micahpod.R
import com.circpros.micahpod.data.Podcast
import com.circpros.micahpod.databinding.ItemPodcastBinding

class PodcastAdapter(
    private val onPodcastClick: (Podcast) -> Unit,
    private val onSubscriptionToggle: (Podcast, Boolean) -> Unit
) : ListAdapter<PodcastAdapter.PodcastItem, PodcastAdapter.PodcastViewHolder>(PodcastDiff) {

    data class PodcastItem(
        val podcast: Podcast,
        val isSubscribed: Boolean
    )

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PodcastViewHolder {
        val binding = ItemPodcastBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return PodcastViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PodcastViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class PodcastViewHolder(
        private val binding: ItemPodcastBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: PodcastItem) {
            binding.podcastTitle.text = item.podcast.title
            binding.podcastCount.text = itemView.context.getString(
                R.string.episodes
            ) + ": " + item.podcast.episodeCount

            binding.podcastArtwork.load(item.podcast.artworkUrl) {
                crossfade(true)
            }

            val buttonText = if (item.isSubscribed) {
                R.string.unsubscribe
            } else {
                R.string.subscribe
            }
            binding.subscribeButton.setText(buttonText)

            binding.root.setOnClickListener {
                onPodcastClick(item.podcast)
            }
            binding.subscribeButton.setOnClickListener {
                onSubscriptionToggle(item.podcast, !item.isSubscribed)
            }
        }
    }

    private object PodcastDiff : DiffUtil.ItemCallback<PodcastItem>() {
        override fun areItemsTheSame(oldItem: PodcastItem, newItem: PodcastItem): Boolean {
            return oldItem.podcast.id == newItem.podcast.id
        }

        override fun areContentsTheSame(oldItem: PodcastItem, newItem: PodcastItem): Boolean {
            return oldItem == newItem
        }
    }
}

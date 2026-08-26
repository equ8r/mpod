package com.circpros.micahpod.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.circpros.micahpod.data.Episode
import com.circpros.micahpod.databinding.ItemEpisodeBinding

class EpisodeAdapter(
    private val onEpisodeClick: (Episode) -> Unit
) : ListAdapter<Episode, EpisodeAdapter.EpisodeViewHolder>(EpisodeDiff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpisodeViewHolder {
        val binding = ItemEpisodeBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return EpisodeViewHolder(binding)
    }

    override fun onBindViewHolder(holder: EpisodeViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class EpisodeViewHolder(
        private val binding: ItemEpisodeBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(episode: Episode) {
            binding.episodeTitle.text = episode.title
            val podcastTitle = episode.podcastTitle
            binding.episodePodcast.text = podcastTitle.orEmpty()
            binding.episodePodcast.isVisible = !podcastTitle.isNullOrBlank()
            val date = episode.pubDate?.substringBefore(" ") ?: ""
            val dur = episode.durationSeconds?.let {
                val m = it / 60; val s = it % 60
                "${m}m ${s}s"
            } ?: ""
            binding.episodeMeta.text = listOf(date, dur).filter { it.isNotEmpty() }.joinToString(" • ")

            val art = episode.artworkUrl ?: episode.podcastArtwork
            binding.episodeArtwork.load(art) {
                crossfade(true)
            }

            binding.root.setOnClickListener {
                onEpisodeClick(episode)
            }
        }
    }

    private object EpisodeDiff : DiffUtil.ItemCallback<Episode>() {
        override fun areItemsTheSame(oldItem: Episode, newItem: Episode): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Episode, newItem: Episode): Boolean {
            return oldItem == newItem
        }
    }
}

package com.circpros.micahpod.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.circpros.micahpod.data.Episode
import com.circpros.micahpod.databinding.ItemContinueListeningBinding
import com.circpros.micahpod.playback.PlaybackHelper

class ContinueListeningAdapter(
    private val onResume: (Episode) -> Unit
) : ListAdapter<Episode, ContinueListeningAdapter.VH>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemContinueListeningBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VH(
        private val binding: ItemContinueListeningBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(episode: Episode) {
            binding.continuePodcast.text = episode.podcastTitle.orEmpty()
            binding.continueTitle.text = episode.title
            val at = PlaybackHelper.formatClock(episode.positionSeconds ?: 0.0)
            val dur = episode.durationSeconds?.let { PlaybackHelper.formatClock(it.toDouble()) }
            binding.continueMeta.text = if (dur != null) "At $at / $dur" else "At $at"
            binding.continueArtwork.load(episode.artworkUrl ?: episode.podcastArtwork) {
                crossfade(true)
            }
            binding.continueResume.setOnClickListener { onResume(episode) }
            binding.root.setOnClickListener { onResume(episode) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<Episode>() {
        override fun areItemsTheSame(oldItem: Episode, newItem: Episode) =
            oldItem.resolvedId() == newItem.resolvedId()

        override fun areContentsTheSame(oldItem: Episode, newItem: Episode) = oldItem == newItem
    }
}

package com.circpros.micahpod.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.circpros.micahpod.data.QueueEpisode
import com.circpros.micahpod.databinding.ItemQueueBinding

class QueueAdapter(
    private val onPlay: (QueueEpisode, Int) -> Unit,
    private val onRemove: (Int) -> Unit
) : ListAdapter<QueueEpisode, QueueAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<QueueEpisode>() {
            override fun areItemsTheSame(oldItem: QueueEpisode, newItem: QueueEpisode): Boolean =
                oldItem.key() == newItem.key()

            override fun areContentsTheSame(oldItem: QueueEpisode, newItem: QueueEpisode): Boolean =
                oldItem == newItem
        }
    }

    inner class VH(val binding: ItemQueueBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: QueueEpisode, position: Int) {
            binding.queueItemTitle.text = item.title ?: "Untitled"
            binding.queueItemPodcast.text = item.podcastTitle.orEmpty()
            binding.queueItemArt.load(item.artworkUrl ?: item.podcastArtwork) { crossfade(true) }
            binding.queueItemPlay.setOnClickListener { onPlay(item, bindingAdapterPosition) }
            binding.queueItemRemove.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onRemove(pos)
            }
            binding.root.setOnClickListener { onPlay(item, bindingAdapterPosition) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemQueueBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position), position)
    }
}

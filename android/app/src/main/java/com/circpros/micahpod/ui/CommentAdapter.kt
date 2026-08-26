package com.circpros.micahpod.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.circpros.micahpod.data.Comment
import com.circpros.micahpod.databinding.ItemCommentBinding

class CommentAdapter : ListAdapter<Comment, CommentAdapter.CommentViewHolder>(CommentDiff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CommentViewHolder {
        val binding = ItemCommentBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return CommentViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CommentViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class CommentViewHolder(
        private val binding: ItemCommentBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(comment: Comment) {
            binding.commentUsername.text = comment.username ?: "Anonymous"
            binding.commentDate.text = comment.createdAt?.substringBefore("T") ?: ""
            binding.commentBody.text = comment.body

            // Render replies
            binding.repliesContainer.removeAllViews()
            comment.replies?.forEach { reply ->
                val replyBinding = ItemCommentBinding.inflate(
                    LayoutInflater.from(binding.root.context),
                    binding.repliesContainer,
                    false
                )
                replyBinding.commentUsername.text = reply.username ?: "Anonymous"
                replyBinding.commentDate.text = reply.createdAt?.substringBefore("T") ?: ""
                replyBinding.commentBody.text = reply.body
                binding.repliesContainer.addView(replyBinding.root)
            }
        }
    }

    private object CommentDiff : DiffUtil.ItemCallback<Comment>() {
        override fun areItemsTheSame(oldItem: Comment, newItem: Comment) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Comment, newItem: Comment) = oldItem == newItem
    }
}

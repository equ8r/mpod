package com.circpros.micahpod.ui

import android.graphics.Canvas
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/**
 * Swipe left to reveal the "Mark complete" action on Continue rows.
 * Does not dismiss the row — the revealed button must be tapped.
 */
class ContinueSwipeCallback(
    private val isEnabled: () -> Boolean
) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {

    override fun getSwipeDirs(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder
    ): Int = if (isEnabled()) ItemTouchHelper.LEFT else 0

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean = false

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        // Threshold is set high so this should rarely fire; snap open if it does.
        snap(viewHolder, open = true)
    }

    override fun onChildDraw(
        c: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean
    ) {
        val holder = viewHolder as? FeedAdapter.FeedViewHolder ?: return
        val reveal = holder.revealWidth().coerceAtLeast(1f)
        holder.foreground().translationX = dX.coerceIn(-reveal, 0f)
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        val holder = viewHolder as? FeedAdapter.FeedViewHolder ?: return
        val reveal = holder.revealWidth()
        val open = abs(holder.foreground().translationX) >= reveal * 0.45f
        snap(viewHolder, open = open)
    }

    override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float = 2f

    override fun getSwipeEscapeVelocity(defaultValue: Float): Float = defaultValue * 8f

    private fun snap(viewHolder: RecyclerView.ViewHolder, open: Boolean) {
        val holder = viewHolder as? FeedAdapter.FeedViewHolder ?: return
        val reveal = holder.revealWidth()
        val target = if (open) -reveal else 0f
        holder.foreground().animate().translationX(target).setDuration(160).start()
    }
}

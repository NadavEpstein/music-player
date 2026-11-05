package com.nadavepstein.musicplayer.ui

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.nadavepstein.musicplayer.R
import com.nadavepstein.musicplayer.model.AppTheme
import com.nadavepstein.musicplayer.model.QueueEntry
import com.nadavepstein.musicplayer.model.QueueItemStatus

/**
 * RecyclerView Adapter for the Expandable YouTube Music-style Queue (Sections 5, 6, 8, 9).
 *
 * - Clearly distinguishes:
 *   * Previously PLAYED and SKIPPED songs (retained visibly above the current song with subtle muted styling).
 *   * The CURRENTLY PLAYING song (highlighted, fixed, not draggable).
 *   * UPCOMING_PRIORITY ("Play Next") and UPCOMING songs (with visible drag handle for manual reordering).
 * - Ordinary tap selects and starts playing any song immediately (skipping intervening songs without deleting them).
 * - Long press opens the contextual menu with strictly:
 *   1. Add to Favorites
 *   2. Add to Playlist
 *   3. Remove from Queue
 */
class QueueAdapter(
    private val onSongTap: (QueueEntry) -> Unit,
    private val onSongLongPress: (View, QueueEntry) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit
) : RecyclerView.Adapter<QueueAdapter.QueueViewHolder>() {

    private val entries = mutableListOf<QueueEntry>()
    private var currentTheme: AppTheme = AppTheme.DARK

    fun submitList(newEntries: List<QueueEntry>, theme: AppTheme) {
        entries.clear()
        entries.addAll(newEntries)
        currentTheme = theme
        notifyDataSetChanged()
    }

    fun getItemAt(position: Int): QueueEntry? = entries.getOrNull(position)

    fun isPositionDraggableUpcoming(position: Int): Boolean {
        val entry = entries.getOrNull(position) ?: return false
        return entry.status == QueueItemStatus.UPCOMING || entry.status == QueueItemStatus.UPCOMING_PRIORITY
    }

    fun onItemMovePreview(fromPosition: Int, toPosition: Int): Boolean {
        if (!isPositionDraggableUpcoming(fromPosition) || !isPositionDraggableUpcoming(toPosition)) {
            return false
        }
        val moved = entries.removeAt(fromPosition)
        entries.add(toPosition, moved)
        notifyItemMoved(fromPosition, toPosition)
        return true
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QueueViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_queue_song, parent, false)
        return QueueViewHolder(view)
    }

    override fun getItemCount(): Int = entries.size

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: QueueViewHolder, position: Int) {
        val entry = entries[position]
        val palette = ThemePalette.forTheme(currentTheme)

        holder.tvTitle.text = entry.song.displayTitle

        when (entry.status) {
            QueueItemStatus.CURRENT -> {
                holder.root.setBackgroundColor(palette.currentRowHighlightColor)
                holder.tvBadge.text = "▶"
                holder.tvBadge.setTextColor(palette.accentColor)
                holder.tvTitle.setTextColor(palette.primaryTextColor)
                holder.tvTitle.setTypeface(null, Typeface.BOLD)
                // Currently playing song remains fixed and cannot be manually dragged
                holder.ivDragHandle.visibility = View.INVISIBLE
            }
            QueueItemStatus.PLAYED -> {
                holder.root.setBackgroundColor(palette.backgroundColor)
                holder.tvBadge.text = "✓"
                holder.tvBadge.setTextColor(palette.mutedPlayedTextColor)
                holder.tvTitle.setTextColor(palette.mutedPlayedTextColor)
                holder.tvTitle.setTypeface(null, Typeface.NORMAL)
                holder.ivDragHandle.visibility = View.INVISIBLE
            }
            QueueItemStatus.SKIPPED -> {
                holder.root.setBackgroundColor(palette.backgroundColor)
                holder.tvBadge.text = "↷"
                holder.tvBadge.setTextColor(palette.mutedPlayedTextColor)
                holder.tvTitle.setTextColor(palette.mutedPlayedTextColor)
                holder.tvTitle.setTypeface(null, Typeface.ITALIC)
                holder.ivDragHandle.visibility = View.INVISIBLE
            }
            QueueItemStatus.UPCOMING_PRIORITY -> {
                holder.root.setBackgroundColor(palette.backgroundColor)
                holder.tvBadge.text = "⇥"
                holder.tvBadge.setTextColor(palette.accentColor)
                holder.tvTitle.setTextColor(palette.primaryTextColor)
                holder.tvTitle.setTypeface(null, Typeface.NORMAL)
                holder.ivDragHandle.visibility = View.VISIBLE
                holder.ivDragHandle.setColorFilter(palette.secondaryTextColor)
            }
            QueueItemStatus.UPCOMING -> {
                holder.root.setBackgroundColor(palette.backgroundColor)
                holder.tvBadge.text = "•"
                holder.tvBadge.setTextColor(palette.secondaryTextColor)
                holder.tvTitle.setTextColor(palette.primaryTextColor)
                holder.tvTitle.setTypeface(null, Typeface.NORMAL)
                holder.ivDragHandle.visibility = View.VISIBLE
                holder.ivDragHandle.setColorFilter(palette.secondaryTextColor)
            }
        }

        // Ordinary tap starts playing the selected song immediately
        holder.root.setOnClickListener {
            onSongTap(entry)
        }

        // Long press opens the contextual menu without triggering ordinary tap
        holder.root.setOnLongClickListener { v ->
            onSongLongPress(v, entry)
            true
        }

        holder.ivDragHandle.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN && isPositionDraggableUpcoming(holder.bindingAdapterPosition)) {
                onStartDrag(holder)
            }
            false
        }
    }

    class QueueViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val root: View = itemView.findViewById(R.id.queueItemRoot)
        val tvBadge: TextView = itemView.findViewById(R.id.tvQueueStatusBadge)
        val tvTitle: TextView = itemView.findViewById(R.id.tvQueueSongTitle)
        val ivDragHandle: ImageView = itemView.findViewById(R.id.ivQueueDragHandle)
    }
}

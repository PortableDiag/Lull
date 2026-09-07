package com.lull.player

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class TrackAdapter(
    private val scope: CoroutineScope,
    private val onClick: (AudioItem, Int) -> Unit,
    private val onLongClick: (AudioItem, Int) -> Unit = { _, _ -> },
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit = {}
) : ListAdapter<AudioItem, TrackAdapter.VH>(DIFF) {

    init { setHasStableIds(true) }

    var nowPlayingId: Long = -1
        set(value) { field = value; notifyDataSetChanged() }

    /** Shows the reorder handle (playlist view only). Off in the library and while searching. */
    var dragHandles: Boolean = false
        set(value) { if (field != value) { field = value; notifyDataSetChanged() } }

    // ---------------- Multi-selection ----------------

    /**
     * Selected track ids, in the order they were picked. Ids rather than positions, so a selection
     * survives the list being re-submitted (a search keystroke, a playlist edit) — which positions
     * would not.
     */
    private val selection = LinkedHashSet<Long>()

    val selectedIds: List<Long> get() = selection.toList()
    val selectedCount: Int get() = selection.size
    val selectionMode: Boolean get() = selection.isNotEmpty()

    fun isSelected(id: Long) = id in selection

    fun toggle(id: Long) {
        if (!selection.remove(id)) selection.add(id)
        val idx = currentList.indexOfFirst { it.id == id }
        if (idx >= 0) notifyItemChanged(idx)
    }

    /** Selects everything currently shown — the filtered list, not the whole library. */
    fun selectAll() {
        selection.addAll(currentList.map { it.id })
        notifyDataSetChanged()
    }

    fun clearSelection() {
        if (selection.isEmpty()) return
        selection.clear()
        notifyDataSetChanged()
    }

    /** The selected tracks in list order, which is the order they should be queued or added in. */
    fun selectedTracks(): List<AudioItem> = currentList.filter { it.id in selection }

    override fun getItemId(position: Int): Long = getItem(position).id

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<AudioItem>() {
            override fun areItemsTheSame(a: AudioItem, b: AudioItem) = a.id == b.id
            override fun areContentsTheSame(a: AudioItem, b: AudioItem) = a == b
        }

        fun formatDuration(ms: Long): String {
            if (ms <= 0) return "0:00"
            val total = ms / 1000
            val h = TimeUnit.SECONDS.toHours(total)
            val m = TimeUnit.SECONDS.toMinutes(total) % 60
            val s = total % 60
            return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
            else String.format("%d:%02d", m, s)
        }
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val art: ImageView = view.findViewById(R.id.art)
        val selectOverlay: View = view.findViewById(R.id.selectOverlay)
        val equalizer: ImageView = view.findViewById(R.id.playingMark)
        val title: TextView = view.findViewById(R.id.title)
        val subtitle: TextView = view.findViewById(R.id.subtitle)
        val duration: TextView = view.findViewById(R.id.duration)
        val dragHandle: ImageView = view.findViewById(R.id.dragHandle)
        var job: Job? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_track, parent, false)
        return VH(v)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val selected = isSelected(item.id)

        holder.title.text = item.title
        holder.subtitle.text = item.artist.ifBlank { holder.itemView.context.getString(R.string.unknown_artist) }
        holder.duration.text = formatDuration(item.durationMs)
        holder.equalizer.visibility =
            if (item.id == nowPlayingId && !selected) View.VISIBLE else View.GONE
        holder.itemView.isActivated = selected
        holder.selectOverlay.visibility = if (selected) View.VISIBLE else View.GONE
        holder.itemView.setOnClickListener { onClick(item, holder.bindingAdapterPosition) }
        holder.itemView.setOnLongClickListener { onLongClick(item, holder.bindingAdapterPosition); true }

        // Reordering and selecting are different gestures on the same row; hide the handle while
        // a selection is running so a drag can't half-apply to rows the user is picking.
        holder.dragHandle.visibility =
            if (dragHandles && !selectionMode) View.VISIBLE else View.GONE
        holder.dragHandle.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) onStartDrag(holder)
            false
        }

        holder.job?.cancel()
        val cached = ArtLoader.cached(item)
        if (cached != null) {
            holder.art.setImageBitmap(cached)
        } else {
            holder.art.setImageResource(R.drawable.bg_art_placeholder)
            holder.job = scope.launch {
                val bmp = ArtLoader.load(holder.itemView.context, item, 160)
                if (bmp != null && holder.bindingAdapterPosition == position) {
                    holder.art.setImageBitmap(bmp)
                }
            }
        }
    }

    /** Reflects a drag reorder in the list. Called for each single-step move by ItemTouchHelper. */
    fun moveItem(from: Int, to: Int) {
        if (from == to || from !in currentList.indices || to !in currentList.indices) return
        val list = currentList.toMutableList()
        list.add(to, list.removeAt(from))
        submitList(list)
    }

    override fun onViewRecycled(holder: VH) {
        holder.job?.cancel(); holder.job = null
    }
}

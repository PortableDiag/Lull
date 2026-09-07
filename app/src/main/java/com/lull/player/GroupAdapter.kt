package com.lull.player

import android.view.LayoutInflater
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

/**
 * The rows of a grouped view — folders, artists, albums, genres or playlists. Tapping one drills
 * into its tracks; long-pressing selects it, so a whole album or folder can be added to a playlist
 * without opening it first.
 */
class GroupAdapter(
    private val scope: CoroutineScope,
    private val onClick: (Group) -> Unit,
    private val onLongClick: (Group) -> Unit,
    private val onMenu: (Group, View) -> Unit
) : ListAdapter<Group, GroupAdapter.VH>(DIFF) {

    /** The placeholder drawn when a group has no artwork — it names the axis being browsed. */
    var iconRes: Int = R.drawable.ic_folder
        set(value) { if (field != value) { field = value; notifyDataSetChanged() } }

    /** Shows the per-row overflow button. On in the Playlists tab, where rows have actions. */
    var rowMenus: Boolean = false
        set(value) { if (field != value) { field = value; notifyDataSetChanged() } }

    private val selection = LinkedHashSet<String>()

    val selectedKeys: List<String> get() = selection.toList()
    val selectedCount: Int get() = selection.size
    val selectionMode: Boolean get() = selection.isNotEmpty()

    fun isSelected(key: String) = key in selection

    fun toggle(key: String) {
        if (!selection.remove(key)) selection.add(key)
        val idx = currentList.indexOfFirst { it.key == key }
        if (idx >= 0) notifyItemChanged(idx)
    }

    fun selectAll() {
        selection.addAll(currentList.map { it.key })
        notifyDataSetChanged()
    }

    fun clearSelection() {
        if (selection.isEmpty()) return
        selection.clear()
        notifyDataSetChanged()
    }

    /** Every track in the selected groups, in row order, without repeats. */
    fun selectedTracks(): List<AudioItem> {
        val seen = LinkedHashMap<Long, AudioItem>()
        for (g in currentList) if (g.key in selection) for (t in g.tracks) seen.putIfAbsent(t.id, t)
        return seen.values.toList()
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<Group>() {
            override fun areItemsTheSame(a: Group, b: Group) = a.key == b.key
            override fun areContentsTheSame(a: Group, b: Group) =
                a.title == b.title && a.subtitle == b.subtitle && a.tracks.size == b.tracks.size
        }
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val art: ImageView = view.findViewById(R.id.art)
        val icon: ImageView = view.findViewById(R.id.icon)
        val title: TextView = view.findViewById(R.id.title)
        val subtitle: TextView = view.findViewById(R.id.subtitle)
        val menu: View = view.findViewById(R.id.rowMenu)
        var job: Job? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_group, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val group = getItem(position)
        holder.title.text = group.title
        holder.subtitle.text = group.subtitle
        holder.itemView.isActivated = isSelected(group.key)
        holder.itemView.setOnClickListener { onClick(group) }
        holder.itemView.setOnLongClickListener { onLongClick(group); true }

        holder.menu.visibility = if (rowMenus) View.VISIBLE else View.GONE
        holder.menu.setOnClickListener { onMenu(group, it) }

        holder.icon.setImageResource(iconRes)
        holder.job?.cancel()
        holder.art.visibility = View.GONE
        holder.icon.visibility = View.VISIBLE

        val artTrack = group.art
        if (artTrack != null) {
            val cached = ArtLoader.cached(artTrack)
            if (cached != null) {
                holder.art.setImageBitmap(cached)
                holder.art.visibility = View.VISIBLE
                holder.icon.visibility = View.GONE
            } else {
                holder.job = scope.launch {
                    val bmp = ArtLoader.load(holder.itemView.context, artTrack, 160)
                    if (bmp != null && holder.bindingAdapterPosition == position) {
                        holder.art.setImageBitmap(bmp)
                        holder.art.visibility = View.VISIBLE
                        holder.icon.visibility = View.GONE
                    }
                }
            }
        }
    }

    override fun onViewRecycled(holder: VH) {
        holder.job?.cancel(); holder.job = null
    }
}

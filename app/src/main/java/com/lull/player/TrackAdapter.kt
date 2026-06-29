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
import java.util.concurrent.TimeUnit

class TrackAdapter(
    private val scope: CoroutineScope,
    private val onClick: (AudioItem, Int) -> Unit
) : ListAdapter<AudioItem, TrackAdapter.VH>(DIFF) {

    var nowPlayingId: Long = -1
        set(value) { field = value; notifyDataSetChanged() }

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
        val equalizer: ImageView = view.findViewById(R.id.playingMark)
        val title: TextView = view.findViewById(R.id.title)
        val subtitle: TextView = view.findViewById(R.id.subtitle)
        val duration: TextView = view.findViewById(R.id.duration)
        var job: Job? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_track, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.title.text = item.title
        holder.subtitle.text = item.artist.ifBlank { holder.itemView.context.getString(R.string.unknown_artist) }
        holder.duration.text = formatDuration(item.durationMs)
        holder.equalizer.visibility = if (item.id == nowPlayingId) View.VISIBLE else View.GONE
        holder.itemView.setOnClickListener { onClick(item, holder.bindingAdapterPosition) }

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

    override fun onViewRecycled(holder: VH) {
        holder.job?.cancel(); holder.job = null
    }
}

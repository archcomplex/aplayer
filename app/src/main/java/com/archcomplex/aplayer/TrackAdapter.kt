package com.archcomplex.aplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class TrackAdapter(private val onClick: (Track) -> Unit) : ListAdapter<Track, TrackAdapter.Holder>(TrackDiffCallback()) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_track, parent, false)
    )
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))
    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val title = view.findViewById<TextView>(R.id.track_title)
        private val details = view.findViewById<TextView>(R.id.track_details)
        fun bind(track: Track) {
            title.text = track.title
            details.text = "${track.artist} • ${track.album}"
            itemView.setOnClickListener { onClick(track) }
        }
    }
}

private class TrackDiffCallback : androidx.recyclerview.widget.DiffUtil.ItemCallback<Track>() {
    override fun areItemsTheSame(oldItem: Track, newItem: Track) = oldItem.id == newItem.id
    override fun areContentsTheSame(oldItem: Track, newItem: Track) = oldItem == newItem
}

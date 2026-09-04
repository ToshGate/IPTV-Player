package com.tosh.iptvplayer.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.tosh.iptvplayer.data.SourceRepository
import com.tosh.iptvplayer.databinding.ItemChannelBinding
import com.tosh.iptvplayer.model.Channel

class ChannelAdapter(
    private val repository: SourceRepository,
    private val onClick: (Channel) -> Unit,
    private val favoriteNamesProvider: () -> Set<String> = { emptySet() },
    private val onToggleFavorite: ((String) -> Unit)? = null,
    // Stable channel number (e.g. matching its position in the full, unfiltered list) — NOT the
    // row's position in whatever list is currently bound, which used to be used directly and
    // renumbered every channel from 1 whenever a search filtered the list down.
    private val channelNumberProvider: (Channel) -> Int = { 0 }
) : ListAdapter<Channel, ChannelAdapter.VH>(DIFF) {

    inner class VH(val binding: ItemChannelBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val channel = getItem(position)
        val b = holder.binding
        b.channelNumber.text = channelNumberProvider(channel).toString()
        b.channelName.text = channel.name
        b.channelGroup.text = channel.groupTitle ?: ""
        b.channelGroup.visibility = if (channel.groupTitle.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
        b.channelLogo.load(channel.logoUrl) {
            crossfade(true)
        }

        val now = repository.currentProgramme(channel.tvgId)
        if (now != null) {
            b.channelEpg.text = now.title
            b.channelEpgDot.visibility = android.view.View.VISIBLE
        } else {
            b.channelEpg.text = "EPG indisponível"
            b.channelEpgDot.visibility = android.view.View.GONE
        }

        if (onToggleFavorite != null) {
            val baseName = com.tosh.iptvplayer.data.ChannelGrouping.baseName(channel.name)
            b.channelFavorite.visibility = android.view.View.VISIBLE
            val isFavorite = favoriteNamesProvider().contains(baseName)
            val context = b.channelFavorite.context
            if (isFavorite) {
                b.channelFavorite.setImageResource(com.tosh.iptvplayer.R.drawable.ic_star_filled)
                b.channelFavorite.imageTintList = android.content.res.ColorStateList.valueOf(
                    context.getColor(com.tosh.iptvplayer.R.color.accent)
                )
            } else {
                b.channelFavorite.setImageResource(com.tosh.iptvplayer.R.drawable.ic_star_outline)
                b.channelFavorite.imageTintList = android.content.res.ColorStateList.valueOf(
                    context.getColor(com.tosh.iptvplayer.R.color.on_surface_muted)
                )
            }
            b.channelFavorite.setOnClickListener { onToggleFavorite.invoke(baseName) }
        } else {
            b.channelFavorite.visibility = android.view.View.GONE
        }

        b.root.setOnClickListener { onClick(channel) }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Channel>() {
            override fun areItemsTheSame(oldItem: Channel, newItem: Channel) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: Channel, newItem: Channel) = oldItem == newItem
        }
    }
}

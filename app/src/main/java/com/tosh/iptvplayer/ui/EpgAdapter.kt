package com.tosh.iptvplayer.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.tosh.iptvplayer.databinding.ItemEpgProgramBinding
import com.tosh.iptvplayer.model.EpgProgramme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EpgAdapter(private val items: List<EpgProgramme>) : RecyclerView.Adapter<EpgAdapter.VH>() {

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    inner class VH(val binding: ItemEpgProgramBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemEpgProgramBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val programme = items[position]
        val now = System.currentTimeMillis()
        val isNow = now in programme.startMillis until programme.stopMillis
        val b = holder.binding
        b.programTitle.text = programme.title
        b.programTime.text = timeFormat.format(Date(programme.startMillis))
        b.programEndTime.text = timeFormat.format(Date(programme.stopMillis))
        b.nowLabel.visibility = if (isNow) android.view.View.VISIBLE else android.view.View.GONE
        b.epgCard.setBackgroundResource(
            if (isNow) com.tosh.iptvplayer.R.drawable.bg_card_now
            else com.tosh.iptvplayer.R.drawable.bg_card
        )

        val durationMillis = programme.stopMillis - programme.startMillis
        val durationMinutesTotal = durationMillis / (1000 * 60)
        val hours = durationMinutesTotal / 60
        val minutes = durationMinutesTotal % 60

        val durationText = when {
            hours > 0 && minutes > 0 -> "${hours}h ${minutes} min"
            hours > 0 -> "${hours}h"
            else -> "${minutes} min"
        }
        b.programDuration.text = durationText
    }

    override fun getItemCount() = items.size
}

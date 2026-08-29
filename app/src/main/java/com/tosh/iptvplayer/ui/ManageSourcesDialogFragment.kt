package com.tosh.iptvplayer.ui

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import com.tosh.iptvplayer.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tosh.iptvplayer.IptvApplication
import com.tosh.iptvplayer.databinding.DialogManageSourcesBinding
import com.tosh.iptvplayer.databinding.ItemSourceBinding
import com.tosh.iptvplayer.model.PlaylistSource
import kotlinx.coroutines.launch

/**
 * Lists every configured playlist source and lets the user remove one (along with its channels).
 */
class ManageSourcesDialogFragment : AppCompatDialogFragment() {

    private var _binding: DialogManageSourcesBinding? = null
    private val binding get() = _binding!!
    private val repository by lazy { (requireActivity().application as IptvApplication).repository }
    private lateinit var adapter: SourcesAdapter

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogManageSourcesBinding.inflate(LayoutInflater.from(requireContext()))

        adapter = SourcesAdapter { source -> confirmRemoval(source) }
        binding.sourcesList.layoutManager = LinearLayoutManager(requireContext())
        binding.sourcesList.adapter = adapter

        lifecycleScope.launch {
            repository.observeSources().collect { sources ->
                adapter.submitList(sources)
                binding.noSourcesLabel.visibility =
                    if (sources.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
                binding.sourcesList.visibility =
                    if (sources.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
            }
        }

        return MaterialAlertDialogBuilder(requireContext(), R.style.CustomDarkDialog)
            .setTitle("Fontes adicionadas")
            .setView(binding.root)
            .setPositiveButton("Fechar", null)
            .create()
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun confirmRemoval(source: PlaylistSource) {
        MaterialAlertDialogBuilder(requireContext(), R.style.CustomDarkDialog)
            .setTitle("Remover fonte")
            .setMessage("Remover \"${source.name}\" e todos os seus canais?")
            .setPositiveButton("Remover") { _, _ ->
                lifecycleScope.launch { repository.removeSource(source) }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

private class SourcesAdapter(
    private val onDelete: (PlaylistSource) -> Unit
) : RecyclerView.Adapter<SourcesAdapter.VH>() {

    private var items: List<PlaylistSource> = emptyList()

    fun submitList(newItems: List<PlaylistSource>) {
        items = newItems
        notifyDataSetChanged()
    }

    inner class VH(val binding: ItemSourceBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemSourceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val source = items[position]
        val hasEpg = !source.epgLocation.isNullOrBlank()
        holder.binding.sourceName.text = source.name
        holder.binding.sourceTypeChip.text = sourceTypeLabel(source)
        holder.binding.sourceEpgStatus.text = if (hasEpg) "EPG sincronizado" else "Sem EPG associado"
        holder.binding.sourceEpgDot.backgroundTintList = android.content.res.ColorStateList.valueOf(
            holder.binding.root.context.getColor(
                if (hasEpg) com.tosh.iptvplayer.R.color.synced_green else com.tosh.iptvplayer.R.color.on_surface_faint
            )
        )
        holder.binding.btnDeleteSource.setOnClickListener { onDelete(source) }
    }

    override fun getItemCount() = items.size

    /**
     * The app doesn't store which of URL/Ficheiro/Xtream a source was added with — this is
     * inferred from the resulting playlist location instead: a picked file is a content:// URI,
     * and an Xtream Codes URL is recognizably built as .../get.php with username/password query
     * parameters (the exact pattern this app constructs when the Xtream form is used).
     */
    private fun sourceTypeLabel(source: PlaylistSource): String {
        if (source.playlistIsFile) return "Ficheiro"
        val looksLikeXtream = runCatching {
            val uri = android.net.Uri.parse(source.playlistLocation)
            uri.path?.contains("get.php", ignoreCase = true) == true &&
                !uri.getQueryParameter("username").isNullOrBlank() &&
                !uri.getQueryParameter("password").isNullOrBlank()
        }.getOrDefault(false)
        return if (looksLikeXtream) "Xtream" else "URL"
    }
}

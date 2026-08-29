package com.tosh.iptvplayer.ui

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.DialogFragment
import com.tosh.iptvplayer.databinding.DialogAddSourceBinding

/**
 * Lets the user configure a new source:
 *  - Playlist: URL or a local .m3u/.m3u8 file
 *  - EPG (optional): XMLTV URL or a local .xml/.xml.gz file
 *
 * Shown full-screen rather than as a small floating AlertDialog window: Android's text-selection
 * "Paste" popup consistently mis-positions itself for EditTexts hosted inside a small floating
 * Dialog window (several narrower fixes for this — theme attributes, explicit width — didn't
 * fully resolve it). A full-screen window behaves exactly like a normal Activity for this, which
 * never had the problem.
 */
class AddSourceDialogFragment : DialogFragment() {

    interface Listener {
        fun onSourceConfigured(
            name: String,
            playlistLocation: String,
            playlistIsFile: Boolean,
            epgLocation: String?,
            epgIsFile: Boolean
        )
    }

    private var _binding: DialogAddSourceBinding? = null
    private val binding get() = _binding!!

    private var pickedPlaylistUri: Uri? = null
    private var pickedEpgUri: Uri? = null

    private val pickPlaylistFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        requireContext().contentResolver.takePersistableUriPermission(
            uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        pickedPlaylistUri = uri
        binding.playlistFileLabel.text = uri.lastPathSegment ?: uri.toString()
        binding.playlistTypeToggle.check(binding.btnPlaylistFile.id)
    }

    private val pickEpgFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        requireContext().contentResolver.takePersistableUriPermission(
            uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        pickedEpgUri = uri
        binding.epgFileLabel.text = uri.lastPathSegment ?: uri.toString()
        binding.epgTypeToggle.check(binding.btnEpgFile.id)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, com.tosh.iptvplayer.R.style.Theme_IptvPlayer)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogAddSourceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        // Make the dialog window fill the whole screen instead of the platform's default
        // "wrap narrow" floating dialog sizing.
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener { dismiss() }
        binding.btnCancel.setOnClickListener { dismiss() }
        binding.btnSubmit.setOnClickListener { submit() }

        binding.btnPickPlaylistFile.setOnClickListener {
            pickPlaylistFile.launch(arrayOf("audio/x-mpegurl", "application/x-mpegurl", "*/*"))
        }
        binding.btnPickEpgFile.setOnClickListener {
            pickEpgFile.launch(arrayOf("text/xml", "application/xml", "application/gzip", "*/*"))
        }

        binding.playlistTypeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val isXtream = checkedId == binding.btnPlaylistXtream.id
            val isFile = checkedId == binding.btnPlaylistFile.id
            binding.xtreamFieldsContainer.visibility = if (isXtream) View.VISIBLE else View.GONE
            binding.urlFileFieldsContainer.visibility = if (isXtream) View.GONE else View.VISIBLE
            // Only show the field matching the selected option — previously both the URL field
            // and the file picker stayed visible together regardless of which was selected,
            // which made it look like either one worked when only the selected one was actually
            // used on submit.
            binding.playlistUrlLayout.visibility = if (isFile) View.GONE else View.VISIBLE
            binding.playlistFileGroup.visibility = if (isFile) View.VISIBLE else View.GONE
            // Xtream derives its own EPG automatically — the manual EPG section would just be
            // redundant/confusing alongside it.
            binding.epgSectionContainer.visibility = if (isXtream) View.GONE else View.VISIBLE
        }

        binding.epgTypeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val isFile = checkedId == binding.btnEpgFile.id
            binding.epgUrlLayout.visibility = if (isFile) View.GONE else View.VISIBLE
            binding.epgFileGroup.visibility = if (isFile) View.VISIBLE else View.GONE
        }
    }

    private fun submit() {
        val listener = (parentFragment as? Listener) ?: (activity as? Listener) ?: return

        val name = binding.sourceName.text?.toString()?.ifBlank { "Nova fonte" } ?: "Nova fonte"

        if (binding.playlistTypeToggle.checkedButtonId == binding.btnPlaylistXtream.id) {
            val rawHost = binding.xtreamHost.text?.toString()?.trim().orEmpty()
            val username = binding.xtreamUsername.text?.toString()?.trim().orEmpty()
            val password = binding.xtreamPassword.text?.toString()?.trim().orEmpty()
            if (rawHost.isBlank() || username.isBlank() || password.isBlank()) return

            // Accept the host with or without a scheme (people often paste just "servidor.com:8080").
            val baseUrl = if (rawHost.startsWith("http://") || rawHost.startsWith("https://")) {
                rawHost.trimEnd('/')
            } else {
                "http://${rawHost.trimEnd('/')}"
            }
            val playlistLocation = "$baseUrl/get.php?username=$username&password=$password&type=m3u_plus&output=ts"
            val epgLocation = "$baseUrl/xmltv.php?username=$username&password=$password"

            listener.onSourceConfigured(name, playlistLocation, false, epgLocation, false)
            dismiss()
            return
        }

        val playlistIsFile = binding.playlistTypeToggle.checkedButtonId == binding.btnPlaylistFile.id
        val playlistLocation = if (playlistIsFile) {
            pickedPlaylistUri?.toString() ?: return
        } else {
            binding.playlistUrl.text?.toString()?.trim().orEmpty().ifBlank { return }
        }

        val epgIsFile = binding.epgTypeToggle.checkedButtonId == binding.btnEpgFile.id
        val epgLocation: String? = if (epgIsFile) {
            pickedEpgUri?.toString()
        } else {
            binding.epgUrl.text?.toString()?.trim()?.ifBlank { null }
        }

        listener.onSourceConfigured(name, playlistLocation, playlistIsFile, epgLocation, epgIsFile)
        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

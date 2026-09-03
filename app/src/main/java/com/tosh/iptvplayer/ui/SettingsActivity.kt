package com.tosh.iptvplayer.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tosh.iptvplayer.IptvApplication
import com.tosh.iptvplayer.R
import com.tosh.iptvplayer.databinding.ActivitySettingsBinding
import com.tosh.iptvplayer.model.BufferMode
import com.tosh.iptvplayer.model.DefaultScreen
import com.tosh.iptvplayer.model.ThemeMode
import androidx.appcompat.app.AppCompatDelegate
import com.tosh.iptvplayer.model.EpgSyncFrequency
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : AppCompatActivity(), AddSourceDialogFragment.Listener {

    private lateinit var binding: ActivitySettingsBinding
    private val repository by lazy { (application as IptvApplication).repository }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnAddSource.setOnClickListener {
            AddSourceDialogFragment().show(supportFragmentManager, "add_source")
        }

        binding.btnManageSources.setOnClickListener {
            ManageSourcesDialogFragment().show(supportFragmentManager, "manage_sources")
        }

        updateEpgSyncSubtitle()
        updateLastSyncSubtitle()

        binding.btnEpgSync.setOnClickListener {
            showEpgSyncDialog()
        }

        binding.btnEpgSyncNow.setOnClickListener {
            syncEpgNow()
        }

        updateDefaultScreenSubtitle()
        binding.btnDefaultScreen.setOnClickListener {
            showDefaultScreenDialog()
        }

        updateThemeModeSubtitle()
        binding.btnThemeMode.setOnClickListener {
            showThemeModeDialog()
        }

        updateBufferModeSubtitle()
        binding.btnBufferMode.setOnClickListener {
            showBufferModeDialog()
        }

        binding.appVersionSubtitle.text = "Versão ${com.tosh.iptvplayer.BuildConfig.VERSION_NAME}"
        binding.btnCheckUpdate.setOnClickListener {
            checkForUpdateManually()
        }
    }

    private fun checkForUpdateManually() {
        binding.appVersionSubtitle.text = "A verificar…"
        lifecycleScope.launch {
            val updateChecker = com.tosh.iptvplayer.data.UpdateChecker(this@SettingsActivity)
            val update = runCatching { updateChecker.checkForUpdate() }.getOrNull()
            binding.appVersionSubtitle.text = "Versão ${com.tosh.iptvplayer.BuildConfig.VERSION_NAME}"
            if (update != null) {
                showUpdateDialog(updateChecker, update)
            } else {
                Toast.makeText(this@SettingsActivity, "Já tens a versão mais recente", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateDefaultScreenSubtitle() {
        binding.defaultScreenSubtitle.text = repository.getDefaultScreen().label
    }

    private fun showDefaultScreenDialog() {
        val options = DefaultScreen.values()
        val labels = options.map { it.label }.toTypedArray()
        val current = repository.getDefaultScreen()
        val selectedIndex = options.indexOf(current).coerceAtLeast(0)

        MaterialAlertDialogBuilder(this, R.style.CustomDarkDialog)
            .setTitle("Ecrã inicial")
            .setSingleChoiceItems(labels, selectedIndex) { dialog, which ->
                repository.setDefaultScreen(options[which])
                updateDefaultScreenSubtitle()
                dialog.dismiss()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun updateThemeModeSubtitle() {
        binding.themeModeSubtitle.text = repository.getThemeMode().label
    }

    private fun showThemeModeDialog() {
        val options = ThemeMode.values()
        val labels = options.map { it.label }.toTypedArray()
        val current = repository.getThemeMode()
        val selectedIndex = options.indexOf(current).coerceAtLeast(0)

        MaterialAlertDialogBuilder(this, R.style.CustomDarkDialog)
            .setTitle("Tema")
            .setSingleChoiceItems(labels, selectedIndex) { dialog, which ->
                val selected = options[which]
                repository.setThemeMode(selected)
                updateThemeModeSubtitle()
                dialog.dismiss()
                val nightMode = when (selected) {
                    ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                    ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
                    ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
                // Applies immediately and recreates this (and any other visible) Activity so
                // the new theme is visible right away instead of only after a manual restart.
                AppCompatDelegate.setDefaultNightMode(nightMode)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun updateBufferModeSubtitle() {
        val mode = repository.getBufferMode()
        binding.bufferModeSubtitle.text = if (mode == BufferMode.CUSTOM) {
            "Personalizado (${repository.getCustomBufferSeconds()}s)"
        } else {
            mode.label
        }
    }

    private fun showBufferModeDialog() {
        val options = BufferMode.values()
        val labels = options.map { it.label }.toTypedArray()
        val current = repository.getBufferMode()
        val selectedIndex = options.indexOf(current).coerceAtLeast(0)

        MaterialAlertDialogBuilder(this, R.style.CustomDarkDialog)
            .setTitle("Buffer de reprodução")
            .setSingleChoiceItems(labels, selectedIndex) { dialog, which ->
                dialog.dismiss()
                val selected = options[which]
                if (selected == BufferMode.CUSTOM) {
                    showCustomBufferInputDialog()
                } else {
                    repository.setBufferMode(selected)
                    updateBufferModeSubtitle()
                    Toast.makeText(this, "Aplica-se ao próximo canal que abrires", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showCustomBufferInputDialog() {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(repository.getCustomBufferSeconds().toString())
            setSelection(text.length)
        }
        val padding = (20 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }

        MaterialAlertDialogBuilder(this, R.style.CustomDarkDialog)
            .setTitle("Buffer personalizado")
            .setMessage("Quantos segundos de stream queres manter em buffer (e, consequentemente, de atraso em relação ao direto)? Entre 1 e 300 segundos.")
            .setView(container)
            .setPositiveButton("Guardar") { _, _ ->
                val seconds = input.text.toString().toIntOrNull()
                if (seconds == null || seconds < 1) {
                    Toast.makeText(this, "Introduz um número válido de segundos", Toast.LENGTH_SHORT).show()
                } else {
                    repository.setCustomBufferSeconds(seconds)
                    repository.setBufferMode(BufferMode.CUSTOM)
                    updateBufferModeSubtitle()
                    Toast.makeText(this, "Aplica-se ao próximo canal que abrires", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun updateEpgSyncSubtitle() {
        binding.epgSyncSubtitle.text = repository.getEpgSyncFrequency().label
    }

    private fun updateLastSyncSubtitle() {
        val lastSync = repository.getLastEpgSyncMillis()
        binding.epgLastSyncSubtitle.text = if (lastSync == null) {
            "Ainda não sincronizado"
        } else {
            val format = SimpleDateFormat("dd/MM/yyyy 'às' HH:mm", Locale.getDefault())
            "Última sincronização: ${format.format(Date(lastSync))}"
        }
    }

    private fun showEpgSyncDialog() {
        val options = EpgSyncFrequency.values()
        val labels = options.map { it.label }.toTypedArray()
        val current = repository.getEpgSyncFrequency()
        val selectedIndex = options.indexOf(current).coerceAtLeast(0)

        MaterialAlertDialogBuilder(this, R.style.CustomDarkDialog)
            .setTitle("Sincronização do EPG")
            .setSingleChoiceItems(labels, selectedIndex) { dialog, which ->
                repository.setEpgSyncFrequency(options[which])
                updateEpgSyncSubtitle()
                dialog.dismiss()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun syncEpgNow() {
        binding.epgLastSyncSubtitle.text = "A sincronizar…"
        binding.btnEpgSyncNow.isEnabled = false
        lifecycleScope.launch {
            val result = runCatching { repository.refreshAllEpg(force = true) }
            binding.btnEpgSyncNow.isEnabled = true
            updateLastSyncSubtitle()
            result.onFailure {
                Toast.makeText(this@SettingsActivity, "Erro ao sincronizar: ${it.message}", Toast.LENGTH_LONG).show()
            }.onSuccess {
                Toast.makeText(this@SettingsActivity, "EPG sincronizado", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onSourceConfigured(
        name: String,
        playlistLocation: String,
        playlistIsFile: Boolean,
        epgLocation: String?,
        epgIsFile: Boolean
    ) {
        lifecycleScope.launch {
            Toast.makeText(this@SettingsActivity, "A carregar fonte…", Toast.LENGTH_SHORT).show()
            val result = runCatching {
                repository.addSource(name, playlistLocation, playlistIsFile, epgLocation, epgIsFile)
            }
            result.onSuccess {
                Toast.makeText(this@SettingsActivity, "Fonte adicionada", Toast.LENGTH_SHORT).show()
            }.onFailure { e ->
                Toast.makeText(this@SettingsActivity, "Erro: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}

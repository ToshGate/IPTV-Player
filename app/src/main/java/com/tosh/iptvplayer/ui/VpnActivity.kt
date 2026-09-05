package com.tosh.iptvplayer.ui

import android.app.Activity
import android.net.VpnService
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tosh.iptvplayer.IptvApplication
import com.tosh.iptvplayer.R
import com.tosh.iptvplayer.databinding.ActivityVpnBinding
import com.tosh.iptvplayer.model.VpnProfile
import com.wireguard.android.backend.Tunnel
import kotlinx.coroutines.launch

class VpnActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVpnBinding
    private val vpnRepository by lazy { (application as IptvApplication).vpnRepository }

    private val pickConfigFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        runCatching {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: throw IllegalStateException("Não foi possível ler o ficheiro")
            val name = queryDisplayName(uri) ?: "Perfil ${vpnRepository.getProfiles().size + 1}"
            vpnRepository.addProfile(name, text)
        }.onSuccess {
            refreshUi()
            Toast.makeText(this, "Perfil importado", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, "Ficheiro de configuração inválido", Toast.LENGTH_LONG).show()
        }
    }

    // The one-time system dialog asking the person to trust this app as a VPN — only needs to be
    // shown once per app install, but VpnService.prepare() must still be called every time to
    // check, since it can also return non-null again if another VPN app took over in between.
    private val vpnPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            doConnect()
        } else {
            Toast.makeText(this, "Permissão de VPN recusada", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVpnBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnImportConfig.setOnClickListener {
            pickConfigFile.launch(arrayOf("*/*"))
        }

        binding.btnToggleConnection.setOnClickListener {
            if (vpnRepository.currentState() == Tunnel.State.UP) {
                doDisconnect()
            } else {
                requestConnect()
            }
        }

        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        updateConnectionStatus(vpnRepository.currentState())
    }

    private fun requestConnect() {
        val consentIntent = VpnService.prepare(this)
        if (consentIntent != null) {
            vpnPermissionLauncher.launch(consentIntent)
        } else {
            doConnect()
        }
    }

    private fun doConnect() {
        binding.btnToggleConnection.isEnabled = false
        lifecycleScope.launch {
            val result = vpnRepository.connect()
            result.onSuccess { state ->
                updateConnectionStatus(state)
            }.onFailure {
                Toast.makeText(this@VpnActivity, "Falha ao ligar: ${it.message}", Toast.LENGTH_LONG).show()
                updateConnectionStatus(vpnRepository.currentState())
            }
        }
    }

    private fun doDisconnect() {
        binding.btnToggleConnection.isEnabled = false
        lifecycleScope.launch {
            val result = vpnRepository.disconnect()
            result.onSuccess { state ->
                updateConnectionStatus(state)
            }.onFailure {
                Toast.makeText(this@VpnActivity, "Falha ao desligar: ${it.message}", Toast.LENGTH_LONG).show()
                updateConnectionStatus(vpnRepository.currentState())
            }
        }
    }

    private fun refreshUi() {
        rebuildProfilesList()
        updateConnectionStatus(vpnRepository.currentState())
    }

    private fun rebuildProfilesList() {
        val container = binding.profilesContainer
        container.removeAllViews()
        val profiles = vpnRepository.getProfiles()
        val selectedId = vpnRepository.getSelectedProfileId()

        binding.profilesDivider.visibility = if (profiles.isEmpty()) View.GONE else View.VISIBLE

        profiles.forEach { profile ->
            container.addView(buildProfileRow(profile, profile.id == selectedId))
        }
    }

    private fun buildProfileRow(profile: VpnProfile, isSelected: Boolean): View {
        val density = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())
            isClickable = true
            isFocusable = true
            setBackgroundResource(R.drawable.bg_settings_row_ripple)
            setOnClickListener {
                vpnRepository.setSelectedProfileId(profile.id)
                refreshUi()
            }
        }

        val indicator = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams((22 * density).toInt(), (22 * density).toInt())
            setBackgroundResource(if (isSelected) R.drawable.bg_control_circle else R.drawable.bg_settings_icon_bubble)
        }
        if (isSelected) {
            indicator.addView(ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams((12 * density).toInt(), (12 * density).toInt(), android.view.Gravity.CENTER)
                setImageResource(R.drawable.ic_lock)
                setColorFilter(getColor(R.color.accent))
            })
        }
        row.addView(indicator)

        val name = TextView(this).apply {
            text = profile.name
            textSize = 15f
            setTextColor(getColor(if (isSelected) R.color.accent else R.color.on_surface))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (14 * density).toInt()
            }
        }
        row.addView(name)

        val deleteButton = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams((22 * density).toInt(), (22 * density).toInt())
            setImageResource(R.drawable.ic_delete)
            setColorFilter(getColor(R.color.on_surface_muted))
            isClickable = true
            isFocusable = true
            setBackgroundResource(R.drawable.bg_settings_row_ripple)
            setOnClickListener {
                val wasConnectedToThis = profile.id == vpnRepository.getSelectedProfileId() &&
                    vpnRepository.currentState() == Tunnel.State.UP
                if (wasConnectedToThis) doDisconnect()
                vpnRepository.deleteProfile(profile.id)
                refreshUi()
            }
        }
        row.addView(deleteButton)

        return row
    }

    private fun updateConnectionStatus(state: Tunnel.State) {
        val isUp = state == Tunnel.State.UP
        val hasSelection = vpnRepository.getSelectedProfileId() != null
        binding.connectionStatusLabel.text = when {
            isUp -> "Ligado — ${vpnRepository.getSelectedProfile()?.name.orEmpty()}"
            hasSelection -> "Desligado"
            else -> "Sem perfil selecionado"
        }
        binding.connectionIcon.setImageResource(if (isUp) R.drawable.ic_lock else R.drawable.ic_lock_open)
        binding.btnToggleConnection.text = if (isUp) "Desligar" else "Ligar"
        binding.btnToggleConnection.isEnabled = hasSelection
    }

    private fun queryDisplayName(uri: android.net.Uri): String? {
        return runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
            }
        }.getOrNull()
    }
}

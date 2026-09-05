package com.tosh.iptvplayer.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.tosh.iptvplayer.IptvApplication
import com.tosh.iptvplayer.R
import com.tosh.iptvplayer.data.ChannelGrouping
import com.tosh.iptvplayer.databinding.ActivityFavoritesBinding
import com.tosh.iptvplayer.model.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class FavoritesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFavoritesBinding
    private val repository by lazy { (application as IptvApplication).repository }
    private val vpnRepository by lazy { (application as IptvApplication).vpnRepository }
    private lateinit var adapter: ChannelAdapter
    private var optionsMenu: Menu? = null

    private val vpnPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            performVpnConnect()
        } else {
            android.widget.Toast.makeText(this, "Permissão de VPN recusada", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun performVpnConnect() {
        lifecycleScope.launch {
            vpnRepository.connect().onSuccess {
                updateVpnMenuIcon()
            }.onFailure {
                android.widget.Toast.makeText(this@FavoritesActivity, "Falha ao ligar VPN: ${it.message}", android.widget.Toast.LENGTH_LONG).show()
                updateVpnMenuIcon()
            }
        }
    }

    private fun performVpnDisconnect() {
        lifecycleScope.launch {
            vpnRepository.disconnect()
            updateVpnMenuIcon()
        }
    }

    private fun updateVpnMenuIcon() {
        val isUp = vpnRepository.currentState() == com.wireguard.android.backend.Tunnel.State.UP
        optionsMenu?.findItem(R.id.action_vpn)?.setIcon(
            if (isUp) R.drawable.ic_vpn_on else R.drawable.ic_vpn_off
        )
    }

    private var allFavoriteChannels: List<Channel> = emptyList()
    // Same stable numbering as the main list — computed from the FULL channel list (all
    // channels, not just favorites), so a channel shows the same number here as it does there.
    private var baseNameToNumber: Map<String, Int> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFavoritesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        binding.swipeRefresh.setColorSchemeResources(R.color.accent)
        binding.swipeRefresh.setProgressBackgroundColorSchemeResource(R.color.surface_variant)
        binding.channelList.overScrollMode = android.view.View.OVER_SCROLL_IF_CONTENT_SCROLLS

        adapter = ChannelAdapter(
            repository,
            onClick = { channel -> openPlayer(channel) },
            favoriteNamesProvider = { allFavoriteChannels.map { ChannelGrouping.baseName(it.name) }.toSet() },
            onToggleFavorite = { baseName ->
                lifecycleScope.launch { repository.toggleFavorite(baseName) }
            },
            channelNumberProvider = { channel ->
                baseNameToNumber[ChannelGrouping.baseName(channel.name)] ?: 0
            }
        )
        binding.channelList.layoutManager = LinearLayoutManager(this)
        binding.channelList.adapter = adapter

        binding.searchInput.doOnTextChanged { query -> applyFilter(query) }

        binding.swipeRefresh.setOnRefreshListener {
            lifecycleScope.launch {
                runCatching { repository.refreshAllChannels() }
                runCatching { repository.refreshAllEpg() }
                binding.swipeRefresh.isRefreshing = false
                adapter.notifyDataSetChanged()
            }
        }

        lifecycleScope.launch {
            repository.observeFavoriteChannels().collect { channels ->
                allFavoriteChannels = channels
                applyFilter(binding.searchInput.text?.toString().orEmpty())
                binding.emptyState.visibility =
                    if (channels.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            }
        }

        lifecycleScope.launch {
            repository.observeChannels().collect { channels ->
                baseNameToNumber = channels
                    .groupBy { ChannelGrouping.baseName(it.name) }
                    .keys.withIndex().associate { (i, baseName) -> baseName to i + 1 }
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun applyFilter(query: String) {
        val filtered = if (query.isBlank()) {
            allFavoriteChannels
        } else {
            allFavoriteChannels.filter { it.name.contains(query, ignoreCase = true) }
        }
        adapter.submitList(filtered)
    }

    override fun onResume() {
        super.onResume()
        binding.searchInput.clearFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
        updateVpnMenuIcon()
    }

    private fun openPlayer(channel: Channel) {
        // Favorites only stores/shows one representative row per channel; re-derive its quality
        // siblings from the full channel set (via the shared source flow) so the player's
        // quality selector still offers every variant, exactly like the main list does.
        lifecycleScope.launch {
            val baseName = ChannelGrouping.baseName(channel.name)
            val allChannels = repository.observeChannels().first()
            val qualities = allChannels.filter { ChannelGrouping.baseName(it.name) == baseName }
                .ifEmpty { listOf(channel) }

            val bestTvgId = channel.tvgId?.takeIf { it.isNotBlank() }
                ?: qualities.find { !it.tvgId.isNullOrBlank() }?.tvgId

            val intent = Intent(this@FavoritesActivity, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, baseName)
                putExtra(PlayerActivity.EXTRA_STREAM_URL, channel.streamUrl)
                putExtra(PlayerActivity.EXTRA_TVG_ID, bestTvgId)
                putStringArrayListExtra(PlayerActivity.EXTRA_QUALITY_NAMES, ArrayList(qualities.map { it.name }))
                putStringArrayListExtra(PlayerActivity.EXTRA_QUALITY_URLS, ArrayList(qualities.map { it.streamUrl }))
                putStringArrayListExtra(PlayerActivity.EXTRA_QUALITY_IDS, ArrayList(qualities.map { it.tvgId ?: "" }))
            }
            startActivity(intent)
        }
    }

    /** Always lands on an existing/fresh MainActivity showing the full channel list — never
     * bounces back here, regardless of what "Ecrã inicial" is set to. */
    private fun goToMainScreen() {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_SKIP_DEFAULT_SCREEN_REDIRECT, true)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.favorites_menu, menu)
        optionsMenu = menu
        updateVpnMenuIcon()
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_vpn -> {
                if (vpnRepository.getSelectedProfileId() == null) {
                    android.widget.Toast.makeText(
                        this, "Configura um perfil de VPN primeiro", android.widget.Toast.LENGTH_SHORT
                    ).show()
                    startActivity(Intent(this, VpnActivity::class.java))
                } else if (vpnRepository.currentState() == com.wireguard.android.backend.Tunnel.State.UP) {
                    performVpnDisconnect()
                } else {
                    val consentIntent = android.net.VpnService.prepare(this)
                    if (consentIntent != null) {
                        vpnPermissionLauncher.launch(consentIntent)
                    } else {
                        performVpnConnect()
                    }
                }
                return true
            }
            R.id.action_all_channels -> {
                goToMainScreen()
                return true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }
}

/** Small helper: TextWatcher as a trailing lambda without extra dependencies. */
private inline fun android.widget.EditText.doOnTextChanged(crossinline action: (String) -> Unit) {
    addTextChangedListener(object : android.text.TextWatcher {
        override fun afterTextChanged(s: android.text.Editable?) = action(s?.toString().orEmpty())
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    })
}

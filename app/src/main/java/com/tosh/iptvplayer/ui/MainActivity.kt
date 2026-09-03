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
import com.tosh.iptvplayer.databinding.ActivityMainBinding
import com.tosh.iptvplayer.model.Channel
import com.tosh.iptvplayer.model.DefaultScreen
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val repository by lazy { (application as IptvApplication).repository }
    private lateinit var adapter: ChannelAdapter

    private var allChannels: List<Channel> = emptyList()
    private var groupedChannels: Map<String, List<Channel>> = emptyMap()
    private var favoriteNames: Set<String> = emptySet()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Only redirect on a genuine cold app launch, never when explicitly navigated to from
        // within the app (e.g. tapping "Todos os canais" from Favorites) — otherwise that would
        // just bounce straight back to Favorites. isTaskRoot() alone isn't reliable enough for
        // this: it can still report true for a brief moment right after the previous screen
        // calls finish(), depending on exactly when this runs relative to that — which caused
        // exactly that bounce-back loop. An explicit "don't redirect" extra sidesteps the race.
        val skipDefaultScreenRedirect = intent.getBooleanExtra(EXTRA_SKIP_DEFAULT_SCREEN_REDIRECT, false)
        if (!skipDefaultScreenRedirect && isTaskRoot && repository.getDefaultScreen() == DefaultScreen.FAVORITES) {
            startActivity(Intent(this, FavoritesActivity::class.java))
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.elevation = 0f

        // A white flash used to appear over the search bar when the list was overscrolled at the
        // top: SwipeRefreshLayout's pull-to-refresh indicator defaults to a white background,
        // which stood out sharply against the dark theme. Match it to the app's palette instead.
        binding.swipeRefresh.setColorSchemeResources(R.color.accent)
        binding.swipeRefresh.setProgressBackgroundColorSchemeResource(R.color.surface_variant)
        binding.channelList.overScrollMode = android.view.View.OVER_SCROLL_IF_CONTENT_SCROLLS

        adapter = ChannelAdapter(
            repository,
            onClick = { channel -> openPlayer(channel) },
            favoriteNamesProvider = { favoriteNames },
            onToggleFavorite = { baseName ->
                lifecycleScope.launch { repository.toggleFavorite(baseName) }
            }
        )
        binding.channelList.layoutManager = LinearLayoutManager(this)
        binding.channelList.adapter = adapter

        binding.searchInput.doOnTextChanged { query ->
            applyFilter(query)
        }

        binding.swipeRefresh.setOnRefreshListener {
            lifecycleScope.launch {
                // Deliberately NOT force = true: pull-to-refresh should still respect the EPG
                // sync interval chosen in Definições. Forcing a sync from every app screen would
                // bypass the whole point of that setting. A manual, always-force sync is
                // available from Definições instead.
                runCatching { repository.refreshAllEpg() }
                binding.swipeRefresh.isRefreshing = false
                applyFilter(binding.searchInput.text?.toString().orEmpty())
                adapter.notifyDataSetChanged()
            }
        }

        lifecycleScope.launch {
            repository.observeChannels().collect { channels ->
                allChannels = channels
                groupedChannels = channels.groupBy { ChannelGrouping.baseName(it.name) }
                applyFilter(binding.searchInput.text?.toString().orEmpty())
                binding.emptyState.visibility =
                    if (channels.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            }
        }

        lifecycleScope.launch {
            repository.observeFavoriteNames().collect { names ->
                favoriteNames = names
                // Same DiffUtil blind spot as EPG data: favorite state isn't part of the Channel
                // model, so rows won't rebind on their own when it changes.
                adapter.notifyDataSetChanged()
            }
        }

        lifecycleScope.launch {
            runCatching { 
                repository.refreshAllEpg()
                // Re-apply filter after EPG load to pick the best representatives
                applyFilter(binding.searchInput.text?.toString().orEmpty())
                // ListAdapter/DiffUtil only rebinds rows whose Channel data actually changed;
                // the EPG "now playing" text comes from a separate cache the adapter reads at
                // bind time, so without this the rows never redraw until something else (like a
                // search) happens to change the list's shape and force a rebind anyway.
                adapter.notifyDataSetChanged()
            }
        }

        // Silent update check: only ever surfaces a dialog if a newer release genuinely exists —
        // no dialog, no toast, nothing at all if the check fails (no network) or is up to date.
        // Guarded to once per app process, since MainActivity can be recreated multiple times in
        // a session (e.g. navigating back from Favoritos) — without this it could re-prompt every time.
        if (!hasCheckedForUpdateThisSession) {
            hasCheckedForUpdateThisSession = true
            lifecycleScope.launch {
                runCatching {
                    val updateChecker = com.tosh.iptvplayer.data.UpdateChecker(this@MainActivity)
                    updateChecker.checkForUpdate()?.let { update ->
                        showUpdateDialog(updateChecker, update)
                    }
                }
            }
        }
    }

    private fun applyFilter(query: String) {
        val filteredGroups = if (query.isBlank()) {
            groupedChannels
        } else {
            groupedChannels.filter { (baseName, _) -> 
                baseName.contains(query, ignoreCase = true) 
            }
        }
        
        // Show the best representative of each group: prefer one that has EPG data available
        val displayList = filteredGroups.map { entry ->
            val group = entry.value
            group.find { repository.hasEpg(it.tvgId) } 
                ?: group.find { !it.tvgId.isNullOrBlank() } 
                ?: group.first()
        }
        adapter.submitList(displayList)
    }

    private fun openPlayer(channel: Channel) {
        val baseName = ChannelGrouping.baseName(channel.name)
        val qualities = groupedChannels[baseName] ?: listOf(channel)

        // Prefer the exact channel/quality the user tapped — its own tvg-id is the correct
        // match for the EPG data. Only fall back to a sibling quality's tvg-id if the clicked
        // one truly doesn't have one (some providers only tag a single quality variant).
        // We deliberately don't gate this on whether the EPG cache has already finished loading
        // (repository.hasEpg): that depends on an async network fetch that may still be running
        // when the user taps a channel, and picking based on it would "freeze" the wrong id into
        // the player screen even after the EPG data arrives moments later.
        val bestTvgId = channel.tvgId?.takeIf { it.isNotBlank() }
            ?: qualities.find { !it.tvgId.isNullOrBlank() }?.tvgId

        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, baseName)
            putExtra(PlayerActivity.EXTRA_STREAM_URL, channel.streamUrl)
            putExtra(PlayerActivity.EXTRA_TVG_ID, bestTvgId)
            
            // Pass all available qualities and their IDs for this channel
            putStringArrayListExtra(PlayerActivity.EXTRA_QUALITY_NAMES, ArrayList(qualities.map { it.name }))
            putStringArrayListExtra(PlayerActivity.EXTRA_QUALITY_URLS, ArrayList(qualities.map { it.streamUrl }))
            putStringArrayListExtra(PlayerActivity.EXTRA_QUALITY_IDS, ArrayList(qualities.map { it.tvgId ?: "" }))
        }
        startActivity(intent)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_favorites -> {
                startActivity(Intent(this, FavoritesActivity::class.java))
                @Suppress("DEPRECATION")
                overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
                return true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onResume() {
        super.onResume()
        // Guard against the early-redirect path in onCreate() (default screen = Favoritos),
        // where this Activity finishes before ever inflating its layout — binding would still
        // be uninitialized if onResume happens to fire in that brief window.
        if (!::binding.isInitialized) return

        // Returning to this screen (e.g. via the star toggle) shouldn't leave the search field
        // focused/keyboard open just because it was focused before navigating away — an Activity
        // that's merely brought back to front (not recreated) keeps whatever focus it had.
        binding.searchInput.clearFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
    }

    companion object {
        const val EXTRA_SKIP_DEFAULT_SCREEN_REDIRECT = "extra_skip_default_screen_redirect"
        private var hasCheckedForUpdateThisSession = false
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

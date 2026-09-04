package com.tosh.iptvplayer.data

import android.content.Context
import android.net.Uri
import com.tosh.iptvplayer.db.AppDatabase
import com.tosh.iptvplayer.db.FavoriteEntity
import com.tosh.iptvplayer.db.ProgrammeEntity
import com.tosh.iptvplayer.db.toEntity
import com.tosh.iptvplayer.db.toModel
import com.tosh.iptvplayer.model.Channel
import com.tosh.iptvplayer.model.BufferMode
import com.tosh.iptvplayer.model.BufferSettings
import com.tosh.iptvplayer.model.DefaultScreen
import com.tosh.iptvplayer.model.ThemeMode
import com.tosh.iptvplayer.model.EpgProgramme
import com.tosh.iptvplayer.model.EpgSyncFrequency
import com.tosh.iptvplayer.model.PlaylistSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

class SourceRepository(private val context: Context) {

    private val db = AppDatabase.get(context)
    private val http = OkHttpClient.Builder().build()
    private val prefs = context.getSharedPreferences("iptv_player_prefs", Context.MODE_PRIVATE)

    /** In-memory EPG cache: tvg-id -> sorted list of programmes. Rebuilt whenever a source's EPG is (re)loaded. */
    private val epgCache = HashMap<String, List<EpgProgramme>>()

    fun getEpgSyncFrequency(): EpgSyncFrequency =
        EpgSyncFrequency.fromName(prefs.getString(PREF_EPG_SYNC_FREQUENCY, null))

    fun setEpgSyncFrequency(frequency: EpgSyncFrequency) {
        prefs.edit().putString(PREF_EPG_SYNC_FREQUENCY, frequency.name).apply()
    }

    /** Millis timestamp of the last successful EPG sync, or null if it never ran. */
    fun getLastEpgSyncMillis(): Long? {
        val value = prefs.getLong(PREF_EPG_LAST_SYNC, 0L)
        return if (value == 0L) null else value
    }

    private fun isEpgSyncDue(): Boolean {
        val frequency = getEpgSyncFrequency()
        if (frequency == EpgSyncFrequency.ALWAYS) return true
        val lastSync = prefs.getLong(PREF_EPG_LAST_SYNC, 0L)
        return (System.currentTimeMillis() - lastSync) >= frequency.intervalMillis
    }

    private fun markEpgSynced() {
        prefs.edit().putLong(PREF_EPG_LAST_SYNC, System.currentTimeMillis()).apply()
    }

    fun observeChannels(): Flow<List<Channel>> =
        db.channelDao().observeAll().map { list -> list.map { it.toModel() } }

    fun observeSources(): Flow<List<PlaylistSource>> =
        db.sourceDao().observeAll().map { list -> list.map { it.toModel() } }

    fun observeFavoriteNames(): Flow<Set<String>> =
        db.favoriteDao().observeAllNames().map { it.toSet() }

    suspend fun toggleFavorite(baseName: String) = withContext(Dispatchers.IO) {
        if (db.favoriteDao().isFavorite(baseName)) {
            db.favoriteDao().remove(baseName)
        } else {
            db.favoriteDao().add(FavoriteEntity(baseName))
        }
    }

    /** One representative Channel row per favorited base name (same "pick the best quality
     * variant to display" logic used by the main channel list), for the Favorites screen. */
    fun observeFavoriteChannels(): Flow<List<Channel>> =
        combine(observeChannels(), observeFavoriteNames()) { channels, favoriteNames ->
            channels.groupBy { ChannelGrouping.baseName(it.name) }
                .filterKeys { it in favoriteNames }
                .map { (_, group) ->
                    group.find { hasEpg(it.tvgId) } ?: group.find { !it.tvgId.isNullOrBlank() } ?: group.first()
                }
        }

    fun getDefaultScreen(): DefaultScreen =
        DefaultScreen.fromName(prefs.getString(PREF_DEFAULT_SCREEN, null))

    fun setDefaultScreen(screen: DefaultScreen) {
        prefs.edit().putString(PREF_DEFAULT_SCREEN, screen.name).apply()
    }

    fun getThemeMode(): ThemeMode =
        ThemeMode.fromName(prefs.getString(PREF_THEME_MODE, null))

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(PREF_THEME_MODE, mode.name).apply()
    }

    fun isPipEnabled(): Boolean = prefs.getBoolean(PREF_PIP_ENABLED, true)

    fun setPipEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_PIP_ENABLED, enabled).apply()
    }

    fun getBufferMode(): BufferMode =
        BufferMode.fromName(prefs.getString(PREF_BUFFER_MODE, null))

    fun setBufferMode(mode: BufferMode) {
        prefs.edit().putString(PREF_BUFFER_MODE, mode.name).apply()
    }

    fun getCustomBufferSeconds(): Int = prefs.getInt(PREF_CUSTOM_BUFFER_SECONDS, 10)

    fun setCustomBufferSeconds(seconds: Int) {
        prefs.edit().putInt(PREF_CUSTOM_BUFFER_SECONDS, seconds.coerceIn(1, 300)).apply()
    }

    /** Resolves the currently selected buffer mode into concrete numbers for the player: either
     * a preset's fixed values, or values derived from the user's custom seconds input. */
    fun getEffectiveBufferSettings(): BufferSettings {
        val mode = getBufferMode()
        return if (mode == BufferMode.CUSTOM) {
            val ms = getCustomBufferSeconds().coerceIn(1, 300) * 1000
            BufferSettings(
                minBufferMs = ms,
                maxBufferMs = ms,
                bufferForPlaybackMs = minOf(2_500, ms),
                bufferForPlaybackAfterRebufferMs = minOf(5_000, ms),
                liveTargetOffsetMs = ms.toLong()
            )
        } else {
            BufferSettings(
                mode.minBufferMs,
                mode.maxBufferMs,
                mode.bufferForPlaybackMs,
                mode.bufferForPlaybackAfterRebufferMs,
                mode.liveTargetOffsetMs
            )
        }
    }

    fun programmesFor(tvgId: String?): List<EpgProgramme> {
        if (tvgId.isNullOrBlank()) return emptyList()
        return epgCache[tvgId].orEmpty()
    }

    fun hasEpg(tvgId: String?): Boolean {
        if (tvgId.isNullOrBlank()) return false
        return epgCache.containsKey(tvgId) && epgCache[tvgId]?.isNotEmpty() == true
    }

    fun currentProgramme(tvgId: String?): EpgProgramme? {
        val now = System.currentTimeMillis()
        return programmesFor(tvgId).firstOrNull { now in it.startMillis until it.stopMillis }
    }

    /**
     * Adds a new playlist source: downloads/reads the M3U, parses channels, persists them,
     * then (if an EPG location was supplied) loads the EPG into the in-memory cache.
     */
    suspend fun addSource(
        name: String,
        playlistLocation: String,
        playlistIsFile: Boolean,
        epgLocation: String?,
        epgIsFile: Boolean
    ) = withContext(Dispatchers.IO) {
        val sourceModel = PlaylistSource(
            name = name,
            playlistLocation = playlistLocation,
            playlistIsFile = playlistIsFile,
            epgLocation = epgLocation,
            epgIsFile = epgIsFile
        )
        val sourceId = db.sourceDao().insert(sourceModel.toEntity())

        var embeddedEpgUrl: String? = null
        openStream(playlistLocation, playlistIsFile).use { stream ->
            val result = M3uParser.parse(stream, sourceId)
            db.channelDao().insertAll(result.channels.map { it.toEntity() })
            embeddedEpgUrl = result.embeddedEpgUrl
        }

        when {
            !epgLocation.isNullOrBlank() -> {
                loadEpg(epgLocation, epgIsFile)
            }
            !embeddedEpgUrl.isNullOrBlank() -> {
                // The playlist itself advertises an EPG (url-tvg / x-tvg-url) and the user
                // didn't set one manually: use it automatically and remember it for future syncs.
                loadEpg(embeddedEpgUrl!!, isFile = false)
                db.sourceDao().updateEpg(sourceId, embeddedEpgUrl, false)
            }
            !playlistIsFile -> {
                // Common with Xtream Codes-style providers: the playlist URL itself carries the
                // account's username/password (e.g. .../get.php?username=X&password=Y&...), and
                // the EPG lives at xmltv.php on that same server/account, without ever being
                // declared inside the M3U. Try to derive and use it automatically.
                val xtreamEpgUrl = deriveXtreamEpgUrl(playlistLocation)
                if (!xtreamEpgUrl.isNullOrBlank()) {
                    runCatching { loadEpg(xtreamEpgUrl, isFile = false) }
                        .onSuccess { db.sourceDao().updateEpg(sourceId, xtreamEpgUrl, false) }
                }
            }
        }
        persistEpgCacheToDisk()
    }

    suspend fun removeSource(source: PlaylistSource) = withContext(Dispatchers.IO) {
        db.channelDao().deleteForSource(source.id)
        db.sourceDao().delete(source.toEntity())
    }

    /** (Re)loads an XMLTV EPG document into the in-memory cache. Safe to call again to refresh. */
    suspend fun loadEpg(location: String, isFile: Boolean) = withContext(Dispatchers.IO) {
        openStream(location, isFile).use { stream ->
            val programmes = XmltvParser.parse(stream)
            val grouped = programmes.groupBy { it.channelTvgId }
                .mapValues { (_, list) -> mergeOverlappingDuplicates(list.sortedBy { it.startMillis }) }
            epgCache.putAll(grouped)
        }
    }

    /**
     * Loads whatever EPG data is already saved on disk into the in-memory cache, without any
     * network access. The in-memory cache alone doesn't survive the app process being killed —
     * without this, choosing a sync interval other than "Sempre" meant the EPG would show as
     * unavailable on every fresh app open until the interval happened to be due, even though it
     * had been successfully fetched before. Cheap to call; skips work if already populated.
     */
    suspend fun restoreEpgCacheFromDisk() = withContext(Dispatchers.IO) {
        if (epgCache.isNotEmpty()) return@withContext
        val rows = db.programmeDao().getAll()
        val grouped = rows.groupBy { it.channelTvgId }
            .mapValues { (_, list) ->
                list.map { EpgProgramme(it.channelTvgId, it.title, it.description, it.startMillis, it.stopMillis) }
                    .sortedBy { it.startMillis }
            }
        epgCache.putAll(grouped)
    }

    /** Persists the current in-memory EPG cache to disk, replacing whatever was there before. */
    private suspend fun persistEpgCacheToDisk() = withContext(Dispatchers.IO) {
        val entities = epgCache.flatMap { (tvgId, programmes) ->
            programmes.map { p ->
                ProgrammeEntity(
                    channelTvgId = tvgId,
                    title = p.title,
                    description = p.description,
                    startMillis = p.startMillis,
                    stopMillis = p.stopMillis
                )
            }
        }
        db.programmeDao().replaceAll(entities)
    }

    /**
     * Aggregated/free XMLTV feeds commonly combine two providers' schedules for the same
     * channel, which can list the same programme twice with slightly different timings (e.g.
     * "Joker" 21:30–22:30 from one feed and 21:35–22:27 from another). An exact-match dedupe
     * doesn't catch this. Instead, walk the (already time-sorted) list and, whenever the next
     * entry has the same title and its start time falls before the previous entry's end time,
     * treat it as the same programme and merge the two into their combined time span rather
     * than showing both.
     */
    private fun mergeOverlappingDuplicates(sorted: List<EpgProgramme>): List<EpgProgramme> {
        if (sorted.isEmpty()) return sorted
        val result = mutableListOf(sorted.first())
        for (i in 1 until sorted.size) {
            val current = sorted[i]
            val last = result.last()
            val sameProgramme = current.title.equals(last.title, ignoreCase = true) &&
                current.startMillis < last.stopMillis
            if (sameProgramme) {
                if (current.stopMillis > last.stopMillis) {
                    result[result.lastIndex] = last.copy(
                        startMillis = minOf(last.startMillis, current.startMillis),
                        stopMillis = current.stopMillis,
                        description = last.description ?: current.description
                    )
                }
                // else current is fully contained within/before last — drop it
            } else {
                result += current
            }
        }
        return result
    }

    /** Re-fetches every source's playlist and replaces its channels with whatever's there now —
     * additions, removals and renames on the provider's side all show up after this, unlike
     * before where channels were only ever fetched once, when the source was first added.
     * Per-source failures (one bad/unreachable source) don't stop the others from updating. */
    suspend fun refreshAllChannels(): Boolean = withContext(Dispatchers.IO) {
        var anySucceeded = false
        for (source in db.sourceDao().getAll()) {
            runCatching {
                openStream(source.playlistLocation, source.playlistIsFile).use { stream ->
                    val result = M3uParser.parse(stream, source.id)
                    db.channelDao().replaceForSource(source.id, result.channels.map { it.toEntity() })
                }
            }.onSuccess { anySucceeded = true }
        }
        anySucceeded
    }

    suspend fun refreshAllEpg(force: Boolean = false) = withContext(Dispatchers.IO) {
        // Always make sure whatever was last fetched is available immediately (fast, local),
        // regardless of whether a fresh network sync is due right now.
        restoreEpgCacheFromDisk()

        if (!force && !isEpgSyncDue()) return@withContext

        // Channels follow the same schedule as EPG — additions/removals/renames on the
        // provider's side show up whenever a scheduled (or manually forced) sync actually runs,
        // not just when the user explicitly pulls to refresh.
        runCatching { refreshAllChannels() }

        db.sourceDao().getAll().forEach { source ->
            val epgLoc = source.epgLocation
            if (!epgLoc.isNullOrBlank()) {
                runCatching { loadEpg(epgLoc, source.epgIsFile) }
            } else if (!source.playlistIsFile) {
                // Source was added before Xtream EPG auto-detection existed, or the guess
                // simply hadn't run yet: try it now and remember it for next time if it works.
                val xtreamEpgUrl = deriveXtreamEpgUrl(source.playlistLocation)
                if (!xtreamEpgUrl.isNullOrBlank()) {
                    runCatching { loadEpg(xtreamEpgUrl, isFile = false) }
                        .onSuccess { db.sourceDao().updateEpg(source.id, xtreamEpgUrl, false) }
                }
            }
        }
        persistEpgCacheToDisk()
        markEpgSynced()
    }

    /**
     * Recognizes Xtream Codes-style playlist URLs — which carry the account's credentials as
     * "username"/"password" query parameters, regardless of the exact path (get.php, etc.) —
     * and derives the standard XMLTV EPG endpoint (xmltv.php) on that same server/account.
     * Returns null if the URL doesn't look like this pattern.
     */
    private fun deriveXtreamEpgUrl(playlistUrl: String): String? = runCatching {
        val uri = java.net.URI(playlistUrl)
        val query = uri.query ?: return null
        val params = query.split("&").mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) null else pair.substring(0, idx) to pair.substring(idx + 1)
        }.toMap()
        val username = params["username"]?.takeIf { it.isNotBlank() } ?: return null
        val password = params["password"]?.takeIf { it.isNotBlank() } ?: return null
        val scheme = uri.scheme ?: "http"
        val host = uri.host ?: return null
        val portPart = if (uri.port != -1) ":${uri.port}" else ""
        "$scheme://$host$portPart/xmltv.php?username=$username&password=$password"
    }.getOrNull()

    private fun openStream(location: String, isFile: Boolean): InputStream {
        val raw = if (isFile) {
            context.contentResolver.openInputStream(Uri.parse(location))
                ?: error("Não foi possível abrir o ficheiro: $location")
        } else {
            val request = Request.Builder().url(location).header("Accept-Encoding", "identity").build()
            val response = http.newCall(request).execute()
            if (!response.isSuccessful) error("Falha ao descarregar: HTTP ${response.code}")
            response.body?.byteStream() ?: error("Resposta vazia de $location")
        }
        return maybeGunzip(raw)
    }

    /**
     * Many EPG (XMLTV) providers serve gzip-compressed content, sometimes even without a .gz
     * extension in the URL. Detect it by magic bytes rather than trusting the file name.
     */
    private fun maybeGunzip(input: InputStream): InputStream {
        val buffered = BufferedInputStream(input, 8 * 1024)
        buffered.mark(2)
        val b0 = buffered.read()
        val b1 = buffered.read()
        buffered.reset()
        val isGzip = b0 == 0x1f && b1 == (0x8b and 0xff)
        return if (isGzip) GZIPInputStream(buffered) else buffered
    }

    companion object {
        private const val PREF_EPG_SYNC_FREQUENCY = "epg_sync_frequency"
        private const val PREF_EPG_LAST_SYNC = "epg_last_sync_millis"
        private const val PREF_DEFAULT_SCREEN = "default_screen"
        private const val PREF_BUFFER_MODE = "buffer_mode"
        private const val PREF_CUSTOM_BUFFER_SECONDS = "custom_buffer_seconds"
        private const val PREF_THEME_MODE = "theme_mode"
        private const val PREF_PIP_ENABLED = "pip_enabled"
    }
}

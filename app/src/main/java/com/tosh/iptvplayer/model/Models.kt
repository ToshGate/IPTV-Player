package com.tosh.iptvplayer.model

/**
 * Where a raw resource (playlist or EPG) comes from.
 */
sealed class SourceLocation {
    data class Url(val url: String) : SourceLocation()
    data class LocalFile(val uri: String, val displayName: String) : SourceLocation()
}

/** A single IPTV channel parsed from an M3U playlist. */
data class Channel(
    val id: Long = 0,
    val sourceId: Long = 0,
    val name: String,
    val streamUrl: String,
    val logoUrl: String? = null,
    val groupTitle: String? = null,
    val tvgId: String? = null,
    val tvgName: String? = null,
    val catchupDays: Int = 0
)

/** A configured playlist source (M3U by URL or file), optionally paired with an EPG source. */
data class PlaylistSource(
    val id: Long = 0,
    val name: String,
    val playlistLocation: String,
    val playlistIsFile: Boolean,
    val epgLocation: String? = null,
    val epgIsFile: Boolean = false
)

/** A single EPG programme entry for a channel (from XMLTV). */
data class EpgProgramme(
    val channelTvgId: String,
    val title: String,
    val description: String? = null,
    val startMillis: Long,
    val stopMillis: Long
)

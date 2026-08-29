package com.tosh.iptvplayer.data

import com.tosh.iptvplayer.model.Channel
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Parses standard IPTV M3U / M3U8 playlists:
 *
 * #EXTM3U
 * #EXTINF:-1 tvg-id="rtp1.pt" tvg-name="RTP 1" tvg-logo="http://.../rtp1.png" group-title="Portugal" catchup-days="7",RTP 1
 * http://server/rtp1/index.m3u8
 */
/** Result of parsing a playlist: the channels plus an optional EPG URL embedded in the header. */
data class M3uParseResult(
    val channels: List<Channel>,
    val embeddedEpgUrl: String? = null
)

object M3uParser {

    private val attrRegex = Regex("""([a-zA-Z0-9\-]+)="([^"]*)"""")

    fun parse(input: InputStream, sourceId: Long): M3uParseResult {
        val channels = mutableListOf<Channel>()
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))

        var pendingName: String? = null
        var pendingLogo: String? = null
        var pendingGroup: String? = null
        var pendingTvgId: String? = null
        var pendingTvgName: String? = null
        var pendingCatchupDays = 0
        var embeddedEpgUrl: String? = null

        reader.forEachLine { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEachLine

            when {
                line.startsWith("#EXTM3U") -> {
                    // Many providers advertise their EPG right here, e.g.:
                    // #EXTM3U url-tvg="http://provider/epg.xml.gz" or x-tvg-url="..."
                    val attrs = attrRegex.findAll(line).associate { it.groupValues[1] to it.groupValues[2] }
                    val raw = attrs["url-tvg"] ?: attrs["x-tvg-url"] ?: attrs["tvg-url"]
                    // Some playlists list multiple comma-separated EPG URLs; use the first one.
                    embeddedEpgUrl = raw?.split(",")?.firstOrNull()?.trim()?.ifBlank { null }
                }
                line.startsWith("#EXTINF") -> {
                    val attrs = attrRegex.findAll(line).associate { it.groupValues[1] to it.groupValues[2] }
                    pendingTvgId = attrs["tvg-id"]
                    pendingTvgName = attrs["tvg-name"]
                    pendingLogo = attrs["tvg-logo"]
                    pendingGroup = attrs["group-title"]
                    pendingCatchupDays = (attrs["catchup-days"] ?: attrs["timeshift"])?.toIntOrNull() ?: 0
                    // Display name is whatever follows the last comma on the EXTINF line
                    pendingName = line.substringAfterLast(',').trim().ifEmpty { pendingTvgName }
                }
                line.startsWith("#") -> {
                    // ignore other directives (#EXTVLCOPT, #EXTGRP, #KODIPROP, etc.)
                }
                else -> {
                    // This is a URL line -> emit the pending channel
                    val name = pendingName ?: line
                    channels += Channel(
                        sourceId = sourceId,
                        name = name,
                        streamUrl = line,
                        logoUrl = pendingLogo,
                        groupTitle = pendingGroup,
                        tvgId = pendingTvgId,
                        tvgName = pendingTvgName,
                        catchupDays = pendingCatchupDays
                    )
                    pendingName = null
                    pendingLogo = null
                    pendingGroup = null
                    pendingTvgId = null
                    pendingTvgName = null
                    pendingCatchupDays = 0
                }
            }
        }
        return M3uParseResult(channels, embeddedEpgUrl)
    }
}

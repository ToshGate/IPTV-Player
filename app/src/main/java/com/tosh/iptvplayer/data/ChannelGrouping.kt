package com.tosh.iptvplayer.data

/**
 * Strips known quality-tier suffixes ("HD", "Full HD", "Low", stylized unicode variants, etc.)
 * from a channel's display name, so all quality variants of the same channel group together
 * under one base name. Shared by every screen that lists/groups channels (main list, favorites)
 * so they all agree on what "the same channel" means.
 */
object ChannelGrouping {

    private val suffixes = listOf(
        "Full HD", "FullHD", "FHD", "HD", "SD", "Low", "UHD", "4K",
        "1080p", "720p", "576p", "480p", "240p",
        // Stylized unicode variants some playlists use instead of plain ASCII
        // (small-caps "LOW" and superscript-style "hevc").
        "ᴸᴼᵂ", "ʰᵉᵛᶜ", "ᴴᴱᵛᶜ", "ᴴᴰ", "ᶠᴴᴰ"
    )

    fun baseName(name: String): String {
        var base = name
        suffixes.forEach { suffix ->
            val regex = Regex("\\s+[(\\[]?${Regex.escape(suffix)}[)\\]]?\\s*$", RegexOption.IGNORE_CASE)
            base = base.replace(regex, "").trim()
        }
        return base
    }
}

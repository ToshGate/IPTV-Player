package com.tosh.iptvplayer.model

/** A single imported WireGuard configuration, stored under its own id so several can coexist. */
data class VpnProfile(
    val id: String,
    val name: String,
    val configText: String
)

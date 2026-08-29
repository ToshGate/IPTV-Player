package com.tosh.iptvplayer.model

/** Which screen the app should show first on launch. */
enum class DefaultScreen(val label: String) {
    ALL_CHANNELS("Todos os canais"),
    FAVORITES("Favoritos");

    companion object {
        fun fromName(name: String?): DefaultScreen =
            values().find { it.name == name } ?: ALL_CHANNELS
    }
}

package com.tosh.iptvplayer.model

/** Which app-wide visual theme to use. */
enum class ThemeMode(val label: String) {
    LIGHT("Claro"),
    DARK("Escuro"),
    SYSTEM("Seguir o sistema");

    companion object {
        // Default to DARK, not SYSTEM: the app was dark-only until now, so anyone who hasn't
        // explicitly picked a theme should keep seeing exactly what they're used to, rather
        // than suddenly switching to light because their phone happens to be in light mode.
        fun fromName(name: String?): ThemeMode =
            values().find { it.name == name } ?: DARK
    }
}

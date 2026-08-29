package com.tosh.iptvplayer.model

/** How often the app should automatically re-download the EPG on launch/app-open. */
enum class EpgSyncFrequency(val label: String, val intervalMillis: Long) {
    ALWAYS("Sempre", 0L),
    DAILY("Todos os dias", 24L * 60 * 60 * 1000),
    EVERY_2_DAYS("De 2 em 2 dias", 2L * 24 * 60 * 60 * 1000),
    WEEKLY("1 vez por semana", 7L * 24 * 60 * 60 * 1000);

    companion object {
        fun fromName(name: String?): EpgSyncFrequency =
            values().find { it.name == name } ?: ALWAYS
    }
}

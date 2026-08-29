package com.tosh.iptvplayer

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.tosh.iptvplayer.data.SourceRepository
import com.tosh.iptvplayer.model.ThemeMode

class IptvApplication : Application() {
    lateinit var repository: SourceRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = SourceRepository(this)

        // Applied before any Activity is created, so the correct theme is already active on the
        // very first frame instead of flashing the default and then switching.
        val mode = when (repository.getThemeMode()) {
            ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}

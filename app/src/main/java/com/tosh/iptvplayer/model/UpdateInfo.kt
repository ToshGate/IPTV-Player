package com.tosh.iptvplayer.model

/** Info about a newer app version available on GitHub Releases. */
data class UpdateInfo(
    val versionName: String,
    val downloadUrl: String,
    val releaseNotes: String,
    val htmlUrl: String
)

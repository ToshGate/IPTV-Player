package com.tosh.iptvplayer.ui

import android.content.Intent
import android.util.AndroidRuntimeException
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Opens the player, surviving one specific race. PlayerActivity is singleTask, so starting it
 * reuses its existing task — and when that task is the PiP window and the person closes the PiP
 * at the very moment they tap a channel, the system is tearing that task down while the launch is
 * targeting it. startActivity() then throws AndroidRuntimeException ("Activity could not be
 * started", START_CANCELED) and, uncaught, takes the whole app down (seen in a release build,
 * 11 ms after "Pinned task is removed"). Once the old task is gone a second attempt lands on a
 * fresh one, so a single short retry is enough.
 */
internal fun AppCompatActivity.startPlayerSafely(intent: Intent) {
    try {
        startActivity(intent)
    } catch (e: AndroidRuntimeException) {
        window.decorView.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            try {
                startActivity(intent)
            } catch (e2: AndroidRuntimeException) {
                Toast.makeText(this, "Não foi possível abrir o leitor. Tenta outra vez.", Toast.LENGTH_SHORT).show()
            }
        }, RETRY_DELAY_MS)
    }
}

private const val RETRY_DELAY_MS = 300L

package com.tosh.iptvplayer.util

import android.app.Activity
import android.media.AudioManager
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs

/**
 * Handles the classic video-player swipe gestures:
 *  - Vertical drag on the LEFT half of the screen -> screen brightness
 *  - Vertical drag on the RIGHT half of the screen -> media volume
 *
 * Attach with [attachTo], and use [onLevelChanged] to update an on-screen indicator
 * (e.g. a small overlay showing the brightness/volume percentage while dragging).
 */
class PlayerGestureController(
    private val activity: Activity,
    private val audioManager: AudioManager
) {

    var onBrightnessChanged: ((percent: Int) -> Unit)? = null
    var onVolumeChanged: ((percent: Int) -> Unit)? = null
    var onGestureEnd: (() -> Unit)? = null

    private var startY = 0f
    private var startBrightness = 0f
    private var startVolumeFraction = 0f
    private var isLeftSide = false
    private var dragging = false

    private val maxVolume get() = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    fun attachTo(view: View) {
        view.setOnTouchListener { v, event ->
            handleTouch(v, event)
        }
    }

    private fun handleTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startY = event.y
                isLeftSide = event.x < view.width / 2f
                startBrightness = currentBrightness()
                
                val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                startVolumeFraction = if (maxVolume == 0) 0f else currentVol.toFloat() / maxVolume
                
                dragging = false
                return false // allow taps / other gestures (e.g. show/hide controls) to pass through
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaY = startY - event.y
                if (!dragging && abs(deltaY) < TOUCH_SLOP) return false
                dragging = true

                val viewHeight = view.height.coerceAtLeast(1)
                val fraction = deltaY / viewHeight // -1..1 across the full screen height

                if (isLeftSide) {
                    val newBrightness = (startBrightness + fraction).coerceIn(0.01f, 1f)
                    setBrightness(newBrightness)
                    onBrightnessChanged?.invoke((newBrightness * 100).toInt())
                } else {
                    val newVolumeFraction = (startVolumeFraction + fraction).coerceIn(0f, 1f)
                    val newVolume = (newVolumeFraction * maxVolume).toInt()
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
                    onVolumeChanged?.invoke((newVolumeFraction * 100).toInt())
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasDragging = dragging
                dragging = false
                if (wasDragging) {
                    onGestureEnd?.invoke()
                    return true
                }
                return false
            }
        }
        return false
    }

    private fun currentBrightness(): Float {
        val lp = activity.window.attributes
        return if (lp.screenBrightness in 0f..1f) lp.screenBrightness
        else {
            // Fall back to the system brightness setting (0-255) if the window hasn't overridden it yet.
            runCatching {
                android.provider.Settings.System.getInt(
                    activity.contentResolver,
                    android.provider.Settings.System.SCREEN_BRIGHTNESS
                ) / 255f
            }.getOrDefault(0.5f)
        }
    }

    private fun setBrightness(value: Float) {
        val lp = activity.window.attributes
        lp.screenBrightness = value
        activity.window.attributes = lp
    }

    companion object {
        private const val TOUCH_SLOP = 16
    }
}

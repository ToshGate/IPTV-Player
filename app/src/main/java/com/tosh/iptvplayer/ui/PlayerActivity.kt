package com.tosh.iptvplayer.ui

import android.app.PictureInPictureParams
import android.content.Context
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.updatePadding
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.launch
import com.tosh.iptvplayer.IptvApplication
import com.tosh.iptvplayer.R
import com.tosh.iptvplayer.databinding.ActivityPlayerBinding
import com.tosh.iptvplayer.util.PlayerGestureController

class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private val repository by lazy { (application as IptvApplication).repository }
    private var sessionStartTime: Long = 0
    private var currentStreamUrl: String? = null
    private var qualityNames: List<String> = emptyList()
    private var qualityUrls: List<String> = emptyList()
    private var qualityIds: List<String> = emptyList()
    private var selectedQualityIndex = 0
    private var currentProgrammes: List<com.tosh.iptvplayer.model.EpgProgramme> = emptyList()

    private val updateProgressAction = object : Runnable {
        override fun run() {
            updateProgress()
            binding.root.postDelayed(this, 1000)
        }
    }

    private val hideControlsAction = Runnable { hideControls() }

    private var hideIndicatorRunnable: Runnable? = null
    private var isActivityResumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)

        // Make the window genuinely edge-to-edge (content draws behind where system bars would
        // be), rather than just hiding the bars and leaving the system to still reserve/inset
        // space for them asymmetrically depending on the device. Needed alongside the insets
        // controller calls below for landscape fullscreen to be evenly centered on phones.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

        setContentView(binding.root)

        // Going edge-to-edge (above) means the system no longer automatically keeps our content
        // clear of the status/navigation bars — we have to do that ourselves now. Pad the header
        // by the status bar's height when it's visible (portrait) and the EPG panel by the
        // navigation bar's height at the bottom; both naturally become 0 once those bars are
        // hidden in landscape fullscreen, so this doesn't fight the immersive video layout.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.headerBar.updatePadding(top = systemBars.top)
            binding.epgContainer.updatePadding(bottom = systemBars.bottom)
            insets
        }

        // Let the video draw fully behind/around the display cutout (camera punch-hole) instead
        // of the system reserving space only on the side the cutout happens to land on when
        // rotated to landscape. Without this, the reserved space pushes the video off-center —
        // fine on tablets (usually no cutout, or centered), visibly wrong on phones with a
        // corner/edge punch-hole camera.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }

        loadChannel(intent)
        setupGestures()
        updateLayoutForOrientation(resources.configuration.orientation, false)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnPip.setOnClickListener {
            showControls()
            enterPip()
        }

        binding.btnQuality.setOnClickListener {
            showControls()
            showQualityDialog()
        }

        binding.btnFullscreen.setOnClickListener {
            showControls()
            val currentOrientation = resources.configuration.orientation
            requestedOrientation = if (currentOrientation == Configuration.ORIENTATION_PORTRAIT) {
                // SENSOR_LANDSCAPE (not plain LANDSCAPE) — plain LANDSCAPE locks to one fixed
                // direction, so physically flipping the phone to the other landscape orientation
                // (e.g. volume buttons swapping sides) wouldn't rotate the video to match. The
                // sensor variant stays within the landscape family but follows the device.
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }

        binding.btnPlayPause.setOnClickListener {
            showControls()
            player?.let {
                if (it.isPlaying) it.pause() else it.play()
            }
        }

        binding.btnLive.setOnClickListener {
            showControls()
            reloadStream()
        }

        binding.videoSeekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    showControls()
                    player?.seekTo(progress.toLong())
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {
                binding.root.removeCallbacks(hideControlsAction)
            }
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                showControls()
            }
        })

        binding.playerView.setOnClickListener {
            if (binding.controlsOverlay.visibility == android.view.View.VISIBLE) {
                hideControls()
            } else {
                showControls()
            }
        }

        // Show controls initially
        showControls()
    }

    private fun showControls() {
        binding.root.removeCallbacks(hideControlsAction)
        binding.controlsOverlay.animate()
            .alpha(1f)
            .setDuration(300)
            .withStartAction { binding.controlsOverlay.visibility = android.view.View.VISIBLE }
            .start()
        binding.root.postDelayed(hideControlsAction, 5000)
    }

    private fun hideControls() {
        binding.controlsOverlay.animate()
            .alpha(0f)
            .setDuration(500)
            .withEndAction { binding.controlsOverlay.visibility = android.view.View.GONE }
            .start()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // With singleTask launch mode, opening another channel while this one is showing (in PiP
        // or otherwise) reuses this same Activity instead of stacking a second player on top.
        // Without setIntent(), getIntent()/`intent` would keep returning the OLD extras.
        setIntent(intent)
        loadChannel(intent)
        showControls()
    }

    /** (Re)loads whichever channel/quality the given intent points to: swaps the playing
     * stream, title, quality list and EPG panel. Safe to call again on an existing instance
     * (e.g. via onNewIntent) — releases the previous player before starting the new one. */
    private fun loadChannel(intent: android.content.Intent) {
        val channelName = intent.getStringExtra(EXTRA_CHANNEL_NAME).orEmpty()
        val streamUrl = intent.getStringExtra(EXTRA_STREAM_URL).orEmpty()
        val tvgId = intent.getStringExtra(EXTRA_TVG_ID)
        qualityNames = intent.getStringArrayListExtra(EXTRA_QUALITY_NAMES).orEmpty()
        qualityUrls = intent.getStringArrayListExtra(EXTRA_QUALITY_URLS).orEmpty()
        qualityIds = intent.getStringArrayListExtra(EXTRA_QUALITY_IDS).orEmpty()
        selectedQualityIndex = qualityUrls.indexOf(streamUrl).coerceAtLeast(0)

        // Reopening the exact same stream that's already loaded (e.g. tapping the channel
        // that's currently playing in PiP) should just bring the player back to the normal
        // view — not restart playback from scratch.
        val isSameStream = streamUrl.isNotBlank() && streamUrl == currentStreamUrl && player != null

        currentStreamUrl = streamUrl
        binding.channelNameLabel.text = channelName
        binding.btnQuality.visibility =
            if (qualityNames.size > 1) android.view.View.VISIBLE else android.view.View.GONE

        if (!isSameStream) {
            player?.release()
            setupPlayer(streamUrl)
        } else {
            // Reopening the same channel after it was paused (e.g. we paused it when the PiP
            // window was dismissed) should resume playback, not sit there silently paused.
            player?.play()
        }
        setupEpgPanel(tvgId)
    }

    private fun setupPlayer(streamUrl: String) {
        val bufferSettings = repository.getEffectiveBufferSettings()
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                bufferSettings.minBufferMs,
                bufferSettings.maxBufferMs,
                bufferSettings.bufferForPlaybackMs,
                bufferSettings.bufferForPlaybackAfterRebufferMs
            )
            .build()

        // Some IPTV providers silently refuse/hang connections from clients whose User-Agent
        // they don't recognize, rather than returning a clear error — which looks exactly like
        // "never starts" with no explanation. Identifying as a common, widely-whitelisted client
        // (VLC) avoids that with providers that gate on this.
        //
        // NOTE: previously also sent Referer/Origin guessed from the stream URL's own host, to
        // cover providers that check those. Reverted: that guess isn't reliable (stream segments
        // are often served from a different CDN host than the provider's actual portal domain,
        // which is what they may really be checking against), and it coincided with a 403 on a
        // channel that may have worked fine without it. Without a way to test against real
        // provider infrastructure, sending a guessed header that might be wrong is worse than
        // sending none.
        val httpClient = okhttp3.OkHttpClient.Builder().build()
        val dataSourceFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(httpClient)
            .setUserAgent("VLC/3.0.18 LibVLC/3.0.18")
        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this)
            .setDataSourceFactory(dataSourceFactory)

        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build()
        binding.playerView.player = exoPlayer
        binding.playerView.useController = false // we draw our own minimal controls overlay
        binding.playerView.keepScreenOn = true

        exoPlayer.addListener(object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                binding.btnPlayPause.setImageResource(
                    if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                )
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // Surface the real reason instead of the stream just silently never starting —
                // this is exactly what's needed to tell "codec not supported" apart from a
                // network/auth/timeout problem.
                val causeMessage = error.cause?.message.orEmpty()
                val message = when {
                    causeMessage.contains("#EXTM3U", ignoreCase = true) ->
                        "Este stream não devolveu uma playlist válida — o servidor pode estar a bloquear o pedido ou o link estar errado/expirado."
                    causeMessage.contains("403") ->
                        "O servidor recusou o pedido (403). O link pode ter expirado, exigir autenticação diferente, ou estar bloqueado para esta ligação/região."
                    causeMessage.contains("404") ->
                        "Stream não encontrado (404). O link deste canal pode estar errado ou já não existir."
                    else ->
                        "Erro ao reproduzir: ${error.errorCodeName} — ${error.message}"
                }
                android.widget.Toast.makeText(this@PlayerActivity, message, android.widget.Toast.LENGTH_LONG).show()
            }
        })

        exoPlayer.setMediaItem(buildMediaItem(streamUrl, bufferSettings))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        player = exoPlayer
        sessionStartTime = System.currentTimeMillis()
    }

    private fun setupGestures() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val gestureController = PlayerGestureController(this, audioManager)

        gestureController.onBrightnessChanged = { percent ->
            showIndicator(R.drawable.ic_gesture_brightness, percent)
        }
        gestureController.onVolumeChanged = { percent ->
            showIndicator(R.drawable.ic_gesture_volume, percent)
        }
        gestureController.onGestureEnd = {
            binding.gestureIndicator.postDelayed({
                binding.gestureIndicator.visibility = android.view.View.GONE
            }, 500)
        }
        gestureController.attachTo(binding.playerView)
    }

    private fun showIndicator(iconRes: Int, percent: Int) {
        binding.gestureIndicator.visibility = android.view.View.VISIBLE
        binding.gestureIndicatorIcon.setImageResource(iconRes)
        binding.gestureIndicatorValue.text = "$percent%"
        binding.gestureIndicatorBar.progress = percent
    }

    private fun setupEpgPanel(tvgId: String?) {
        // Make sure the disk-persisted EPG has actually been loaded into memory before reading
        // it — restoreEpgCacheFromDisk() normally runs from MainActivity on app open, but that's
        // a separate, independently-timed coroutine; if a channel is opened quickly enough, it
        // can still be in flight, and programmesFor() would silently read an empty cache. This
        // guarantees it's ready first (the call is cheap/idempotent if already loaded).
        lifecycleScope.launch {
            repository.restoreEpgCacheFromDisk()
            renderEpgPanel(repository.programmesFor(tvgId))
        }
    }

    private fun renderEpgPanel(programmes: List<com.tosh.iptvplayer.model.EpgProgramme>) {
        currentProgrammes = programmes
        updateFullscreenProgramLabel(resources.configuration.orientation)

        if (programmes.isEmpty()) {
            binding.epgEmptyLabel.visibility = android.view.View.VISIBLE
            binding.epgList.visibility = android.view.View.GONE
        } else {
            binding.epgEmptyLabel.visibility = android.view.View.GONE
            binding.epgList.visibility = android.view.View.VISIBLE
            val layoutManager = LinearLayoutManager(this)
            binding.epgList.layoutManager = layoutManager
            binding.epgList.adapter = EpgAdapter(programmes)

            // Scroll to current programme with a small delay to ensure layout is ready
            binding.epgList.post {
                val now = System.currentTimeMillis()
                val currentIndex = programmes.indexOfFirst { now in it.startMillis until it.stopMillis }
                if (currentIndex != -1) {
                    layoutManager.scrollToPositionWithOffset(currentIndex, 0)
                }
            }
        }
    }

    /** Current programme name shown over the video — only in fullscreen/landscape, where the
     * header (which normally shows the channel name) is hidden. Re-evaluated both when fresh EPG
     * data arrives and whenever the orientation changes, so rotating mid-viewing updates it too. */
    private fun updateFullscreenProgramLabel(orientation: Int) {
        val now = System.currentTimeMillis()
        val current = currentProgrammes.firstOrNull { now in it.startMillis until it.stopMillis }
        val isLandscape = orientation == Configuration.ORIENTATION_LANDSCAPE
        if (current == null || !isLandscape) {
            binding.fullscreenProgramLabel.visibility = android.view.View.GONE
        } else {
            binding.fullscreenProgramLabel.text = current.title
            binding.fullscreenProgramLabel.visibility = android.view.View.VISIBLE
            // Marquee only actually animates once the TextView is marked "selected".
            binding.fullscreenProgramLabel.isSelected = true
        }
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val aspectRatio = Rational(16, 9)
            val videoBounds = android.graphics.Rect()
            binding.playerView.getGlobalVisibleRect(videoBounds)
            val paramsBuilder = PictureInPictureParams.Builder()
                .setAspectRatio(aspectRatio)
            if (!videoBounds.isEmpty) {
                paramsBuilder.setSourceRectHint(videoBounds)
            }
            enterPictureInPictureMode(paramsBuilder.build())
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Auto-enter floating (PiP) mode when the user leaves the app while a channel is playing,
        // enabling multitasking instead of stopping playback.
        if (player?.isPlaying == true) {
            enterPip()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        updateLayoutForOrientation(newConfig.orientation, isInPictureInPictureMode)

        if (!isInPictureInPictureMode) {
            // We just left PiP — but that happens both when the user taps it to expand back to
            // fullscreen (onResume follows right away, should keep playing) *and* when the PiP
            // window is swiped away/dismissed (on some devices this only hides the floating
            // window without promptly calling onStop()/finishing the Activity, so relying on
            // those alone wasn't catching it). Give it a brief moment to see which one this is:
            // if we're still not resumed shortly after, treat it as genuinely closed — stop the
            // stream itself (not just mute it) and close this screen, same as pressing back.
            binding.root.postDelayed({
                if (!isActivityResumed) {
                    player?.stop()
                    finish()
                }
            }, 300)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Deferred to the next frame: right when this callback fires, the window may not have
        // fully committed its new post-rotation pixel bounds yet on every device, so measuring/
        // laying out the video immediately here can lock in a stale, too-narrow width — showing
        // up as the video being off-center. Posting lets that settle first.
        binding.root.post {
            updateLayoutForOrientation(newConfig.orientation, isInPictureInPictureModeCompat())
        }
    }

    private fun updateLayoutForOrientation(orientation: Int, isPip: Boolean) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (isPip) {
            binding.controlsOverlay.visibility = android.view.View.GONE
            binding.headerBar.visibility = android.view.View.GONE
            binding.epgContainer.visibility = android.view.View.GONE
            // The video was likely sized to the fixed 220dp portrait height before entering PiP.
            // Without expanding it to fill the whole window, the system shrinks the *entire*
            // window (including the empty space below the video) into the small PiP box,
            // making the stream appear shifted down and cropped. Make both the video and its
            // container fill the window instead.
            binding.playerContainer.layoutParams = binding.playerContainer.layoutParams.apply {
                height = ViewGroup.LayoutParams.MATCH_PARENT
            }
            binding.playerView.layoutParams = binding.playerView.layoutParams.apply {
                height = ViewGroup.LayoutParams.MATCH_PARENT
            }
            binding.root.requestLayout()
            return
        }

        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            binding.headerBar.visibility = android.view.View.GONE
            binding.epgContainer.visibility = android.view.View.GONE
            // Same issue as the PiP case: resizing only the video (not its wrap_content parent
            // container) leaves the measurement ambiguous, so the video can end up off-center
            // or pinned to one edge depending on the device/screen size. Expand both.
            binding.playerContainer.layoutParams = binding.playerContainer.layoutParams.apply {
                height = ViewGroup.LayoutParams.MATCH_PARENT
            }
            binding.playerView.layoutParams = binding.playerView.layoutParams.apply {
                height = ViewGroup.LayoutParams.MATCH_PARENT
            }

            // Hide system bars in landscape for true fullscreen experience
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            binding.headerBar.visibility = android.view.View.VISIBLE
            binding.epgContainer.visibility = android.view.View.VISIBLE
            // Reset back to the fixed portrait video height — otherwise, once landscape has set
            // this to MATCH_PARENT, returning to portrait would leave it stuck expanded,
            // covering the EPG list below.
            binding.playerContainer.layoutParams = binding.playerContainer.layoutParams.apply {
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
            binding.playerView.layoutParams = binding.playerView.layoutParams.apply {
                height = (220 * resources.displayMetrics.density).toInt()
            }
            
            // Show system bars in portrait
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        updateFullscreenProgramLabel(orientation)
        binding.root.requestLayout()
    }

    override fun onStop() {
        super.onStop()
        binding.root.removeCallbacks(updateProgressAction)
        // Closing/dismissing the PiP window (swipe away, close button) finishes the Activity
        // while it's technically still "in PiP mode" for this instant, so checking PiP mode
        // alone wasn't enough to tell "still floating, keep playing" apart from "being closed,
        // stop now" — isFinishing distinguishes the two.
        if (!isInPictureInPictureModeCompat() || isFinishing) {
            player?.pause()
        }
    }

    override fun onResume() {
        super.onResume()
        isActivityResumed = true
        binding.root.post(updateProgressAction)
    }

    override fun onPause() {
        super.onPause()
        isActivityResumed = false
    }

    private fun updateProgress() {
        player?.let {
            val duration = it.duration
            val isLive = it.isCurrentMediaItemLive || duration <= 0

            if (!isLive) {
                val currentPos = it.currentPosition
                binding.videoSeekBar.max = duration.toInt()
                binding.videoSeekBar.progress = currentPos.toInt()
                binding.totalTime.text = formatTime(duration)
                binding.totalTime.visibility = android.view.View.VISIBLE
                binding.btnLive.visibility = android.view.View.GONE
                binding.videoSeekBar.visibility = android.view.View.VISIBLE
                binding.currentTime.text = formatTime(currentPos)
            } else {
                // For Live: show time elapsed since session start
                val elapsed = System.currentTimeMillis() - sessionStartTime
                binding.videoSeekBar.max = elapsed.toInt()
                binding.videoSeekBar.progress = elapsed.toInt()
                binding.totalTime.visibility = android.view.View.GONE
                binding.btnLive.visibility = android.view.View.VISIBLE
                binding.videoSeekBar.visibility = android.view.View.VISIBLE
                binding.currentTime.text = formatTime(elapsed)
            }
        }
    }

    private fun reloadStream() {
        currentStreamUrl?.let { url ->
            player?.let {
                val currentPos = it.currentPosition
                it.stop()
                it.setMediaItem(buildMediaItem(url, repository.getEffectiveBufferSettings()))
                it.prepare()
                it.playWhenReady = true
                if (!it.isCurrentMediaItemLive) {
                    it.seekTo(currentPos)
                }
                sessionStartTime = System.currentTimeMillis()
            }
        }
    }

    /** Builds the MediaItem with a live-offset target matching the chosen buffer mode — this is
     * what actually controls how far behind the live edge playback sits, independently of the
     * local resilience buffer sizes configured on the player's LoadControl. */
    private fun buildMediaItem(streamUrl: String, bufferSettings: com.tosh.iptvplayer.model.BufferSettings): MediaItem {
        val target = bufferSettings.liveTargetOffsetMs
        return MediaItem.Builder()
            .setUri(streamUrl)
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(target)
                    // Without a min/max range, the player treats the target as more of a loose
                    // suggestion. Giving it a band around the target — and permission to nudge
                    // playback speed slightly — lets it actually converge on and hold the
                    // requested distance behind live, instead of drifting back close to live.
                    .setMinOffsetMs((target * 0.7).toLong())
                    .setMaxOffsetMs((target * 1.3).toLong())
                    .setMinPlaybackSpeed(0.97f)
                    .setMaxPlaybackSpeed(1.03f)
                    .build()
            )
            .build()
    }

    private fun showQualityDialog() {
        val names = qualityNames.toTypedArray()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.CustomDarkDialog)
            .setTitle("Selecionar Qualidade")
            .setSingleChoiceItems(names, selectedQualityIndex) { dialog, which ->
                selectedQualityIndex = which
                currentStreamUrl = qualityUrls[which]
                reloadStream()
                
                // Refresh EPG if the new quality has a different ID
                val newTvgId = qualityIds.getOrNull(which)
                if (!newTvgId.isNullOrBlank()) {
                    setupEpgPanel(newTvgId)
                }
                
                dialog.dismiss()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun formatTime(millis: Long): String {
        val totalSeconds = Math.max(0, millis / 1000)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }

    private fun isInPictureInPictureModeCompat(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode

    companion object {
        const val EXTRA_CHANNEL_NAME = "extra_channel_name"
        const val EXTRA_STREAM_URL = "extra_stream_url"
        const val EXTRA_TVG_ID = "extra_tvg_id"
        const val EXTRA_QUALITY_NAMES = "extra_quality_names"
        const val EXTRA_QUALITY_URLS = "extra_quality_urls"
        const val EXTRA_QUALITY_IDS = "extra_quality_ids"
    }
}

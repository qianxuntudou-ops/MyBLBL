package com.tutu.myblbl.feature.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer

internal object PlayerInstancePool {
    private const val IDLE_RELEASE_DELAY_MS = 45_000L
    private const val MIN_BUFFER_MS = 3_000
    private const val MAX_BUFFER_MS = 12_000
    private const val BUFFER_FOR_PLAYBACK_MS = 2_000
    private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 3_000
    private const val TARGET_BUFFER_BYTES = 10 * 1024 * 1024 // 10MB

    private val mainHandler = Handler(Looper.getMainLooper())

    private var cachedPlayer: ExoPlayer? = null
    private var isAttached = false
    private var pendingReleaseRunnable: Runnable? = null

    @Synchronized
    fun acquire(context: Context): ExoPlayer {
        cancelPendingRelease()
        val reused = cachedPlayer != null
        val player = cachedPlayer ?: buildPlayer(context.applicationContext).also {
            cachedPlayer = it
        }
        isAttached = true
        if (reused) {
            val state = player.playbackState
            val hasMedia = player.mediaItemCount
        } else {
        }
        return player
    }

    @Synchronized
    fun softDetach(player: ExoPlayer?) {
        if (player == null || player !== cachedPlayer) return
        val stateBefore = player.playbackState
        val mediaCount = player.mediaItemCount
        
        player.pause()
        isAttached = false
        player.playWhenReady = false
        player.stop()
        player.clearMediaItems()
        player.clearVideoSurface()
        scheduleRelease()
    }

    @Synchronized
    fun hardReset(player: ExoPlayer?) {
        if (player == null || player !== cachedPlayer) return
        val startMs = System.currentTimeMillis()
        player.playWhenReady = false
        player.clearMediaItems()
        player.stop()
        player.playbackParameters = PlaybackParameters(1f)
    }

    @Synchronized
    fun detach(player: ExoPlayer?, allowReuse: Boolean) {
        if (player == null || player !== cachedPlayer) return
        if (!allowReuse) {
            releaseNow("detach_without_reuse")
            return
        }
        softDetach(player)
    }

    @Synchronized
    fun releaseNow(reason: String) {
        cancelPendingRelease()
        isAttached = false
        cachedPlayer?.let(PlayerAudioNormalizer::release)
        cachedPlayer?.release()
        cachedPlayer = null
    }

    @Synchronized
    private fun scheduleRelease() {
        cancelPendingRelease()
        val releaseRunnable = Runnable {
            synchronized(this) {
                if (isAttached) return@synchronized
                cachedPlayer?.let(PlayerAudioNormalizer::release)
                cachedPlayer?.release()
                cachedPlayer = null
                pendingReleaseRunnable = null
            }
        }
        pendingReleaseRunnable = releaseRunnable
        mainHandler.postDelayed(releaseRunnable, IDLE_RELEASE_DELAY_MS)
    }

    @Synchronized
    private fun cancelPendingRelease() {
        pendingReleaseRunnable?.let(mainHandler::removeCallbacks)
        pendingReleaseRunnable = null
    }

    private fun buildPlayer(context: Context): ExoPlayer {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                MIN_BUFFER_MS,
                MAX_BUFFER_MS,
                BUFFER_FOR_PLAYBACK_MS,
                BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            .setTargetBufferBytes(TARGET_BUFFER_BYTES)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        return ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build()
            .also(PlayerPlaybackPolicy::apply)
    }
}

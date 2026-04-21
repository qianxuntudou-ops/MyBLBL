package com.tutu.myblbl.feature.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import com.tutu.myblbl.core.common.log.AppLog

object PlayerInstancePool {
    private const val TAG = "PlayerInstancePool"
    private const val IDLE_RELEASE_DELAY_MS = 45_000L
    private const val MIN_BUFFER_MS = 3_000
    private const val MAX_BUFFER_MS = 12_000
    private const val BUFFER_FOR_PLAYBACK_MS = 100
    private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 500
    private const val TARGET_BUFFER_BYTES = 10 * 1024 * 1024 // 10MB

    private val mainHandler = Handler(Looper.getMainLooper())

    private var cachedPlayer: ExoPlayer? = null
    private var isAttached = false
    private var pendingReleaseRunnable: Runnable? = null

    @Synchronized
    fun prewarm(context: Context) {
        if (cachedPlayer != null) {
            AppLog.i(TAG, "prewarm: already cached, skip")
            return
        }
        AppLog.i(TAG, "prewarm: building new player")
        mainHandler.post {
            synchronized(this) {
                if (cachedPlayer != null) return@synchronized
                cachedPlayer = buildPlayer(context.applicationContext)
                AppLog.i(TAG, "prewarm: player built and cached")
            }
        }
    }

    @Synchronized
    fun acquire(context: Context): ExoPlayer {
        cancelPendingRelease()
        val reused = cachedPlayer != null
        val player = cachedPlayer ?: buildPlayer(context.applicationContext).also {
            cachedPlayer = it
        }
        isAttached = true
        AppLog.i(TAG, "acquire: reused=$reused attached=$isAttached")
        return player
    }

    @Synchronized
    fun softDetach(player: ExoPlayer?) {
        if (player == null || player !== cachedPlayer) return
        AppLog.i(TAG, "softDetach: stopping player, will release after ${IDLE_RELEASE_DELAY_MS}ms idle")
        player.pause()
        isAttached = false
        player.playWhenReady = false
        player.stop()
        player.clearMediaItems()
        player.clearVideoSurface()
        schedule_release()
    }

    @Synchronized
    fun hardReset(player: ExoPlayer?) {
        if (player == null || player !== cachedPlayer) return
        AppLog.i(TAG, "hardReset")
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
        AppLog.i(TAG, "releaseNow: reason=$reason")
        cancelPendingRelease()
        isAttached = false
        cachedPlayer?.let(PlayerAudioNormalizer::release)
        cachedPlayer?.release()
        cachedPlayer = null
    }

    @Synchronized
    private fun schedule_release() {
        cancelPendingRelease()
        val releaseRunnable = Runnable {
            synchronized(this) {
                if (isAttached) return@synchronized
                AppLog.i(TAG, "scheduledRelease: idle timeout, releasing player")
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

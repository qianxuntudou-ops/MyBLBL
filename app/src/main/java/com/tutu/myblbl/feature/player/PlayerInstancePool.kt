package com.tutu.myblbl.feature.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import com.tutu.myblbl.core.common.log.AppLog

internal object PlayerInstancePool {

    private const val TAG = "PlayerInstancePool"
    private const val IDLE_RELEASE_DELAY_MS = 45_000L
    private const val MIN_BUFFER_MS = 1_000
    private const val MAX_BUFFER_MS = 12_000
    private const val BUFFER_FOR_PLAYBACK_MS = 100
    private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 500

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
        AppLog.d(TAG, if (reused) "acquire reused player" else "acquire created player")
        return player
    }

    @Synchronized
    fun detach(player: ExoPlayer?, allowReuse: Boolean) {
        if (player == null || player !== cachedPlayer) {
            return
        }
        isAttached = false
        if (!allowReuse) {
            releaseNow("detach_without_reuse")
            return
        }
        resetForReuse(player)
        scheduleRelease()
        AppLog.d(TAG, "detach scheduled release in ${IDLE_RELEASE_DELAY_MS}ms")
    }

    @Synchronized
    fun releaseNow(reason: String) {
        cancelPendingRelease()
        isAttached = false
        cachedPlayer?.let(PlayerAudioNormalizer::release)
        cachedPlayer?.release()
        cachedPlayer = null
        AppLog.d(TAG, "releaseNow: reason=$reason")
    }

    @Synchronized
    private fun scheduleRelease() {
        cancelPendingRelease()
        val releaseRunnable = Runnable {
            synchronized(this) {
                if (isAttached) {
                    return@synchronized
                }
                cachedPlayer?.let(PlayerAudioNormalizer::release)
                cachedPlayer?.release()
                cachedPlayer = null
                pendingReleaseRunnable = null
                AppLog.d(TAG, "cached player released after idle timeout")
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

    private fun resetForReuse(player: ExoPlayer) {
        player.playWhenReady = false
        player.clearVideoSurface()
        player.clearMediaItems()
        player.stop()
        player.playbackParameters = PlaybackParameters(1f)
    }

    private fun buildPlayer(context: Context): ExoPlayer {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                MIN_BUFFER_MS,
                MAX_BUFFER_MS,
                BUFFER_FOR_PLAYBACK_MS,
                BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            .setTargetBufferBytes(DefaultLoadControl.DEFAULT_TARGET_BUFFER_BYTES)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        return ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build()
            .also(PlayerPlaybackPolicy::apply)
    }
}

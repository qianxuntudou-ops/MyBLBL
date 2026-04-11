package com.tutu.myblbl.feature.player

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.Player

class VideoPlayerProgressCoordinator(
    private val playerProvider: () -> Player?,
    private val publishProgressStateProvider: () -> Boolean,
    private val onProgressPublished: (positionMs: Long, durationMs: Long, publishProgressState: Boolean) -> Unit,
    private val onPlaybackPositionChanged: (positionMs: Long) -> Unit,
    private val onPlaybackStalled: (positionMs: Long, stalledMs: Long) -> Unit = { _, _ -> }
) {
    companion object {
        private const val PROGRESS_POLL_INTERVAL_MS = 500L
        private const val PLAYBACK_STALL_THRESHOLD_MS = 2500L
        private const val PLAYBACK_STALL_RECOVERY_COOLDOWN_MS = 5000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastPlaybackSyncPositionMs: Long = -1L
    private var lastPlaybackSyncDurationMs: Long = -1L
    private var lastPublishedProgressPositionMs: Long = -1L
    private var lastPublishedProgressDurationMs: Long = -1L
    private var stallTrackedPositionMs: Long = -1L
    private var stallStartedAtElapsedMs: Long = 0L
    private var lastStallRecoveryAtElapsedMs: Long = 0L

    private val progressRunnable = object : Runnable {
        override fun run() {
            val player = playerProvider() ?: return
            sync(player, publishProgressStateProvider())
            if (player.isPlaying) {
                handler.postDelayed(this, PROGRESS_POLL_INTERVAL_MS)
            }
        }
    }

    fun restart() {
        handler.removeCallbacks(progressRunnable)
        handler.post(progressRunnable)
    }

    fun stop() {
        handler.removeCallbacks(progressRunnable)
    }

    fun syncNow(publishProgressState: Boolean = true) {
        val player = playerProvider() ?: return
        sync(player, publishProgressState)
    }

    fun reset() {
        lastPlaybackSyncPositionMs = -1L
        lastPlaybackSyncDurationMs = -1L
        lastPublishedProgressPositionMs = -1L
        lastPublishedProgressDurationMs = -1L
        stallTrackedPositionMs = -1L
        stallStartedAtElapsedMs = 0L
        lastStallRecoveryAtElapsedMs = 0L
    }

    private fun sync(player: Player, publishProgressState: Boolean) {
        val positionMs = player.currentPosition.coerceAtLeast(0L)
        val durationMs = player.duration.takeIf { it > 0L } ?: 0L
        maybeHandlePlaybackStall(player, positionMs, durationMs)
        val playbackChanged =
            positionMs != lastPlaybackSyncPositionMs || durationMs != lastPlaybackSyncDurationMs
        val progressStateChanged =
            positionMs != lastPublishedProgressPositionMs || durationMs != lastPublishedProgressDurationMs

        if (!playbackChanged && (!publishProgressState || !progressStateChanged)) {
            return
        }

        onProgressPublished(positionMs, durationMs, publishProgressState)
        if (publishProgressState) {
            lastPublishedProgressPositionMs = positionMs
            lastPublishedProgressDurationMs = durationMs
        }
        if (!playbackChanged) {
            return
        }
        lastPlaybackSyncPositionMs = positionMs
        lastPlaybackSyncDurationMs = durationMs
        onPlaybackPositionChanged(positionMs)
    }

    private fun maybeHandlePlaybackStall(player: Player, positionMs: Long, durationMs: Long) {
        val shouldMonitor =
            player.playWhenReady &&
                player.playbackState == Player.STATE_READY &&
                durationMs > 0L &&
                positionMs < durationMs
        if (!shouldMonitor) {
            clearStallTracking(positionMs)
            return
        }

        val nowElapsedMs = SystemClock.elapsedRealtime()
        if (positionMs != stallTrackedPositionMs) {
            stallTrackedPositionMs = positionMs
            stallStartedAtElapsedMs = nowElapsedMs
            return
        }
        if (stallStartedAtElapsedMs == 0L) {
            stallStartedAtElapsedMs = nowElapsedMs
            return
        }
        val stalledMs = nowElapsedMs - stallStartedAtElapsedMs
        val recoveryCooldownMs = nowElapsedMs - lastStallRecoveryAtElapsedMs
        if (
            stalledMs >= PLAYBACK_STALL_THRESHOLD_MS &&
            recoveryCooldownMs >= PLAYBACK_STALL_RECOVERY_COOLDOWN_MS
        ) {
            lastStallRecoveryAtElapsedMs = nowElapsedMs
            onPlaybackStalled(positionMs, stalledMs)
        }
    }

    private fun clearStallTracking(positionMs: Long) {
        stallTrackedPositionMs = positionMs
        stallStartedAtElapsedMs = 0L
    }
}

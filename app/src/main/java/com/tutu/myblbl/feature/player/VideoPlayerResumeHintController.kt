package com.tutu.myblbl.feature.player

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.tutu.myblbl.R
import com.tutu.myblbl.core.common.log.AppLog

@UnstableApi
class VideoPlayerResumeHintController(
    private val activity: AppCompatActivity,
    private val playerProvider: () -> Player?,
    private val onCancelResume: () -> Unit,
    private val onClearResumeHint: () -> Unit
) {

    companion object {
        private const val TAG = "ResumeHintController"
        private const val SEEK_DELAY_MS = 3_000L
        private const val READY_TIMEOUT_MS = 30_000L
        private const val POLL_INTERVAL_MS = 50L
        private const val TRACE_THROTTLE_MS = 500L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var resumeHintRunnable: Runnable? = null
    private var resumeHintStartTimeMs: Long = 0L
    private var resumeHintTargetPositionMs: Long = 0L
    private var resumeHintToast: Toast? = null
    private var isResumeHintCancelled: Boolean = false
    private var isResumeHintActive: Boolean = false
    private var lastTraceSignature: String? = null
    private var lastTraceTimestampMs: Long = 0L

    fun cancelResume(): Boolean {
        if (!isResumeHintActive) {
            return false
        }
        AppLog.d(TAG, "cancelResume: cancelling resume progress")
        clearPendingUi(markCancelled = true)
        onCancelResume()
        return true
    }

    fun onHintChanged(hint: VideoPlayerViewModel.ResumeProgressHint?) {
        AppLog.d(TAG, "resumeHint observed: $hint")
        if (hint == null) {
            clearPendingUi(markCancelled = true)
            return
        }

        isResumeHintCancelled = false
        isResumeHintActive = true
        resumeHintTargetPositionMs = hint.targetPositionMs
        resumeHintStartTimeMs = System.currentTimeMillis()
        lastTraceSignature = null
        lastTraceTimestampMs = 0L

        resumeHintToast?.cancel()
        resumeHintToast = Toast.makeText(
activity,
                activity.getString(R.string.tip_play_from_history),
            Toast.LENGTH_LONG
        ).also { it.show() }

        scheduleResumeCheck(immediate = true)
    }

    fun release() {
        clearPendingUi(markCancelled = true)
    }

    private fun scheduleResumeCheck(immediate: Boolean) {
        resumeHintRunnable?.let(handler::removeCallbacks)
        resumeHintRunnable = Runnable { checkResumeHint() }
        if (immediate) {
            handler.post(resumeHintRunnable!!)
        } else {
            handler.postDelayed(resumeHintRunnable!!, POLL_INTERVAL_MS)
        }
    }

    private fun checkResumeHint() {
        val player = playerProvider() ?: return
        if (isResumeHintCancelled) {
            AppLog.d(TAG, "checkResumeHint: cancelled, skip")
            return
        }

        val currentTimeMs = System.currentTimeMillis()
        val seekNotBeforeAtMs = resumeHintStartTimeMs + SEEK_DELAY_MS
        val readyDeadlineAtMs = resumeHintStartTimeMs + READY_TIMEOUT_MS

        if (currentTimeMs >= readyDeadlineAtMs) {
            AppLog.d(TAG, "checkResumeHint: timeout, clearing")
            onClearResumeHint()
            return
        }

        if (player.playbackState == Player.STATE_ENDED) {
            AppLog.d(TAG, "checkResumeHint: playback ended, clearing")
            onClearResumeHint()
            return
        }

        val playerReady = player.playbackState == Player.STATE_READY
        val waitedEnough = currentTimeMs >= seekNotBeforeAtMs
        val traceSignature = "${player.playbackState}|$playerReady|$waitedEnough|${resumeHintTargetPositionMs / 1000}"
        val shouldTrace = traceSignature != lastTraceSignature ||
            currentTimeMs - lastTraceTimestampMs >= TRACE_THROTTLE_MS
        if (shouldTrace) {
            lastTraceSignature = traceSignature
            lastTraceTimestampMs = currentTimeMs
            AppLog.d(
                TAG,
                "checkResumeHint: state=${player.playbackState}, ready=$playerReady, waitedEnough=$waitedEnough, target=${resumeHintTargetPositionMs}ms"
            )
        }

        if (playerReady && waitedEnough) {
            val durationMs = player.duration.takeIf { it > 0L } ?: 0L
            val clampedTargetPositionMs = if (durationMs > 0L) {
                resumeHintTargetPositionMs.coerceIn(0L, (durationMs - 500L).coerceAtLeast(0L))
            } else {
                resumeHintTargetPositionMs
            }
            AppLog.d(TAG, "checkResumeHint: seeking to $clampedTargetPositionMs ms (duration=$durationMs)")
            onClearResumeHint()
            player.seekTo(clampedTargetPositionMs)
            return
        }

        scheduleResumeCheck(immediate = false)
    }

    private fun clearPendingUi(markCancelled: Boolean) {
        isResumeHintCancelled = markCancelled
        isResumeHintActive = false
        lastTraceSignature = null
        lastTraceTimestampMs = 0L
        resumeHintRunnable?.let(handler::removeCallbacks)
        resumeHintRunnable = null
        resumeHintToast?.cancel()
        resumeHintToast = null
    }
}

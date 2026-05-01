package com.tutu.myblbl.feature.player

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.tutu.myblbl.R
import java.util.Locale

@UnstableApi
class VideoPlayerResumeHintController(
    private val activity: AppCompatActivity,
    private val playerProvider: () -> Player?,
    private val onCancelResume: () -> Unit,
    private val onClearResumeHint: () -> Unit
) {

    companion object {
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
        clearPendingUi(markCancelled = true)
        onCancelResume()
        return true
    }

    fun onHintChanged(hint: VideoPlayerViewModel.ResumeProgressHint?) {
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
        val timeStr = formatTime(hint.targetPositionMs)
        resumeHintToast = Toast.makeText(
            activity,
            activity.getString(R.string.tip_play_from_history, timeStr),
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
            return
        }

        val currentTimeMs = System.currentTimeMillis()
        val seekNotBeforeAtMs = resumeHintStartTimeMs + SEEK_DELAY_MS
        val readyDeadlineAtMs = resumeHintStartTimeMs + READY_TIMEOUT_MS

        if (currentTimeMs >= readyDeadlineAtMs) {
            onClearResumeHint()
            return
        }

        if (player.playbackState == Player.STATE_ENDED) {
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
        }

        if (playerReady && waitedEnough) {
            val durationMs = player.duration.takeIf { it > 0L } ?: 0L
            val clampedTargetPositionMs = if (durationMs > 0L) {
                resumeHintTargetPositionMs.coerceIn(0L, (durationMs - 500L).coerceAtLeast(0L))
            } else {
                resumeHintTargetPositionMs
            }
            onClearResumeHint()
            // setMediaSource(source, seekPositionMs) already starts the player at the
            // resume position.  Seeking again after the 3 s toast window causes a backward
            // jump + re-buffer; when the audio CDN is fast but the video CDN is slow this
            // re-buffer introduces a persistent A/V offset.  Only seek when the player has
            // drifted far from the target (e.g. the initial start-position was not applied).
            if (kotlin.math.abs(player.currentPosition - clampedTargetPositionMs) <= SEEK_DELAY_MS + 1000L) {
                return
            }
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

    private fun formatTime(timeMs: Long): String {
        if (timeMs < 0) return "00:00"
        val totalSeconds = timeMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }
}

package com.tutu.myblbl.feature.player.view

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import java.util.Locale

@OptIn(UnstableApi::class)
internal class PlayerControlTimelineCoordinator {

    private val timelineWindow = Timeline.Window()
    private var multiWindowTimeBar: Boolean = false
    private var currentWindowOffsetMs: Long = 0L
    private var totalDurationMs: Long = 0L
    private var lastRenderedPositionMs: Long = C.TIME_UNSET
    private var lastRenderedBufferedPositionMs: Long = C.TIME_UNSET
    private var lastRenderedDurationMs: Long = C.TIME_UNSET

    // Owns time-bar state so MyPlayerControlView can focus on input and visibility behavior.
    fun updateProgress(
        player: Player?,
        isVisible: Boolean,
        attachedToWindow: Boolean,
        isScrubbing: Boolean,
        renderPosition: (Long) -> Unit,
        renderDuration: (Long) -> Unit,
        renderBufferedPosition: (Long) -> Unit,
        stopUpdates: () -> Unit
    ) {
        if (player == null) {
            if (!isScrubbing) {
                renderPosition(0L)
            }
            renderDuration(0L)
            renderBufferedPosition(0L)
            stopUpdates()
            return
        }

        if (!isVisible || !attachedToWindow) {
            stopUpdates()
            return
        }

        val position = currentWindowOffsetMs + player.currentPosition
        val bufferedPosition = currentWindowOffsetMs + player.bufferedPosition
        if (!isScrubbing) {
            renderPosition(position)
        }
        renderDuration(totalDurationMs)
        renderBufferedPosition(bufferedPosition)
    }

    fun calculateProgressUpdateDelay(
        player: Player?,
        preferredDelayMs: Long,
        minUpdateIntervalMs: Int
    ): Long {
        val currentPlayer = player ?: return 1000L
        val position = currentPlayer.currentPosition
        val playbackSpeed = currentPlayer.playbackParameters.speed
        val mediaTimeUntilNextFullSecondMs = 1000L - (position % 1000L)
        val timeBarDelayMs = minOf(preferredDelayMs, mediaTimeUntilNextFullSecondMs)
        return if (playbackSpeed > 0f) {
            (timeBarDelayMs / playbackSpeed).toLong()
                .coerceIn(minUpdateIntervalMs.toLong(), 1000L)
        } else {
            1000L
        }
    }

    fun updateTimeline(
        player: Player?,
        showMultiWindowTimeBar: Boolean,
        renderDuration: (Long) -> Unit,
        updateProgress: () -> Unit
    ) {
        if (player == null) {
            currentWindowOffsetMs = 0L
            multiWindowTimeBar = false
            totalDurationMs = 0L
            renderDuration(0L)
            updateProgress()
            return
        }

        val timeline = player.currentTimeline
        multiWindowTimeBar = showMultiWindowTimeBar && canShowMultiWindowTimeBar(timeline)
        currentWindowOffsetMs = 0L

        var duration = 0L
        if (!timeline.isEmpty) {
            val currentWindowIndex = player.currentMediaItemIndex
            val firstWindowIndex = if (multiWindowTimeBar) 0 else currentWindowIndex
            val lastWindowIndex = if (multiWindowTimeBar) timeline.windowCount - 1 else currentWindowIndex
            for (windowIndex in firstWindowIndex..lastWindowIndex) {
                timeline.getWindow(windowIndex, timelineWindow)
                val windowDurationMs = timelineWindow.durationMs
                if (windowDurationMs == C.TIME_UNSET) {
                    multiWindowTimeBar = false
                    currentWindowOffsetMs = 0L
                    duration = player.duration.coerceAtLeast(0L)
                    break
                }
                if (windowIndex == currentWindowIndex) {
                    currentWindowOffsetMs = duration
                }
                duration += windowDurationMs
            }
        }

        if (!multiWindowTimeBar) {
            currentWindowOffsetMs = 0L
            duration = player.duration.coerceAtLeast(0L)
        }
        totalDurationMs = duration
        renderDuration(duration)
        updateProgress()
    }

    fun seekTo(player: Player, positionMs: Long, updateProgress: () -> Unit) {
        val timeline = player.currentTimeline
        if (!multiWindowTimeBar || timeline.isEmpty) {
            player.seekTo(positionMs)
            updateProgress()
            return
        }

        var remainingPositionMs = positionMs
        val lastWindowIndex = timeline.windowCount - 1
        for (windowIndex in 0..lastWindowIndex) {
            timeline.getWindow(windowIndex, timelineWindow)
            val windowDurationMs = timelineWindow.durationMs
            if (windowDurationMs == C.TIME_UNSET) {
                break
            }
            if (remainingPositionMs < windowDurationMs || windowIndex == lastWindowIndex) {
                player.seekTo(windowIndex, remainingPositionMs.coerceAtLeast(0L))
                updateProgress()
                return
            }
            remainingPositionMs -= windowDurationMs
        }

        player.seekTo(positionMs)
        updateProgress()
    }

    fun renderPosition(positionMs: Long, onChanged: (Long) -> Unit) {
        val sanitizedPositionMs = positionMs.coerceAtLeast(0L)
        if (lastRenderedPositionMs == sanitizedPositionMs) {
            return
        }
        lastRenderedPositionMs = sanitizedPositionMs
        onChanged(sanitizedPositionMs)
    }

    fun renderBufferedPosition(bufferedPositionMs: Long, onChanged: (Long) -> Unit) {
        val sanitizedBufferedPositionMs = bufferedPositionMs.coerceAtLeast(0L)
        if (lastRenderedBufferedPositionMs == sanitizedBufferedPositionMs) {
            return
        }
        lastRenderedBufferedPositionMs = sanitizedBufferedPositionMs
        onChanged(sanitizedBufferedPositionMs)
    }

    fun renderDuration(durationMs: Long, onChanged: (Long) -> Unit) {
        val sanitizedDurationMs = durationMs.coerceAtLeast(0L)
        if (lastRenderedDurationMs == sanitizedDurationMs) {
            return
        }
        lastRenderedDurationMs = sanitizedDurationMs
        totalDurationMs = sanitizedDurationMs
        onChanged(sanitizedDurationMs)
    }

    fun formatTime(timeMs: Long): String {
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

    private fun canShowMultiWindowTimeBar(timeline: Timeline): Boolean {
        if (timeline.windowCount > 100) {
            return false
        }
        for (windowIndex in 0 until timeline.windowCount) {
            if (timeline.getWindow(windowIndex, timelineWindow).durationMs == C.TIME_UNSET) {
                return false
            }
        }
        return true
    }
}

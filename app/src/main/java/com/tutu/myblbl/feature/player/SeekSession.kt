package com.tutu.myblbl.feature.player

import android.os.Handler
import android.os.Looper
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer

class SeekSession(
    private val coordinator: PlaybackUiCoordinator,
    private val playerProvider: () -> ExoPlayer?,
    private val seekPreviewRenderer: (targetPositionMs: Long, durationMs: Long) -> Unit,
    private val danmakuSync: (Long) -> Unit
) {
    enum class Mode {
        NONE,
        TAP,
        HOLD,
        SWIPE,
        DOUBLE_TAP,
        SPEED_MODE
    }

    private val handler = Handler(Looper.getMainLooper())
    private var mode = Mode.NONE
    private var isForward = true
    private var swipeStartPositionMs = 0L
    private var swipeTargetPositionMs = 0L
    private var seekMs = 10_000L

    private var isSpeedMode = false
    private var speedIndex = 0
    private var originalSpeed = 1f
    private var speedStepRunnable: Runnable? = null
    private var rewindTickRunnable: Runnable? = null
    private val speeds = floatArrayOf(2f, 4f, 8f, 16f, 32f)
    private val longPressThresholdMs = 300L
    private val speedStepIntervalMs = 500L
    private val rewindTickIntervalMs = 100L
    private var pendingRunnable: Runnable? = null
    var speedChangedListener: ((Boolean, Float) -> Unit)? = null

    fun startTapSeek(forward: Boolean, seekMs: Long) {
        val player = playerProvider() ?: return
        if (player.playbackState == androidx.media3.common.Player.STATE_ENDED ||
            player.playbackState == androidx.media3.common.Player.STATE_IDLE
        ) return
        if (!player.isCurrentMediaItemSeekable || player.duration <= 0L) return

        mode = Mode.TAP
        isForward = forward
        this.seekMs = seekMs
        val deltaMs = seekMs * if (forward) 1 else -1
        val targetMs = (player.currentPosition + deltaMs).coerceIn(0L, player.duration)
        player.seekTo(targetMs)
        danmakuSync(targetMs)
        coordinator.updateSeekPreview(targetMs, player.duration)
        coordinator.transition(UiEvent.SeekStarted)
        coordinator.transition(UiEvent.SeekTypeChanged(SeekType.TAP))
        seekPreviewRenderer(targetMs, player.duration)
        mode = Mode.NONE
        coordinator.transition(UiEvent.SeekFinished)
    }

    fun startHoldSeek(forward: Boolean) {
        val player = playerProvider() ?: return
        if (player.playbackState == androidx.media3.common.Player.STATE_ENDED ||
            player.playbackState == androidx.media3.common.Player.STATE_IDLE
        ) return

        mode = Mode.HOLD
        isForward = forward
        isSpeedMode = false
        speedIndex = 0

        coordinator.transition(UiEvent.SeekStarted)
        coordinator.transition(UiEvent.SeekTypeChanged(SeekType.HOLD))

        if (!forward) {
            doRewindTick()
            val pending = Runnable {
                scheduleNextRewindTick()
            }
            pendingRunnable = pending
            handler.postDelayed(pending, longPressThresholdMs)
        } else {
            originalSpeed = player.playbackParameters.speed
            val pending = Runnable {
                isSpeedMode = true
                mode = Mode.SPEED_MODE
                coordinator.transition(UiEvent.SpeedModeStarted)
                enterSpeedMode()
            }
            pendingRunnable = pending
            handler.postDelayed(pending, longPressThresholdMs)
        }
    }

    fun startSwipeSeek(startPositionMs: Long) {
        mode = Mode.SWIPE
        swipeStartPositionMs = startPositionMs
        swipeTargetPositionMs = startPositionMs

        coordinator.transition(UiEvent.SeekStarted)
        coordinator.transition(UiEvent.SeekTypeChanged(SeekType.SWIPE))
    }

    fun updateSwipeTarget(deltaX: Float, width: Float, durationMs: Long) {
        if (mode != Mode.SWIPE) return
        val offsetRatio = (deltaX / width.coerceAtLeast(1f)).coerceIn(-1f, 1f)
        swipeTargetPositionMs = (swipeStartPositionMs + (durationMs * offsetRatio).toLong())
            .coerceIn(0L, durationMs)
        coordinator.updateSeekPreview(swipeTargetPositionMs, durationMs)
        seekPreviewRenderer(swipeTargetPositionMs, durationMs)
    }

    fun commitSwipe() {
        if (mode != Mode.SWIPE) return
        val player = playerProvider() ?: return
        player.seekTo(swipeTargetPositionMs)
        danmakuSync(swipeTargetPositionMs)
        mode = Mode.NONE
        coordinator.transition(UiEvent.SeekFinished)
    }

    fun changeDirection(forward: Boolean) {
        if (mode == Mode.HOLD || mode == Mode.SPEED_MODE) {
            cancelPendingRunnable()
            cancelSpeedStepRunnable()
            cancelRewindTickRunnable()
            if (isSpeedMode && isForward) {
                val player = playerProvider()
                if (player != null) {
                    player.playbackParameters = PlaybackParameters(originalSpeed)
                }
                coordinator.transition(UiEvent.SpeedModeFinished)
            }
            isSpeedMode = false
            speedIndex = 0
            isForward = forward
            mode = Mode.HOLD
            coordinator.transition(UiEvent.SeekTypeChanged(SeekType.HOLD))
            if (!forward) {
                doRewindTick()
                val pending = Runnable {
                    scheduleNextRewindTick()
                }
                pendingRunnable = pending
                handler.postDelayed(pending, longPressThresholdMs)
            } else {
                originalSpeed = playerProvider()?.playbackParameters?.speed ?: 1f
                val pending = Runnable {
                    isSpeedMode = true
                    mode = Mode.SPEED_MODE
                    coordinator.transition(UiEvent.SpeedModeStarted)
                    enterSpeedMode()
                }
                pendingRunnable = pending
                handler.postDelayed(pending, longPressThresholdMs)
            }
        }
    }

    fun doRewindTick() {
        val player = playerProvider() ?: return
        val duration = player.duration
        if (duration <= 0L || !player.isCurrentMediaItemSeekable) return
        val deltaMs = seekMs
        val targetMs = (player.currentPosition - deltaMs).coerceAtLeast(0L)
        player.seekTo(targetMs)
        danmakuSync(targetMs)
        coordinator.updateSeekPreview(targetMs, duration)
        seekPreviewRenderer(targetMs, duration)
    }

    fun doForwardTick() {
        val player = playerProvider() ?: return
        val duration = player.duration
        if (duration <= 0L || !player.isCurrentMediaItemSeekable) return
        val deltaMs = seekMs
        val targetMs = (player.currentPosition + deltaMs).coerceIn(0L, duration)
        player.seekTo(targetMs)
        danmakuSync(targetMs)
        coordinator.updateSeekPreview(targetMs, duration)
        seekPreviewRenderer(targetMs, duration)
    }

    fun finishSeek() {
        cancelPendingRunnable()
        cancelSpeedStepRunnable()
        cancelRewindTickRunnable()
        if (isSpeedMode) {
            isSpeedMode = false
            speedIndex = 0
            val player = playerProvider()
            if (player != null && isForward) {
                player.playbackParameters = PlaybackParameters(originalSpeed)
            }
            coordinator.transition(UiEvent.SpeedModeFinished)
        } else if (mode == Mode.HOLD && isForward) {
            doForwardTick()
        }
        mode = Mode.NONE
        coordinator.transition(UiEvent.SeekFinished)
    }

    fun cancel() {
        cancelPendingRunnable()
        cancelSpeedStepRunnable()
        cancelRewindTickRunnable()
        isSpeedMode = false
        speedIndex = 0
        if (mode == Mode.SPEED_MODE && isForward) {
            val player = playerProvider()
            if (player != null) {
                player.playbackParameters = PlaybackParameters(originalSpeed)
            }
            coordinator.transition(UiEvent.SpeedModeFinished)
        }
        mode = Mode.NONE
        coordinator.transition(UiEvent.SeekCancelled)
    }

    fun isActive(): Boolean = mode != Mode.NONE

    fun isSeeking(): Boolean = mode != Mode.NONE

    fun isInSpeedMode(): Boolean = mode == Mode.SPEED_MODE

    fun isForwardDirection(): Boolean = isForward

    private fun enterSpeedMode() {
        val player = playerProvider() ?: return
        if (!player.isPlaying) player.play()
        player.playbackParameters = PlaybackParameters(speeds[0])
        speedChangedListener?.invoke(isForward, speeds[0])
        scheduleNextSpeedStep()
    }

    private fun scheduleNextSpeedStep() {
        cancelSpeedStepRunnable()
        if (speedIndex >= speeds.size - 1) return
        val runnable = Runnable {
            speedIndex++
            val speed = speeds[speedIndex]
            playerProvider()?.playbackParameters = PlaybackParameters(speed)
            speedChangedListener?.invoke(isForward, speed)
            scheduleNextSpeedStep()
        }
        speedStepRunnable = runnable
        handler.postDelayed(runnable, speedStepIntervalMs)
    }

    private fun scheduleNextRewindTick() {
        cancelRewindTickRunnable()
        val runnable = Runnable {
            if (mode == Mode.HOLD && !isForward) {
                doRewindTick()
                scheduleNextRewindTick()
            }
        }
        rewindTickRunnable = runnable
        handler.postDelayed(runnable, rewindTickIntervalMs)
    }

    private fun cancelPendingRunnable() {
        pendingRunnable?.let { handler.removeCallbacks(it) }
        pendingRunnable = null
    }

    private fun cancelSpeedStepRunnable() {
        speedStepRunnable?.let { handler.removeCallbacks(it) }
        speedStepRunnable = null
    }

    private fun cancelRewindTickRunnable() {
        rewindTickRunnable?.let { handler.removeCallbacks(it) }
        rewindTickRunnable = null
    }
}

package com.tutu.myblbl.feature.player.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import androidx.media3.common.Player
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.tutu.myblbl.R
import com.tutu.myblbl.model.player.VideoSnapshotData
import com.tutu.myblbl.network.NetworkManager
import com.tutu.myblbl.core.common.format.NumberUtils
import com.tutu.myblbl.feature.player.PlaybackUiCoordinator
import com.tutu.myblbl.feature.player.UiEvent
import kotlin.math.abs

class YouTubeOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    enum class DisplayMode {
        EXCLUSIVE,
        COEXISTING
    }

    private enum class IndicatorLayoutMode {
        START,
        END,
        CENTER
    }

    private val rootConstraintLayout: ConstraintLayout
    private val secondsView: SecondsView
    private val circleClipTapView: CircleClipTapView

    private var player: Player? = null
    private var playerView: MyPlayerView? = null
    private var callback: Callback? = null
    private var uiCoordinator: PlaybackUiCoordinator? = null
    private var overlayShowing = false
    private var activeDisplayMode = DisplayMode.EXCLUSIVE
    private var layoutMode = IndicatorLayoutMode.CENTER
    private var previewSnapshot: VideoSnapshotData? = null
    private var swipePreviewActive = false
    private var showBottomProgress = true
    private var persistentBottomProgressEnabled = false
    private var lastSwipeTargetPositionMs = 0L
    private var currentPreviewFrameKey: String? = null
    private var lastQuantizedFrameMs: Long = -1L
    private var previewRequestToken = 0
    private var currentPreviewTarget: CustomTarget<Bitmap>? = null
    private val previewBitmapCache = object : LruCache<String, Bitmap>(24) {}

    private val hideOverlayRunnable = Runnable {
        hideOverlayImmediately()
    }

    var seekSeconds: Int = 10
        set(value) {
            field = value
            secondsView.let {
                // Bound to controller preferences; text rendering is handled on demand.
            }
        }

    interface Callback {
        fun onAnimationStart(displayMode: DisplayMode)
        fun onAnimationEnd(displayMode: DisplayMode)
        fun shouldForward(player: Player, playerView: MyPlayerView, x: Float): Boolean?
    }

    fun setUiCoordinator(coordinator: PlaybackUiCoordinator?) {
        uiCoordinator = coordinator
    }

    init {
        val view = LayoutInflater.from(context).inflate(R.layout.yt_overlay, this, true)
        rootConstraintLayout = view.findViewById(R.id.root_constraint_layout)
        secondsView = view.findViewById(R.id.seconds_view)
        circleClipTapView = view.findViewById(R.id.circle_clip_tap_view)

        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.YouTubeOverlay, 0, 0)
            circleClipTapView.setAnimationDuration(
                a.getInt(R.styleable.YouTubeOverlay_yt_animationDuration, 650).toLong()
            )
            seekSeconds = a.getInt(R.styleable.YouTubeOverlay_yt_seekSeconds, 10)
            secondsView.setCycleDuration(
                a.getInt(R.styleable.YouTubeOverlay_yt_iconAnimationDuration, 750).toLong()
            )
            circleClipTapView.setArcSize(
                a.getDimensionPixelSize(
                    R.styleable.YouTubeOverlay_yt_arcSize,
                    context.resources.getDimensionPixelSize(R.dimen.px100)
                ).toFloat()
            )
            circleClipTapView.setCircleColor(
                a.getColor(
                    R.styleable.YouTubeOverlay_yt_tapCircleColor,
                    ContextCompat.getColor(context, R.color.dtpv_yt_tap_circle_color)
                )
            )
            circleClipTapView.setCircleBackgroundColor(
                a.getColor(
                    R.styleable.YouTubeOverlay_yt_backgroundCircleColor,
                    ContextCompat.getColor(context, R.color.dtpv_yt_background_circle_color)
                )
            )
            val textAppearance =
                a.getResourceId(R.styleable.YouTubeOverlay_yt_textAppearance, R.style.YTOSecondsTextAppearance)
            TextViewCompat.setTextAppearance(secondsView.getTextView(), textAppearance)
            val iconRes = a.getResourceId(R.styleable.YouTubeOverlay_yt_icon, R.drawable.ic_play_triangle)
            secondsView.m()
            secondsView.setIcon(iconRes)
            a.recycle()
        } else {
            circleClipTapView.setArcSize(context.resources.getDimensionPixelSize(R.dimen.px100).toFloat())
            circleClipTapView.setCircleColor(ContextCompat.getColor(context, R.color.dtpv_yt_tap_circle_color))
            circleClipTapView.setCircleBackgroundColor(
                ContextCompat.getColor(context, R.color.dtpv_yt_background_circle_color)
            )
            circleClipTapView.setAnimationDuration(650L)
            secondsView.setCycleDuration(750L)
            seekSeconds = 10
            TextViewCompat.setTextAppearance(secondsView.getTextView(), R.style.YTOSecondsTextAppearance)
        }

        secondsView.setForward(true)
        applyLayoutMode(IndicatorLayoutMode.CENTER)
        circleClipTapView.setPerformAtEnd(null)
        circleClipTapView.visibility = View.INVISIBLE
    }

    fun setPlayer(player: Player?) {
        this.player = player
    }

    fun isOverlayShowing(): Boolean = overlayShowing

    fun setPlayerView(playerView: MyPlayerView?) {
        this.playerView = playerView
    }

    fun setCallback(callback: Callback?) {
        this.callback = callback
    }

    fun setSeekPreviewSnapshot(snapshot: VideoSnapshotData?) {
        previewSnapshot = snapshot
        if (snapshot == null) {
            cancelPreviewRequest(resetFrameKey = true)
        } else if (swipePreviewActive && overlayShowing) {
            requestSwipePreview(lastSwipeTargetPositionMs)
        }
    }

    fun setPersistentBottomProgressEnabled(enabled: Boolean) {
        persistentBottomProgressEnabled = enabled
    }

    fun setIconDrawables(
        @Suppress("UNUSED_PARAMETER") forward: Drawable?,
        @Suppress("UNUSED_PARAMETER") rewind: Drawable?
    ) {
    }

    fun show(forward: Boolean, x: Float, y: Float) {
        swipePreviewActive = false
        cancelPreviewRequest(resetFrameKey = true)
        removeCallbacks(hideOverlayRunnable)
        ensureOverlayVisible(
            displayMode = DisplayMode.EXCLUSIVE,
            showBottomProgress = !persistentBottomProgressEnabled
        )
        secondsView.hidePreview()
        secondsView.cancel()
        secondsView.setForward(forward)
        secondsView.x = x - secondsView.width / 2
        secondsView.y = y - secondsView.height / 2
        secondsView.visibility = View.VISIBLE
        secondsView.start()

        seekTo(forward)
        scheduleHide()
    }

    private fun seekTo(forward: Boolean) {
        val currentPlayer = player ?: return
        val currentPos = currentPlayer.currentPosition
        val seekAmount = if (forward) seekSeconds * 1000L else -seekSeconds * 1000L
        val newPos = (currentPos + seekAmount).coerceIn(0L, currentPlayer.duration)
        currentPlayer.seekTo(newPos)
        playerView?.syncDanmakuPosition(newPos, forceSeek = true)
        updateProgress(newPos, currentPlayer.duration)
    }

    fun handleDoubleTap(x: Float, y: Float) {
        val currentPlayer = player ?: return
        val currentPlayerView = playerView ?: return

        val shouldForward = callback?.shouldForward(currentPlayer, currentPlayerView, x) ?: return
        val wasShowing = overlayShowing
        swipePreviewActive = false
        cancelPreviewRequest(resetFrameKey = true)
        ensureOverlayVisible(
            displayMode = DisplayMode.EXCLUSIVE,
            showBottomProgress = !persistentBottomProgressEnabled
        )

        val isRewind = !shouldForward
        val directionChanged = secondsView.isForward != shouldForward
        if (!wasShowing || directionChanged || secondsView.visibility != View.VISIBLE) {
            startIndicatorAnimation(
                forward = shouldForward,
                layout = if (shouldForward) IndicatorLayoutMode.END else IndicatorLayoutMode.START
            )
            secondsView.setSeekText(0)
        } else {
            secondsView.hidePreview()
        }

        circleClipTapView.animate {
            circleClipTapView.setTapPosition(x, y)
        }

        val targetPositionMs = if (isRewind) {
            (currentPlayer.currentPosition - (seekSeconds * 1000L)).coerceAtLeast(0L)
        } else {
            (currentPlayer.currentPosition + (seekSeconds * 1000L))
                .coerceAtMost(currentPlayer.duration.coerceAtLeast(0L))
        }
        val progressText = NumberUtils.formatDuration(targetPositionMs / 1000L)
        secondsView.setSeekText(secondsView.getSeconds() + seekSeconds, progressText)
        seekWithinDoubleTap(targetPositionMs)

        updateProgress(targetPositionMs, currentPlayer.duration)
    }

    private fun seekWithinDoubleTap(positionMs: Long) {
        val currentPlayer = player ?: return
        val boundedPositionMs = positionMs.coerceIn(0L, currentPlayer.duration.coerceAtLeast(0L))
        playerView?.keepInDoubleTapMode()
        currentPlayer.seekTo(boundedPositionMs)
        playerView?.syncDanmakuPosition(boundedPositionMs, forceSeek = true)
    }

    fun showSwipeSeek(
        targetPositionMs: Long,
        durationMs: Long,
        deltaMs: Long,
        showBottomProgress: Boolean,
        showThumbnails: Boolean = true,
        seekSeconds: Int = 0
    ) {
        val forward = deltaMs >= 0L
        val directionChanged = secondsView.isForward != forward
        val wasShowing = overlayShowing

        swipePreviewActive = showThumbnails
        lastSwipeTargetPositionMs = targetPositionMs
        removeCallbacks(hideOverlayRunnable)
        ensureOverlayVisible(
            displayMode = DisplayMode.COEXISTING,
            showBottomProgress = showBottomProgress
        )
        circleClipTapView.cancelAndHide()
        resetSecondsViewPosition()

        if (!wasShowing || directionChanged || secondsView.visibility != View.VISIBLE) {
            startIndicatorAnimation(forward = forward, layout = IndicatorLayoutMode.CENTER)
        } else {
            applyLayoutMode(IndicatorLayoutMode.CENTER)
            secondsView.setForward(forward)
        }

        secondsView.visibility = View.VISIBLE
        if (seekSeconds > 0) {
            secondsView.setSeekText(seekSeconds)
        } else {
            secondsView.setDurationText(
                targetText = NumberUtils.formatDuration(targetPositionMs / 1000L),
                totalText = NumberUtils.formatDuration(durationMs / 1000L)
            )
        }
        updateProgress(targetPositionMs, durationMs)
        if (showThumbnails) {
            requestSwipePreview(targetPositionMs)
        } else {
            cancelPreviewRequest(resetFrameKey = true)
        }
    }

    /**
     * DO NOT CALL: dead code. Only use if explicitly requested.
     * Shows left/right-biased circle clip animation with seek text.
     * Replaced by showSwipeSeek (centered arrow, no circle clip) for all current seek paths.
     */
    fun showControllerSeek(
        targetPositionMs: Long,
        durationMs: Long,
        deltaMs: Long,
        showBottomProgress: Boolean = true,
        isCumulative: Boolean = false
    ) {
        val forward = deltaMs >= 0L
        val directionChanged = secondsView.isForward != forward
        val wasShowing = overlayShowing

        swipePreviewActive = false
        cancelPreviewRequest(resetFrameKey = true)
        removeCallbacks(hideOverlayRunnable)
        ensureOverlayVisible(
            displayMode = DisplayMode.EXCLUSIVE,
            showBottomProgress = showBottomProgress
        )
        resetSecondsViewPosition()

        if (!wasShowing || directionChanged || secondsView.visibility != View.VISIBLE) {
            startIndicatorAnimation(
                forward = forward,
                layout = if (forward) IndicatorLayoutMode.END else IndicatorLayoutMode.START
            )
            secondsView.setSeekText(0)
        } else {
            secondsView.hidePreview()
        }

        val refWidth = width.takeIf { it > 0 } ?: playerView?.width ?: width
        val refHeight = height.takeIf { it > 0 } ?: playerView?.height ?: height
        val overlayX = if (forward) refWidth * 0.7f else refWidth * 0.3f
        val overlayY = refHeight / 2f
        circleClipTapView.animate {
            circleClipTapView.setTapPosition(overlayX, overlayY)
        }

        secondsView.visibility = View.VISIBLE
        val deltaSeconds = abs(deltaMs / 1000L).toInt().coerceAtLeast(1)
        val displaySeconds = if (isCumulative || !wasShowing || directionChanged) {
            deltaSeconds
        } else {
            secondsView.getSeconds() + deltaSeconds
        }
        secondsView.setSeekText(displaySeconds)
        updateProgress(targetPositionMs, durationMs)
        scheduleHide(minIndicatorDisplayDurationMs())
    }

    fun finishSwipeSeek() {
        circleClipTapView.cancelAndHide()
        scheduleHide(180L)
    }

    fun finishControllerSeek() {
        scheduleHide(minIndicatorDisplayDurationMs())
    }

    /**
     * 延长 overlay 可见时间，不改变内容，仅重置 auto-hide 定时器。
     * 用于长按快进等场景，保持 overlay 不被自动隐藏。
     */
    fun extendOverlayVisibility() {
        if (overlayShowing) {
            removeCallbacks(hideOverlayRunnable)
            scheduleHide(minIndicatorDisplayDurationMs())
        }
    }

    fun showSpeedSeek(forward: Boolean, speed: Float) {
        val directionChanged = secondsView.isForward != forward
        val wasShowing = overlayShowing

        swipePreviewActive = false
        cancelPreviewRequest(resetFrameKey = true)
        removeCallbacks(hideOverlayRunnable)
        ensureOverlayVisible(
            displayMode = DisplayMode.EXCLUSIVE,
            showBottomProgress = !persistentBottomProgressEnabled
        )
        resetSecondsViewPosition()

        if (!wasShowing || directionChanged || secondsView.visibility != View.VISIBLE) {
            startIndicatorAnimation(
                forward = forward,
                layout = if (forward) IndicatorLayoutMode.END else IndicatorLayoutMode.START
            )
        } else {
            secondsView.hidePreview()
        }

        secondsView.visibility = View.VISIBLE
        secondsView.setSpeedText(speed)
        val refWidth = width.takeIf { it > 0 } ?: playerView?.width ?: width
        val refHeight = height.takeIf { it > 0 } ?: playerView?.height ?: height
        val overlayX = if (forward) refWidth * 0.7f else refWidth * 0.3f
        circleClipTapView.animate {
            circleClipTapView.setTapPosition(overlayX, refHeight / 2f)
        }
        val currentPlayer = player ?: return
        updateProgress(currentPlayer.currentPosition, currentPlayer.duration)
    }

    fun finishSpeedSeek() {
        hideOverlayImmediately()
    }

    fun cancelSwipeSeek() {
        circleClipTapView.cancelAndHide()
        hideOverlayImmediately(sendSeekCancelled = true)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(hideOverlayRunnable)
        cancelPreviewRequest(resetFrameKey = true)
        previewBitmapCache.evictAll()
        secondsView.cancel()
        super.onDetachedFromWindow()
    }

    private fun requestSwipePreview(targetPositionMs: Long) {
        val snapshot = previewSnapshot ?: run {
            showPreviewLoadingIndicator()
            return
        }

        val quantizedMs = snapshot.quantizeToFrameMs(targetPositionMs)
        if (quantizedMs != null && quantizedMs == lastQuantizedFrameMs) {
            return
        }
        if (quantizedMs != null) {
            lastQuantizedFrameMs = quantizedMs
        }

        val frame = snapshot.resolveFrame(targetPositionMs) ?: run {
            showPreviewLoadingIndicator()
            return
        }
        if (frame.cacheKey == currentPreviewFrameKey) {
            return
        }
        currentPreviewFrameKey = frame.cacheKey

        previewBitmapCache.get(frame.cacheKey)?.let { cachedBitmap ->
            secondsView.showPreviewBitmap(cachedBitmap)
            return
        }

        if (!secondsView.hasPreviewBitmap()) {
            showPreviewLoadingIndicator()
        }
        val requestToken = ++previewRequestToken
        currentPreviewTarget?.let { Glide.with(this).clear(it) }
        currentPreviewTarget = object : CustomTarget<Bitmap>() {
            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                if (requestToken != previewRequestToken || !swipePreviewActive) {
                    return
                }
                val croppedBitmap = cropFrameBitmap(resource, frame)
                previewBitmapCache.put(frame.cacheKey, croppedBitmap)
                secondsView.showPreviewBitmap(croppedBitmap)
            }

            override fun onLoadCleared(placeholder: Drawable?) = Unit

            override fun onLoadFailed(errorDrawable: Drawable?) {
                if (requestToken == previewRequestToken && swipePreviewActive && !secondsView.hasPreviewBitmap()) {
                    showPreviewLoadingIndicator()
                }
            }
        }

        Glide.with(this)
            .asBitmap()
            .load(buildImageModel(frame.imageUrl))
            .into(currentPreviewTarget!!)
    }

    private fun cropFrameBitmap(source: Bitmap, frame: VideoSnapshotData.Frame): Bitmap {
        val boundedX = frame.offsetX.coerceIn(0, (source.width - 1).coerceAtLeast(0))
        val boundedY = frame.offsetY.coerceIn(0, (source.height - 1).coerceAtLeast(0))
        val boundedWidth = frame.width.coerceAtMost(source.width - boundedX).coerceAtLeast(1)
        val boundedHeight = frame.height.coerceAtMost(source.height - boundedY).coerceAtLeast(1)
        return Bitmap.createBitmap(source, boundedX, boundedY, boundedWidth, boundedHeight)
    }

    private fun showPreviewLoadingIndicator() {
        secondsView.showPreviewLoading()
        secondsView.ensureIndicatorLoop()
    }

    private fun cancelPreviewRequest(resetFrameKey: Boolean) {
        previewRequestToken += 1
        currentPreviewTarget?.let { Glide.with(this).clear(it) }
        currentPreviewTarget = null
        if (resetFrameKey) {
            currentPreviewFrameKey = null
            lastQuantizedFrameMs = -1L
        }
        secondsView.hidePreview()
    }

    private fun ensureOverlayVisible(displayMode: DisplayMode, showBottomProgress: Boolean) {
        this.showBottomProgress = showBottomProgress
        if (!overlayShowing) {
            uiCoordinator?.transition(UiEvent.SeekStarted)
        }
        if (overlayShowing) {
            if (activeDisplayMode != displayMode) {
                callback?.onAnimationEnd(activeDisplayMode)
                activeDisplayMode = displayMode
                callback?.onAnimationStart(activeDisplayMode)
            }
            visibility = View.VISIBLE
            return
        }
        overlayShowing = true
        activeDisplayMode = displayMode
        visibility = View.VISIBLE
        callback?.onAnimationStart(activeDisplayMode)
    }

    private fun hideOverlayImmediately(sendSeekCancelled: Boolean = false) {
        if (!overlayShowing) {
            return
        }
        val previousDisplayMode = activeDisplayMode
        swipePreviewActive = false
        overlayShowing = false
        showBottomProgress = true
        cancelPreviewRequest(resetFrameKey = true)
        circleClipTapView.cancelAndHide()
        secondsView.cancel()
        secondsView.setSeekText(0)
        secondsView.visibility = View.INVISIBLE
        visibility = View.GONE
        callback?.onAnimationEnd(previousDisplayMode)
        uiCoordinator?.transition(if (sendSeekCancelled) UiEvent.SeekCancelled else UiEvent.SeekFinished)
    }

    private fun scheduleHide(delayMs: Long = 650L) {
        removeCallbacks(hideOverlayRunnable)
        postDelayed(hideOverlayRunnable, delayMs)
    }

    private fun minIndicatorDisplayDurationMs(): Long {
        return secondsView.getCycleDuration().coerceAtLeast(650L) + 100L
    }

    private fun updateProgress(positionMs: Long, durationMs: Long) {
        uiCoordinator?.updateSeekPreview(positionMs, durationMs)
    }

    private fun resetSecondsViewPosition() {
        secondsView.translationX = 0f
        secondsView.translationY = 0f
    }

    private fun startIndicatorAnimation(forward: Boolean, layout: IndicatorLayoutMode) {
        applyLayoutMode(layout)
        secondsView.hidePreview()
        secondsView.setForward(forward)
        secondsView.visibility = View.VISIBLE
        secondsView.restartIndicatorLoop()
    }

    private fun applyLayoutMode(mode: IndicatorLayoutMode) {
        if (layoutMode == mode) {
            return
        }
        layoutMode = mode
        val constraintSet = ConstraintSet()
        constraintSet.clone(rootConstraintLayout)
        constraintSet.clear(secondsView.id, ConstraintSet.START)
        constraintSet.clear(secondsView.id, ConstraintSet.END)
        when (mode) {
            IndicatorLayoutMode.START -> {
                constraintSet.connect(secondsView.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
                constraintSet.connect(secondsView.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
                constraintSet.setHorizontalBias(secondsView.id, 0.3f)
            }

            IndicatorLayoutMode.END -> {
                constraintSet.connect(secondsView.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
                constraintSet.connect(secondsView.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
                constraintSet.setHorizontalBias(secondsView.id, 0.7f)
            }

            IndicatorLayoutMode.CENTER -> {
                constraintSet.connect(secondsView.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
                constraintSet.connect(secondsView.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
                constraintSet.setHorizontalBias(secondsView.id, 0.5f)
            }
        }
        constraintSet.applyTo(rootConstraintLayout)
        rootConstraintLayout.requestLayout()
    }

    private fun buildImageModel(url: String): Any {
        return GlideUrl(
            url,
            LazyHeaders.Builder()
                .addHeader("Referer", "https://www.bilibili.com/")
                .addHeader("User-Agent", NetworkManager.getCurrentUserAgent())
                .build()
        )
    }
}

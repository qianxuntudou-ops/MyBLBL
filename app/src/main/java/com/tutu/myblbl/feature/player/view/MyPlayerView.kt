package com.tutu.myblbl.feature.player.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import com.kuaishou.akdanmaku.ui.DanmakuView
import com.tutu.myblbl.R
import com.tutu.myblbl.model.dm.DmModel
import com.tutu.myblbl.model.dm.SpecialDanmakuModel
import com.tutu.myblbl.model.player.VideoSnapshotData
import com.tutu.myblbl.model.subtitle.SubtitleInfoModel
import com.tutu.myblbl.model.video.quality.AudioQuality
import com.tutu.myblbl.model.video.quality.VideoCodecEnum
import com.tutu.myblbl.model.video.quality.VideoQuality
import com.tutu.myblbl.core.common.ext.isAdvancedDanmakuEnabled
import com.tutu.myblbl.core.common.ext.getDanmakuSmartFilterLevel
import com.tutu.myblbl.feature.player.LiveQualityInfo

@OptIn(UnstableApi::class)
class MyPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        const val SHOW_BUFFERING_NEVER = 0
        const val SHOW_BUFFERING_WHEN_PLAYING = 1
        const val SHOW_BUFFERING_ALWAYS = 2

        private const val SURFACE_TYPE_SURFACE_VIEW = 1
        private const val SURFACE_TYPE_TEXTURE_VIEW = 2
        private const val KEYCODE_SYSTEM_NAVIGATION_UP_COMPAT = 280
        private const val KEYCODE_SYSTEM_NAVIGATION_DOWN_COMPAT = 281
        private const val KEYCODE_SYSTEM_NAVIGATION_LEFT_COMPAT = 282
        private const val KEYCODE_SYSTEM_NAVIGATION_RIGHT_COMPAT = 283
        private const val STARTUP_BUFFERING_INDICATOR_DELAY_MS = 700L
        private const val REBUFFER_BUFFERING_INDICATOR_DELAY_MS = 150L
        private const val SHUTTER_FADE_DURATION_MS = 180L
        private const val SWIPE_SEEK_PREVIEW_CLEAR_DELAY_MS = 180L
        private const val DISCRETE_SEEK_PREVIEW_CLEAR_DELAY_MS = 900L
    }

    private var contentFrame: AspectRatioFrameLayout? = null
    private var shutterView: View? = null
    private var bufferingView: ProgressBar? = null
    private var errorMessageView: TextView? = null
    private var videoSurfaceView: View? = null

    private var controller: MyPlayerControlView? = null
    private var settingView: MyPlayerSettingView? = null
    private var tapOverlayView: YouTubeOverlay? = null
    private var dmkView: DanmakuView? = null
    private var specialDmkOverlayView: SpecialDanmakuOverlayView? = null

    private var player: ExoPlayer? = null
    private var showBuffering: Int = SHOW_BUFFERING_WHEN_PLAYING
    private var controllerShowTimeoutMs: Int = MyPlayerControlView.DEFAULT_SHOW_TIMEOUT_MS
    private var controllerHideOnTouch: Boolean = true
    private var controllerAutoShow: Boolean = false
    private var useController: Boolean = true
    private var isDoubleTapEnabled: Boolean = true
    private var keepContentOnPlayerReset: Boolean = false
    private var customErrorMessage: CharSequence? = null

    private var controllerVisibilityListener: ControllerVisibilityListener? = null

    private var touchInterceptListener: ((MotionEvent) -> Boolean)? = null

    private val controllerComponentListener = object : MyPlayerControlView.VisibilityListener {
        override fun onVisibilityChange(visibility: Int) {
            if (visibility == View.VISIBLE) {
                clearHiddenSeekPreview()
            }
            updateContentDescription()
            controllerVisibilityListener?.onVisibilityChanged(visibility)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val bufferingIndicatorRunnable = Runnable {
        val buffering = bufferingView ?: return@Runnable
        val currentPlayer = player ?: run {
            buffering.visibility = GONE
            return@Runnable
        }
        buffering.visibility = if (shouldShowBufferingIndicator(currentPlayer)) VISIBLE else GONE
    }
    private val gestureListener = PlayerDoubleTapGestureListener(this)
    private val gestureDetector = GestureDetector(context, gestureListener)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    // Encapsulates danmaku config, lifecycle and data transforms so this view stays focused on UI.
    private val danmakuController = MyPlayerDanmakuController(
        context = context,
        danmakuViewProvider = { dmkView }
    )
    private val specialDanmakuController = MyPlayerSpecialDanmakuController(
        overlayViewProvider = { specialDmkOverlayView }
    )
    private var downTouchX = 0f
    private var downTouchY = 0f
    private var isSwipeSeeking = false
    private var swipeSeekUsesControllerPreview = false
    private var swipeSeekStartPositionMs = 0L
    private var swipeSeekTargetPositionMs = 0L
    private var hasRenderedFirstFrame = false
    private var persistentBottomProgressEnabled = false
    private var hiddenSeekPreviewActive = false
    private var hiddenSeekPreviewPositionMs = 0L
    private var hiddenSeekPreviewDurationMs = 0L

    private val progressiveSeekHelper = ProgressiveSeekHelper()
    private val clearHiddenSeekPreviewRunnable = Runnable {
        dispatchSeekPreviewState(active = false, positionMs = 0L, durationMs = 0L)
    }

    interface ControllerVisibilityListener {
        fun onVisibilityChanged(visibility: Int)
    }

    interface RenderEventListener {
        fun onRenderedFirstFrame()
    }

    interface SeekPreviewListener {
        fun onSeekPreviewStateChanged(active: Boolean, positionMs: Long, durationMs: Long)
    }

    var onResumeProgressCancelled: (() -> Boolean)? = null
    private var renderEventListener: RenderEventListener? = null
    private var seekPreviewListener: SeekPreviewListener? = null

    private val componentListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            updateBuffering()
            updateErrorMessage()
            if (events.contains(Player.EVENT_PLAYBACK_PARAMETERS_CHANGED)) {
                val speed = player.playbackParameters.speed
                settingView?.setCurrentSpeed(speed)
                danmakuController.updatePlaybackSpeed(speed)
                specialDanmakuController.updatePlaybackSpeed(speed)
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            updateBuffering()
            updateControllerVisibility()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            updateBuffering()
            updateErrorMessage()
            updateControllerVisibility()
        }

        override fun onPlayerError(error: PlaybackException) {
            updateErrorMessage()
        }

        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            updateAspectRatio()
        }

        override fun onRenderedFirstFrame() {
            hasRenderedFirstFrame = true
            updateBuffering()
            shutterView?.animate()?.cancel()
            shutterView?.animate()
                ?.alpha(0f)
                ?.setDuration(SHUTTER_FADE_DURATION_MS)
                ?.withEndAction {
                    if (hasRenderedFirstFrame) {
                        shutterView?.visibility = INVISIBLE
                    }
                }
                ?.start()
            renderEventListener?.onRenderedFirstFrame()
        }
    }

    init {
        LayoutInflater.from(context).inflate(R.layout.my_exo_styled_player_view, this)
        descendantFocusability = FOCUS_AFTER_DESCENDANTS

        contentFrame = findViewById(R.id.exo_content_frame)
        shutterView = findViewById(R.id.exo_shutter)
        bufferingView = findViewById(R.id.exo_buffering)
        errorMessageView = findViewById(R.id.exo_error_message)
        dmkView = findViewById(R.id.dmk_view)
        specialDmkOverlayView = findViewById(R.id.special_dmk_overlay)

        setupSurfaceView()

        bufferingView?.visibility = GONE
        errorMessageView?.visibility = GONE

        setupController()
        setupSettingView()
        setupYouTubeOverlay()

        isClickable = true
        isFocusable = false
        descendantFocusability = FOCUS_AFTER_DESCENDANTS

        isDoubleTapEnabled = true
    }

    private fun setupSurfaceView() {
        contentFrame?.let { frame ->
            val layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            val surfaceView = SurfaceView(context)
            surfaceView.layoutParams = layoutParams
            videoSurfaceView = surfaceView
            frame.addView(surfaceView, 0)
        }
    }

    private fun setupController() {
        val placeholder: View? = findViewById(R.id.exo_controller_placeholder)
        placeholder?.let { ph ->
            controller = MyPlayerControlView(context)
            controller?.id = R.id.exo_controller
            controller?.layoutParams = ph.layoutParams
            controller?.descendantFocusability = FOCUS_AFTER_DESCENDANTS

            val parent = ph.parent as ViewGroup
            val index = parent.indexOfChild(ph)
            parent.descendantFocusability = FOCUS_AFTER_DESCENDANTS
            parent.removeView(ph)
            parent.addView(controller, index)

            controller?.setOnMenuShowImpl(object : OnMenuShowImpl {
                override fun onShowHide(isShowing: Boolean) {
                    showHideSettingView(isShowing)
                }
            })

            controller?.setOnDmEnableChangeImpl(object : OnDmEnableChangeImpl {
                override fun onEnable() {
                    settingView?.dmEnableClick()
                }
            })
            controller?.setOnSeekCommitListener { positionMs ->
                syncDanmakuPosition(positionMs, forceSeek = true)
            }
            controller?.setProgressOnlyUiEnabled(!persistentBottomProgressEnabled)
            controller?.addVisibilityListener(controllerComponentListener)
        }

        controllerShowTimeoutMs = if (controller != null) {
            MyPlayerControlView.DEFAULT_SHOW_TIMEOUT_MS
        } else {
            0
        }
        controller?.hideImmediately()
        useController = controller != null
        updateContentDescription()
    }

    private fun setupSettingView() {
        settingView = findViewById(R.id.setting_view)
        settingView?.setOnVisibilityStateChangedListener { isShowing ->
            if (isShowing) {
                controller?.removeHideCallbacks()
            } else if (controller?.isFullyVisible() == true) {
                controller?.restoreRememberedFocus()
                controller?.resetHideCallbacks()
            }
        }
        settingView?.setOnPlayerSettingInnerChange(object : OnPlayerSettingInnerChange {
            override fun onAspectRatioChange(ratio: Int) {
                setResizeMode(ratio)
            }

            override fun onDmEnableChange(enabled: Boolean) {
                controller?.dmEnableButtonChange(enabled)
                danmakuController.setEnabled(enabled)
            }

            override fun onPlaybackSpeedChange(speed: Float) {
                player?.playbackParameters = PlaybackParameters(speed)
                danmakuController.updatePlaybackSpeed(speed)
                specialDanmakuController.updatePlaybackSpeed(speed)
            }

            override fun onDmAlpha(alpha: Float) {
                syncDanmakuSettings()
            }

            override fun onDmTextSize(size: Int) {
                syncDanmakuSettings()
            }

            override fun onDmScreenArea(area: Int) {
                syncDanmakuSettings()
            }

            override fun onDmSpeed(speed: Int) {
                syncDanmakuSettings()
            }

            override fun onDmAllowTop(allow: Boolean) {
                syncDanmakuSettings()
            }

            override fun onDmAllowBottom(allow: Boolean) {
                syncDanmakuSettings()
            }

            override fun onDmMergeDuplicate(merge: Boolean) {
                syncDanmakuSettings()
            }
        })
        syncDanmakuSettings()
    }

    private fun setupYouTubeOverlay() {
        tapOverlayView = findViewById(R.id.view_youtube_overlay)
        tapOverlayView?.setPlayerView(this)
        tapOverlayView?.setPersistentBottomProgressEnabled(persistentBottomProgressEnabled)
        tapOverlayView?.setCallback(object : YouTubeOverlay.Callback {
            override fun onAnimationStart(displayMode: YouTubeOverlay.DisplayMode) {
                if (displayMode == YouTubeOverlay.DisplayMode.EXCLUSIVE) {
                    setUseController(false)
                }
            }

            override fun onAnimationEnd(displayMode: YouTubeOverlay.DisplayMode) {
                if (displayMode == YouTubeOverlay.DisplayMode.EXCLUSIVE) {
                    setUseController(true)
                }
            }

            override fun shouldForward(player: Player, playerView: MyPlayerView, x: Float): Boolean? {
                val currentPosition = player.currentPosition
                val duration = player.duration
                if (
                    player.playbackState == Player.STATE_ENDED ||
                    player.playbackState == Player.STATE_IDLE ||
                    player.playbackState == Player.STATE_BUFFERING
                ) {
                    playerView.cancelInDoubleTapMode()
                    return null
                }
                if (currentPosition > 500 && x < playerView.width * 0.35) {
                    return false
                }
                if (currentPosition >= duration || x <= playerView.width * 0.65) {
                    return null
                }
                return true
            }
        })
        gestureListener.setCallback { _, _ ->
            togglePlaybackByDoubleTap()
        }
    }

    fun setPlayer(player: ExoPlayer?) {
        val previousPlayer = this.player
        previousPlayer?.removeListener(componentListener)
        when (val surfaceView = videoSurfaceView) {
            is SurfaceView -> previousPlayer?.clearVideoSurfaceView(surfaceView)
            is TextureView -> previousPlayer?.clearVideoTextureView(surfaceView)
        }
        if (!keepContentOnPlayerReset) {
            closeShutter()
        }
        this.player = player
        player?.addListener(componentListener)
        when (val surfaceView = videoSurfaceView) {
            is SurfaceView -> player?.setVideoSurfaceView(surfaceView)
            is TextureView -> player?.setVideoTextureView(surfaceView)
        }

        controller?.setPlayer(player)
        controller?.setRepeatMode(player?.repeatMode ?: Player.REPEAT_MODE_OFF)
        tapOverlayView?.setPlayer(player)

        updateBuffering()
        updateErrorMessage()
    }

    private fun closeShutter() {
        hasRenderedFirstFrame = false
        shutterView?.animate()?.cancel()
        shutterView?.alpha = 1f
        shutterView?.visibility = VISIBLE
    }

    fun prepareForPlaybackTransition() {
        closeShutter()
        handler.removeCallbacks(bufferingIndicatorRunnable)
        bufferingView?.visibility = GONE
    }

    private fun updateAspectRatio() {
        val videoSize = player?.videoSize ?: return
        val width = videoSize.width
        val height = videoSize.height
        if (width == 0 || height == 0) return

        val pixelWidthHeightRatio = videoSize.pixelWidthHeightRatio
        val aspectRatio = (width * pixelWidthHeightRatio) / height.toFloat()
        contentFrame?.setAspectRatio(aspectRatio)
    }

    private fun updateBuffering() {
        val buffering = bufferingView ?: return
        val currentPlayer = player ?: run {
            handler.removeCallbacks(bufferingIndicatorRunnable)
            buffering.visibility = GONE
            return
        }

        if (!shouldShowBufferingIndicator(currentPlayer)) {
            handler.removeCallbacks(bufferingIndicatorRunnable)
            buffering.visibility = GONE
            return
        }

        if (buffering.visibility == VISIBLE) {
            return
        }

        handler.removeCallbacks(bufferingIndicatorRunnable)
        val delayMs = if (hasRenderedFirstFrame) {
            REBUFFER_BUFFERING_INDICATOR_DELAY_MS
        } else {
            STARTUP_BUFFERING_INDICATOR_DELAY_MS
        }
        handler.postDelayed(bufferingIndicatorRunnable, delayMs)
    }

    private fun shouldShowBufferingIndicator(currentPlayer: Player): Boolean {
        val isBuffering = currentPlayer.playbackState == Player.STATE_BUFFERING
        return when (showBuffering) {
            SHOW_BUFFERING_ALWAYS -> true
            SHOW_BUFFERING_WHEN_PLAYING -> isBuffering && currentPlayer.playWhenReady
            else -> false
        }
    }

    private fun updateErrorMessage() {
        val view = errorMessageView ?: return
        val message = customErrorMessage?.toString().takeUnless { it.isNullOrBlank() }

        if (message.isNullOrBlank()) {
            view.visibility = GONE
            view.text = ""
        } else {
            view.text = message
            view.visibility = VISIBLE
        }
    }

    private fun updateControllerVisibility() {
        maybeShowController(false)
    }

    private fun maybeShowController(isForced: Boolean) {
        if (!useController() || player == null) return

        val shouldShowIndefinitely = shouldShowControllerIndefinitely()
        val controller = controller ?: return

        if (controller.isFullyVisible()) {
            if (isForced) {
                controller.resetHideCallbacks()
            }
            return
        }

        if (!isForced) return

        showController(shouldShowIndefinitely)
    }

    private fun shouldShowControllerIndefinitely(): Boolean {
        val currentPlayer = player ?: return true

        if (controllerAutoShow) {
            if (currentPlayer.currentTimeline.isEmpty) {
                return false
            }
            if (
                currentPlayer.playbackState == Player.STATE_IDLE ||
                currentPlayer.playbackState == Player.STATE_ENDED
            ) {
                return true
            }
            if (!currentPlayer.playWhenReady) {
                return true
            }
        }
        return false
    }

    private fun showController(indefinitely: Boolean) {
        if (!useController()) return

        controller?.setShowTimeoutMs(if (indefinitely) 0 else controllerShowTimeoutMs)
        controller?.show()
    }

    fun hideController() {
        controller?.hide()
    }

    fun showController() {
        showController(shouldShowControllerIndefinitely())
    }

    fun isControllerFullyVisible(): Boolean = controller?.isFullyVisible() ?: false

    fun isSettingViewShowing(): Boolean = settingView?.isShowing() ?: false

    fun removeControllerHideCallbacks() {
        controller?.removeHideCallbacks()
    }

    fun resetControllerHideCallbacks() {
        controller?.resetHideCallbacks()
    }

    private fun toggleControllerVisibility() {
        if (!useController() || player == null) return

        if (controller?.isFullyVisible() == true && controllerHideOnTouch) {
            controller?.hide()
        } else {
            maybeShowController(true)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (player == null) return super.dispatchKeyEvent(event)
        if (controller == null) return false

        if (event.keyCode == KeyEvent.KEYCODE_MENU &&
            event.action == KeyEvent.ACTION_DOWN &&
            settingView?.isShowing() != true
        ) {
            showHideSettingView(true)
            return true
        }

        if (event.keyCode == KeyEvent.KEYCODE_BACK &&
            event.action == KeyEvent.ACTION_DOWN &&
            settingView?.isShowing() != true &&
            onResumeProgressCancelled?.invoke() == true
        ) {
            return true
        }

        if (event.keyCode == KeyEvent.KEYCODE_BACK &&
            event.action == KeyEvent.ACTION_DOWN &&
            settingView?.isShowing() == true
        ) {
            settingView?.onBack()
            return true
        }

        if (settingView?.isShowing() == true) {
            return settingView?.dispatchKeyEvent(event) ?: super.dispatchKeyEvent(event)
        }

        if (progressiveSeekHelper.isActive()) {
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                return progressiveSeekHelper.handleKeyEvent(event)
            }
            progressiveSeekHelper.cancelAndFinishSeek()
        }

        if (gestureListener.isDoubleTapping) {
            if (event.action == KeyEvent.ACTION_DOWN &&
                (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
            ) {
                gestureListener.cancelInDoubleTapMode()
                progressiveSeekHelper.handleKeyEvent(event)
            } else {
                gestureListener.handleKeyDown(event)
            }
            return true
        }

        val isDpadKey = isDpadKey(event.keyCode)
        val isSeekKey = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT

        if (isDpadKey && useController && controller?.isFullyVisible() != true) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (isSeekKey) {
                    progressiveSeekHelper.handleKeyEvent(event)
                } else if (!gestureListener.handleKeyDown(event) && !gestureListener.isDoubleTapping) {
                    maybeShowController(true)
                    controller?.focusButtonByKeyDown(event)
                }
            } else if (event.action == KeyEvent.ACTION_UP && isSeekKey) {
                progressiveSeekHelper.handleKeyEvent(event)
            }
            return true
        }

        if (controller?.dispatchMediaKeyEvent(event) == true) {
            maybeShowController(true)
            return true
        }

        val superResult = super.dispatchKeyEvent(event)
        if (superResult) {
            maybeShowController(true)
            return true
        }

        if (isDpadKey && useController && event.action == KeyEvent.ACTION_DOWN) {
            maybeShowController(true)
            val handled = controller?.handleDpadWhenSuperNotHandled(event) ?: false
            return handled
        }

        return false
    }

    private fun isDpadKey(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_DPAD_UP ||
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
            keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KEYCODE_SYSTEM_NAVIGATION_UP_COMPAT ||
            keyCode == KEYCODE_SYSTEM_NAVIGATION_DOWN_COMPAT ||
            keyCode == KEYCODE_SYSTEM_NAVIGATION_LEFT_COMPAT ||
            keyCode == KEYCODE_SYSTEM_NAVIGATION_RIGHT_COMPAT
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        touchInterceptListener?.let { if (it(event)) return true }
        if (isSwipeSeeking && handleSwipeSeekTouch(event)) {
            return true
        }
        if (controller?.isTouchWithinInteractiveArea(event.x, event.y) == true) {
            return false
        }
        if (handleSwipeSeekTouch(event)) {
            return true
        }
        if (isDoubleTapEnabled) {
            gestureDetector.onTouchEvent(event)
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        toggleControllerVisibility()
        return super.performClick()
    }

    fun setResizeMode(resizeMode: Int) {
        contentFrame?.setResizeMode(resizeMode)
        settingView?.setCurrentScreenRatio(resizeMode)
    }

    fun setTitle(title: String?) {
        controller?.setTitle(title)
    }

    fun setSubTitle(subTitle: String?) {
        controller?.setSubTitle(subTitle)
    }

    fun setCustomErrorMessage(message: CharSequence?) {
        customErrorMessage = message
        updateErrorMessage()
    }

    fun setOnPlayerSettingChange(listener: OnPlayerSettingChange?) {
        settingView?.setOnPlayerSettingChange(listener)
    }

    fun setOnVideoSettingChangeListener(listener: OnVideoSettingChangeListener?) {
        controller?.setOnVideoSettingChangeListener(listener)
    }

    fun setControllerVisibilityListener(listener: ControllerVisibilityListener?) {
        controllerVisibilityListener = listener
        updateContentDescription()
    }

    fun setRenderEventListener(listener: RenderEventListener?) {
        renderEventListener = listener
    }

    fun setSeekPreviewListener(listener: SeekPreviewListener?) {
        seekPreviewListener = listener
        listener?.onSeekPreviewStateChanged(
            hiddenSeekPreviewActive,
            hiddenSeekPreviewPositionMs,
            hiddenSeekPreviewDurationMs
        )
    }

    fun setTouchInterceptListener(listener: ((MotionEvent) -> Boolean)?) {
        touchInterceptListener = listener
    }

    fun setPersistentBottomProgressEnabled(enabled: Boolean) {
        persistentBottomProgressEnabled = enabled
        tapOverlayView?.setPersistentBottomProgressEnabled(enabled)
        controller?.setProgressOnlyUiEnabled(!enabled)
        if (!enabled) {
            clearHiddenSeekPreview()
        }
    }

    fun setShowBuffering(mode: Int) {
        showBuffering = mode
        updateBuffering()
    }

    fun setKeepContentOnPlayerReset(keepContentOnPlayerReset: Boolean) {
        this.keepContentOnPlayerReset = keepContentOnPlayerReset
    }

    fun setQualities(qualities: List<VideoQuality>) {
        settingView?.setVideoQualities(qualities)
    }

    fun setLiveQualities(qualities: List<LiveQualityInfo>) {
        controller?.setLiveQualities(qualities)
    }

    fun setSubtitles(models: List<SubtitleInfoModel>) {
        settingView?.setSubtitles(models)
    }

    fun selectQuality(quality: VideoQuality) {
        settingView?.setCurrentVideoQuality(quality)
    }

    fun selectLiveQuality(qn: Int) {
        controller?.selectLiveQuality(qn)
    }

    fun setAudiosSelect(qualities: List<AudioQuality>) {
        settingView?.setAudioQualities(qualities)
    }

    fun selectAudio(audioQuality: AudioQuality) {
        settingView?.setCurrentAudioQuality(audioQuality)
    }

    fun setVideoCodec(codecs: List<VideoCodecEnum>) {
        settingView?.setVideoCodecs(codecs)
    }

    fun selectVideoCodec(videoCodec: VideoCodecEnum) {
        settingView?.setCurrentVideoCodec(videoCodec)
    }

    fun selectSubtitle(position: Int) {
        settingView?.setCurrentSubtitlePosition(position)
    }

    fun setPlaySpeed(speed: Float) {
        player?.playbackParameters = PlaybackParameters(speed)
        settingView?.setCurrentSpeed(speed)
        danmakuController.updatePlaybackSpeed(speed)
        specialDanmakuController.updatePlaybackSpeed(speed)
    }

    fun showSubtitleSettingView() {
        controller?.rememberCurrentFocusTarget()
        settingView?.showSubtitleMenu()
    }

    fun setRepeatMode(repeatMode: Int) {
        controller?.setRepeatMode(repeatMode)
    }

    fun setSeekSecond(seconds: Int) {
        tapOverlayView?.seekSeconds = seconds
        controller?.setFfDuration(seconds.coerceAtLeast(1).toLong() * 1000L)
    }

    fun showHideSettingView(show: Boolean) {
        if (show) {
            if (progressiveSeekHelper.isActive()) {
                progressiveSeekHelper.cancelAndFinishSeek()
            }
            settingView?.setCurrentSpeed(player?.playbackParameters?.speed ?: 1f)
            controller?.rememberCurrentFocusTarget()
        }
        settingView?.showHide(show)
        if (!show) {
            val currentPositionMs = player?.currentPosition?.coerceAtLeast(0L) ?: 0L
            syncDanmakuPosition(currentPositionMs, forceSeek = true)
            restoreControllerAfterGesture(showIndefinitely = true)
        }
    }

    fun showHideDmSwitchButton(show: Boolean) {
        controller?.showHideDmSwitchButton(show)
    }

    fun showHideNextPrevious(show: Boolean) {
        controller?.showHideNextPrevious(show)
    }

    fun showHideFfRe(show: Boolean) {
        controller?.showHideFfRe(show)
    }

    fun setSimpleKeyPressEnabled(enabled: Boolean) {
        controller?.setSimpleKeyPressEnabled(enabled)
    }

    fun setEpisodeNavigationEnabled(previousEnabled: Boolean, nextEnabled: Boolean) {
        controller?.setEpisodeNavigationEnabled(previousEnabled, nextEnabled)
    }

    fun showHideEpisodeButton(show: Boolean) {
        controller?.showHideEpisodeButton(show)
    }

    fun requestPlayPauseFocus() {
        controller?.requestPlayPauseFocus()
    }

    fun requestEpisodeButtonFocus() {
        controller?.requestEpisodeButtonFocus()
    }

    fun showHideActionButton(show: Boolean) {
        controller?.showHideActionButton(show)
    }

    fun requestMoreButtonFocus() {
        controller?.requestMoreButtonFocus()
    }

    fun showHideRelatedButton(show: Boolean) {
        controller?.showHideRelatedButton(show)
    }

    fun requestRelatedButtonFocus() {
        controller?.requestRelatedButtonFocus()
    }

    fun requestOwnerButtonFocus() {
        controller?.requestOwnerButtonFocus()
    }

    fun rememberCurrentFocusTarget() {
        controller?.rememberCurrentFocusTarget()
    }

    fun restoreRememberedFocus() {
        controller?.restoreRememberedFocus()
    }

    fun showHideRepeatButton(show: Boolean) {
        controller?.showHideRepeatButton(show)
    }

    fun showHideSubtitleButton(show: Boolean) {
        controller?.showHideSubtitleButton(show)
    }

    fun showHideLiveSettingButton(show: Boolean) {
        controller?.showHideLiveSettingButton(show)
    }

    fun showSettingButton(show: Boolean) {
        controller?.showSettingButton(show)
    }

    fun setShowHideOwnerInfo(show: Boolean) {
        controller?.setShowHideOwnerButton(show)
    }

    fun play() {
        player?.play()
    }

    fun pause() {
        player?.pause()
    }

    private fun togglePlaybackByDoubleTap() {
        val currentPlayer = player ?: return
        when {
            currentPlayer.playWhenReady -> currentPlayer.pause()
            currentPlayer.playbackState == Player.STATE_IDLE -> {
                currentPlayer.prepare()
                currentPlayer.play()
            }
            currentPlayer.playbackState == Player.STATE_ENDED -> {
                currentPlayer.seekTo(currentPlayer.currentMediaItemIndex, C.TIME_UNSET)
                currentPlayer.play()
            }
            else -> currentPlayer.play()
        }
    }

    fun destroy() {
        controller?.clearVideoSettingChangeListener()
        val currentPlayer = player
        currentPlayer?.removeListener(componentListener)
        when (val surfaceView = videoSurfaceView) {
            is SurfaceView -> currentPlayer?.clearVideoSurfaceView(surfaceView)
            is TextureView -> currentPlayer?.clearVideoTextureView(surfaceView)
        }
        controller?.removeVisibilityListener(controllerComponentListener)
        handler.removeCallbacksAndMessages(null)
        danmakuController.release()
        specialDanmakuController.release()
    }

    fun cancelInDoubleTapMode() {
        gestureListener.cancelInDoubleTapMode()
    }

    fun keepInDoubleTapMode() {
        gestureListener.keepInDoubleTapMode()
    }

    fun setDoubleTapEnabled(enabled: Boolean) {
        isDoubleTapEnabled = enabled
    }

    fun isDoubleTapEnabled(): Boolean = isDoubleTapEnabled

    fun setDoubleTapDelay(delayMs: Long) {
        gestureListener.doubleTapDelay = delayMs
    }

    fun getDoubleTapDelay(): Long = gestureListener.doubleTapDelay

    fun setSeekPreviewSnapshot(snapshot: VideoSnapshotData?) {
        tapOverlayView?.setSeekPreviewSnapshot(snapshot)
    }

    fun setControllerAutoShow(autoShow: Boolean) {
        controllerAutoShow = autoShow
    }

    fun getControllerAutoShow(): Boolean = controllerAutoShow

    fun setTimeBarMinUpdateInterval(intervalMs: Int) {
        controller?.setTimeBarMinUpdateInterval(intervalMs)
    }

    fun setShowMultiWindowTimeBar(show: Boolean) {
        controller?.setShowMultiWindowTimeBar(show)
    }

    fun setDanmakuData(data: List<DmModel>) {
        danmakuController.setData(data)
    }

    fun setSpecialDanmakuData(data: List<SpecialDanmakuModel>) {
        specialDanmakuController.setData(data)
    }

    fun appendDanmakuData(data: List<DmModel>) {
        danmakuController.appendData(data)
    }

    fun setDanmakuEnabled(enabled: Boolean) {
        if ((settingView?.getDmEnable() ?: enabled) != enabled) {
            settingView?.dmEnableClick()
        }
        danmakuController.setEnabled(enabled)
        specialDanmakuController.setEnabled(enabled)
    }

    fun pauseDanmaku() {
        danmakuController.pause()
        specialDanmakuController.pause()
    }

    fun resumeDanmaku() {
        danmakuController.resume()
        specialDanmakuController.resume()
    }

    fun stopDanmaku() {
        danmakuController.stop()
        specialDanmakuController.stop()
    }

    fun syncDanmakuPosition(positionMs: Long, forceSeek: Boolean = false) {
        danmakuController.syncPosition(positionMs, forceSeek)
        specialDanmakuController.syncPosition(positionMs, forceSeek)
    }

    fun setUseController(use: Boolean) {
        if (useController == use) return
        useController = use
        if (use && controller != null) {
            controller?.setPlayer(player)
        } else {
            controller?.hide()
            controller?.setPlayer(null)
        }
        updateContentDescription()
    }

    private fun updateContentDescription() {
        contentDescription = when {
            !useController() -> null
            controller?.isFullyVisible() != true -> resources.getString(R.string.exo_controls_show)
            controllerHideOnTouch -> resources.getString(R.string.exo_controls_hide)
            else -> null
        }
    }

    private fun useController(): Boolean {
        return useController && controller != null
    }

    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        (videoSurfaceView as? SurfaceView)?.visibility = visibility
    }

    private fun syncDanmakuSettings() {
        val snapshot = buildDanmakuSettingsSnapshot()
        danmakuController.applySettings(snapshot)
        specialDanmakuController.applySettings(snapshot)
    }

    // Keep the mapping from setting panel state to danmaku config in one place.
    private fun buildDanmakuSettingsSnapshot(): MyPlayerDanmakuController.SettingsSnapshot {
        return MyPlayerDanmakuController.SettingsSnapshot(
            enabled = settingView?.getDmEnable() ?: true,
            showAdvancedDanmaku = context.isAdvancedDanmakuEnabled(),
            alpha = settingView?.getDmAlpha() ?: 1f,
            textSize = settingView?.getDmTextScaleParam() ?: 40,
            speed = settingView?.getDmSpeedParam() ?: 4,
            screenArea = settingView?.getScreenPartParam() ?: 3,
            allowTop = settingView?.getDmAllowTop() ?: true,
            allowBottom = settingView?.getDmAllowBottom() ?: true,
            smartFilterLevel = context.getDanmakuSmartFilterLevel(),
            mergeDuplicate = settingView?.getDmMergeDuplicate() ?: true
        )
    }

    private fun handleSwipeSeekTouch(event: MotionEvent): Boolean {
        val currentPlayer = player ?: return false
        if (settingView?.isShowing() == true || currentPlayer.duration <= 0L || !currentPlayer.isCurrentMediaItemSeekable) {
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTouchX = event.x
                downTouchY = event.y
                isSwipeSeeking = false
                swipeSeekUsesControllerPreview = false
                swipeSeekStartPositionMs = currentPlayer.currentPosition.coerceAtLeast(0L)
                swipeSeekTargetPositionMs = swipeSeekStartPositionMs
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.x - downTouchX
                val deltaY = event.y - downTouchY
                if (!isSwipeSeeking) {
                    val horizontalDrag =
                        kotlin.math.abs(deltaX) > touchSlop &&
                            kotlin.math.abs(deltaX) > kotlin.math.abs(deltaY) * 1.2f
                    if (!horizontalDrag) {
                        return false
                    }
                    isSwipeSeeking = true
                    swipeSeekUsesControllerPreview = controller?.isVisible() == true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    val deltaMs = (deltaX / width.coerceAtLeast(1)) * currentPlayer.duration
                    renderSwipeSeekPreview(
                        targetPositionMs = swipeSeekStartPositionMs + deltaMs.toLong()
                            .coerceIn(0L, currentPlayer.duration),
                        durationMs = currentPlayer.duration,
                        deltaMs = deltaMs.toLong()
                    )
                }
                val durationMs = currentPlayer.duration.coerceAtLeast(0L)
                val widthPx = width.coerceAtLeast(1)
                val offsetRatio = (deltaX / widthPx.toFloat()).coerceIn(-1f, 1f)
                val targetPositionMs =
                    (swipeSeekStartPositionMs + (durationMs * offsetRatio).toLong())
                        .coerceIn(0L, durationMs)
                swipeSeekTargetPositionMs = targetPositionMs
                val deltaMs = swipeSeekTargetPositionMs - swipeSeekStartPositionMs
                renderSwipeSeekPreview(
                    targetPositionMs = targetPositionMs,
                    durationMs = durationMs,
                    deltaMs = deltaMs
                )
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!isSwipeSeeking) {
                    return false
                }
                currentPlayer.seekTo(swipeSeekTargetPositionMs)
                syncDanmakuPosition(swipeSeekTargetPositionMs, forceSeek = true)
                if (swipeSeekUsesControllerPreview) {
                    controller?.endSeekPreview(swipeSeekTargetPositionMs, 180L)
                } else if (persistentBottomProgressEnabled) {
                    dispatchSeekPreviewState(true, swipeSeekTargetPositionMs, currentPlayer.duration)
                    scheduleHiddenSeekPreviewClear(SWIPE_SEEK_PREVIEW_CLEAR_DELAY_MS)
                }
                tapOverlayView?.finishSwipeSeek()
                isSwipeSeeking = false
                swipeSeekUsesControllerPreview = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (!isSwipeSeeking) {
                    return false
                }
                if (swipeSeekUsesControllerPreview) {
                    controller?.cancelSeekPreview()
                }
                clearHiddenSeekPreview()
                tapOverlayView?.cancelSwipeSeek()
                isSwipeSeeking = false
                swipeSeekUsesControllerPreview = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return false
    }

    private fun restoreControllerAfterGesture(showIndefinitely: Boolean = false) {
        setUseController(true)
        if (showIndefinitely) {
            showController(true)
        } else {
            maybeShowController(true)
        }
        controller?.resetHideCallbacks()
    }

    private fun renderSwipeSeekPreview(targetPositionMs: Long, durationMs: Long, deltaMs: Long) {
        if (swipeSeekUsesControllerPreview) {
            controller?.beginSeekPreview(targetPositionMs)
            clearHiddenSeekPreview()
        } else if (persistentBottomProgressEnabled) {
            dispatchSeekPreviewState(true, targetPositionMs, durationMs)
        } else {
            clearHiddenSeekPreview()
        }
        tapOverlayView?.showSwipeSeek(
            targetPositionMs = targetPositionMs,
            durationMs = durationMs,
            deltaMs = deltaMs,
            showBottomProgress = !swipeSeekUsesControllerPreview && !persistentBottomProgressEnabled
        )
    }

    private fun dispatchSeekPreviewState(active: Boolean, positionMs: Long, durationMs: Long) {
        val safePositionMs = positionMs.coerceAtLeast(0L)
        val safeDurationMs = durationMs.coerceAtLeast(0L)
        if (hiddenSeekPreviewActive == active &&
            hiddenSeekPreviewPositionMs == safePositionMs &&
            hiddenSeekPreviewDurationMs == safeDurationMs
        ) {
            return
        }
        hiddenSeekPreviewActive = active
        hiddenSeekPreviewPositionMs = safePositionMs
        hiddenSeekPreviewDurationMs = safeDurationMs
        seekPreviewListener?.onSeekPreviewStateChanged(active, safePositionMs, safeDurationMs)
    }

    private fun scheduleHiddenSeekPreviewClear(delayMs: Long) {
        handler.removeCallbacks(clearHiddenSeekPreviewRunnable)
        handler.postDelayed(clearHiddenSeekPreviewRunnable, delayMs)
    }

    private fun clearHiddenSeekPreview() {
        handler.removeCallbacks(clearHiddenSeekPreviewRunnable)
        dispatchSeekPreviewState(active = false, positionMs = 0L, durationMs = 0L)
    }

    private inner class ProgressiveSeekHelper {

        var isSeeking = false
            private set
        var isForward = true
            private set
        private var isSpeedMode = false
        private var speedIndex = 0
        private var originalSpeed = 1f
        private var pendingRunnable: Runnable? = null
        private var speedStepRunnable: Runnable? = null

        private val speeds = floatArrayOf(2f, 4f, 8f, 16f)
        private val longPressThresholdMs = 300L
        private val speedStepIntervalMs = 1500L

        fun handleKeyEvent(event: KeyEvent): Boolean {
            val forward = event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (!isSeeking) {
                    startSeek(forward)
                } else if (isForward != forward) {
                    cancelSeek()
                    startSeek(forward)
                } else if (!isForward && !isSpeedMode) {
                    doRewindTick()
                }
                return true
            } else if (event.action == KeyEvent.ACTION_UP) {
                if (isSeeking) {
                    finishSeek()
                }
                return true
            }
            return isSeeking
        }

        private fun startSeek(forward: Boolean) {
            val currentPlayer = player ?: return
            if (currentPlayer.playbackState == Player.STATE_ENDED ||
                currentPlayer.playbackState == Player.STATE_IDLE
            ) return
            isSeeking = true
            isForward = forward
            isSpeedMode = false
            speedIndex = 0
            if (isForward) {
                originalSpeed = currentPlayer.playbackParameters.speed
                val pending = Runnable {
                    isSpeedMode = true
                    enterSpeedMode()
                }
                pendingRunnable = pending
                handler.postDelayed(pending, longPressThresholdMs)
            } else {
                doRewindTick()
            }
        }

        private fun enterSpeedMode() {
            if (isForward) {
                val currentPlayer = player ?: return
                if (!currentPlayer.isPlaying) currentPlayer.play()
                currentPlayer.playbackParameters = PlaybackParameters(speeds[0])
            }
            tapOverlayView?.showSpeedSeek(isForward, speeds[0])
            scheduleNextSpeedStep()
        }

        private fun scheduleNextSpeedStep() {
            cancelSpeedStepRunnable()
            if (speedIndex >= speeds.size - 1) return
            val runnable = Runnable {
                speedIndex++
                val speed = speeds[speedIndex]
                if (isForward) {
                    player?.playbackParameters = PlaybackParameters(speed)
                }
                tapOverlayView?.showSpeedSeek(isForward, speed)
                scheduleNextSpeedStep()
            }
            speedStepRunnable = runnable
            handler.postDelayed(runnable, speedStepIntervalMs)
        }

        private fun doRewindTick() {
            val currentPlayer = player ?: return
            val duration = currentPlayer.duration
            if (duration <= 0L || !currentPlayer.isCurrentMediaItemSeekable) return
            val seekMs = (tapOverlayView?.seekSeconds?.toLong() ?: 10L) * 1000L
            val targetMs = (currentPlayer.currentPosition - seekMs).coerceAtLeast(0L)
            currentPlayer.seekTo(targetMs)
            syncDanmakuPosition(targetMs, forceSeek = true)
            val usePersistentBottomProgress = persistentBottomProgressEnabled && controller?.isVisible() != true
            if (usePersistentBottomProgress) {
                dispatchSeekPreviewState(true, targetMs, duration)
                scheduleHiddenSeekPreviewClear(DISCRETE_SEEK_PREVIEW_CLEAR_DELAY_MS)
            } else {
                clearHiddenSeekPreview()
            }
            tapOverlayView?.showControllerSeek(
                targetPositionMs = targetMs,
                durationMs = duration,
                deltaMs = -seekMs,
                showBottomProgress = !usePersistentBottomProgress
            )
        }

        private fun finishSeek() {
            cancelPendingRunnable()
            cancelSpeedStepRunnable()
            isSeeking = false
            speedIndex = 0
            if (isSpeedMode) {
                isSpeedMode = false
                val currentPlayer = player
                if (currentPlayer != null && isForward) {
                    currentPlayer.playbackParameters = PlaybackParameters(originalSpeed)
                }
                tapOverlayView?.finishSpeedSeek()
            } else if (isForward) {
                doSingleSeek()
            }
            restoreControllerAfterGesture()
        }

        private fun doSingleSeek() {
            val currentPlayer = player ?: return
            if (currentPlayer.playbackState == Player.STATE_ENDED ||
                currentPlayer.playbackState == Player.STATE_IDLE
            ) return
            val duration = currentPlayer.duration
            if (duration <= 0L || !currentPlayer.isCurrentMediaItemSeekable) return

            val deltaMs = 10000L * if (isForward) 1 else -1
            val targetMs = (currentPlayer.currentPosition + deltaMs).coerceIn(0L, duration)
            currentPlayer.seekTo(targetMs)
            syncDanmakuPosition(targetMs, forceSeek = true)
            val usePersistentBottomProgress = persistentBottomProgressEnabled && controller?.isVisible() != true
            if (usePersistentBottomProgress) {
                dispatchSeekPreviewState(true, targetMs, duration)
                scheduleHiddenSeekPreviewClear(DISCRETE_SEEK_PREVIEW_CLEAR_DELAY_MS)
            } else {
                clearHiddenSeekPreview()
            }
            tapOverlayView?.showControllerSeek(
                targetPositionMs = targetMs,
                durationMs = duration,
                deltaMs = deltaMs,
                showBottomProgress = !usePersistentBottomProgress
            )
        }

        private fun cancelSeek() {
            cancelPendingRunnable()
            cancelSpeedStepRunnable()
            isSeeking = false
            isSpeedMode = false
            speedIndex = 0
            val currentPlayer = player
            if (currentPlayer != null && isForward) {
                currentPlayer.playbackParameters = PlaybackParameters(originalSpeed)
            }
            clearHiddenSeekPreview()
            tapOverlayView?.cancelSwipeSeek()
            restoreControllerAfterGesture()
        }

        private fun cancelPendingRunnable() {
            pendingRunnable?.let { handler.removeCallbacks(it) }
            pendingRunnable = null
        }

        private fun cancelSpeedStepRunnable() {
            speedStepRunnable?.let { handler.removeCallbacks(it) }
            speedStepRunnable = null
        }

        fun cancelAndFinishSeek() {
            cancelPendingRunnable()
            cancelSpeedStepRunnable()
            isSeeking = false
            isSpeedMode = false
            speedIndex = 0
            val currentPlayer = player
            if (currentPlayer != null && isForward) {
                currentPlayer.playbackParameters = PlaybackParameters(originalSpeed)
            }
            clearHiddenSeekPreview()
            tapOverlayView?.cancelSwipeSeek()
            restoreControllerAfterGesture()
        }

        fun isActive(): Boolean = isSeeking
    }
}


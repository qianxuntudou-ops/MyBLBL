package com.tutu.myblbl.feature.player.view

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.graphics.Rect
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.TimeBar
import com.tutu.myblbl.R
import com.tutu.myblbl.core.common.log.AppLog
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.LazyThreadSafetyMode

@OptIn(UnstableApi::class)
class MyPlayerControlView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        const val DEFAULT_SHOW_TIMEOUT_MS = 3000
        const val DEFAULT_FAST_FORWARD_MS = 10000L
        const val DEFAULT_REWIND_MS = 10000L
        const val DEFAULT_TIME_BAR_MIN_UPDATE_INTERVAL_MS = 200
        private const val ENABLED_BUTTON_ALPHA = 1f
        private const val DISABLED_BUTTON_ALPHA = 0.3f
    }

    private val handler = Handler(Looper.getMainLooper())
    var uiCoordinator: com.tutu.myblbl.feature.player.PlaybackUiCoordinator? = null
    private val controlViewLayoutManager by lazy(LazyThreadSafetyMode.NONE) {
        MyPlayerControlViewLayoutManager(this, uiCoordinator)
    }
    
    private var player: Player? = null
    private var attachedToWindow: Boolean = false
    internal var showTimeoutMs: Int = DEFAULT_SHOW_TIMEOUT_MS
    
    private val visibilityListeners = CopyOnWriteArrayList<VisibilityListener>()
    private var onMenuShowImpl: OnMenuShowImpl? = null
    private var onDmEnableChangeImpl: OnDmEnableChangeImpl? = null
    private var onVideoSettingChangeListener: OnVideoSettingChangeListener? = null

    // Owns multi-window time-bar math so the control view can stay centered on input handling.
    private val timelineCoordinator = PlayerControlTimelineCoordinator()
    
    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            val p = player ?: return
            val state = p.playbackState
            if ((state == Player.STATE_ENDED || state == Player.STATE_IDLE) && !p.playWhenReady) {
                return // Don't reschedule when not actively playing
            }
            val delayMs = calculateProgressUpdateDelay()
            handler.postDelayed(this, delayMs)
        }
    }

    private lateinit var textTitle: TextView
    private lateinit var textSubTitle: TextView
    private lateinit var timeBar: DefaultTimeBar
    private lateinit var exoPosition: TextView
    private lateinit var exoDuration: TextView
    private lateinit var buttonPlay: ImageView
    private lateinit var buttonPrevious: ImageView
    private lateinit var buttonNext: ImageView
    private lateinit var buttonRewind: ImageView
    private lateinit var buttonFastForward: ImageView
    private lateinit var buttonDmSwitch: ImageView
    private lateinit var buttonSettings: ImageView
    private lateinit var buttonChooseEpisode: ImageView
    private lateinit var buttonMore: ImageView
    private lateinit var buttonUpInfo: ImageView
    private lateinit var buttonSubtitle: ImageView
    private lateinit var buttonRelated: ImageView
    private lateinit var buttonRepeat: ImageView
    private lateinit var buttonLiveSettings: ImageView
    private lateinit var buttonRefresh: ImageView
    private lateinit var buttonClose: ImageView
    private lateinit var textLiveDuration: TextView
    private lateinit var loadingProgressBar: ProgressBar
    private lateinit var titleContainer: View
    private lateinit var centerControls: ViewGroup
    private lateinit var bottomBar: ViewGroup

    private var dmEnabled: Boolean = true
    private var ffDuration: Long = DEFAULT_FAST_FORWARD_MS
    private var needToHideBars: Boolean = true
    private var isScrubbing: Boolean = false
    private var timeBarMinUpdateIntervalMs: Int = DEFAULT_TIME_BAR_MIN_UPDATE_INTERVAL_MS
    private var showMultiWindowTimeBar: Boolean = false
    private var seekPreviewListener: SeekPreviewListener? = null
    private var seekCommitListener: ((Long) -> Unit)? = null
    private var previousButtonEnabled: Boolean = true
    private var nextButtonEnabled: Boolean = true
    private var simpleKeyPressEnabled: Boolean = false
    private lateinit var focusCoordinator: PlayerControlFocusCoordinator
    private var externalSeekPreviewActive: Boolean = false
    private val externalSeekPreviewFinishRunnable = Runnable {
        finishExternalSeekPreview()
    }

    interface VisibilityListener {
        fun onVisibilityChange(visibility: Int)
    }

    interface SeekPreviewListener {
        fun onSeekPreview(targetPositionMs: Long, durationMs: Long, deltaMs: Long)
    }

    private val componentListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_PLAY_WHEN_READY_CHANGED,
                    Player.EVENT_PLAYBACK_STATE_CHANGED
                )
            ) {
                updatePlayPauseButton()
                updateLoadingState()
            }
            if (events.containsAny(
                    Player.EVENT_PLAY_WHEN_READY_CHANGED,
                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                    Player.EVENT_POSITION_DISCONTINUITY
                )
            ) {
                updateProgress()
            }
            if (events.containsAny(
                    Player.EVENT_AVAILABLE_COMMANDS_CHANGED,
                    Player.EVENT_TIMELINE_CHANGED
                )
            ) {
                updateNavigation()
            }
            if (events.contains(Player.EVENT_TIMELINE_CHANGED)) {
                updateTimeline()
            }
        }
    }

    init {
        LayoutInflater.from(context).inflate(R.layout.my_player_control_view, this, true)
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        controlViewLayoutManager.setAnimationEnabled(true)
        initViews()
        setupClickListeners()
        hideImmediately()
    }

    private fun initViews() {
        textTitle = findViewById(R.id.text_title)
        textSubTitle = findViewById(R.id.text_sub_title)
        timeBar = findViewById(R.id.exo_progress)
        exoPosition = findViewById(R.id.exo_position)
        exoDuration = findViewById(R.id.exo_duration)
        buttonPlay = findViewById(R.id.button_play)
        buttonPrevious = findViewById(R.id.button_previous)
        buttonNext = findViewById(R.id.button_next)
        buttonRewind = findViewById(R.id.button_rewind)
        buttonFastForward = findViewById(R.id.button_fast_forward)
        buttonDmSwitch = findViewById(R.id.button_dm_switch)
        buttonSettings = findViewById(R.id.exo_settings)
        buttonChooseEpisode = findViewById(R.id.button_choose_episode)
        buttonMore = findViewById(R.id.button_more)
        buttonUpInfo = findViewById(R.id.button_up_info)
        buttonSubtitle = findViewById(R.id.button_subtitle)
        buttonRelated = findViewById(R.id.button_related)
        buttonRepeat = findViewById(R.id.button_repeat)
        buttonLiveSettings = findViewById(R.id.button_live_settings)
        buttonRefresh = findViewById(R.id.button_refresh)
        buttonClose = findViewById(R.id.button_close)
        textLiveDuration = findViewById(R.id.text_live_duration)
        loadingProgressBar = findViewById(R.id.loading_progress_bar)
        centerControls = findViewById(R.id.exo_center_controls)
        bottomBar = findViewById(R.id.exo_bottom_bar)
        titleContainer = findViewById(R.id.view_title)
        titleContainer.setOnFocusChangeListener { _, hasFocus ->
        }

        // Focus routing is kept in a dedicated coordinator so player actions stay separate.
        focusCoordinator = PlayerControlFocusCoordinator(
            buttonPlay = buttonPlay,
            buttonPrevious = buttonPrevious,
            buttonNext = buttonNext,
            buttonRewind = buttonRewind,
            buttonFastForward = buttonFastForward,
            buttonDmSwitch = buttonDmSwitch,
            buttonSettings = buttonSettings,
            buttonChooseEpisode = buttonChooseEpisode,
            buttonMore = buttonMore,
            buttonUpInfo = buttonUpInfo,
            buttonSubtitle = buttonSubtitle,
            buttonRelated = buttonRelated,
            buttonRepeat = buttonRepeat,
            buttonLiveSettings = buttonLiveSettings,
            buttonRefresh = buttonRefresh,
            buttonClose = buttonClose,
            timeBar = timeBar,
            bottomBar = bottomBar
        )
        focusCoordinator.setupFocusTracking(
            handler,
            ::isVisible,
            ::resetHideCallbacks,
            buttonPlay,
            buttonPrevious,
            buttonNext,
            buttonRewind,
            buttonFastForward,
            buttonDmSwitch,
            buttonSettings,
            buttonChooseEpisode,
            buttonMore,
            buttonUpInfo,
            buttonSubtitle,
            buttonRelated,
            buttonRepeat,
            buttonLiveSettings,
            buttonRefresh,
            buttonClose,
            timeBar
        )
        timeBar.setKeyCountIncrement(60)
        updateButtonEnabled(buttonPrevious, previousButtonEnabled)
        updateButtonEnabled(buttonNext, nextButtonEnabled)
        syncManagedButtonVisibility()
        AppLog.d("LiveUI", "initViews: buttonRefresh id=${buttonRefresh.id} vis=${buttonRefresh.visibility}")
    }

    private fun setupClickListeners() {
        buttonPlay.setOnClickListener {
            resetHideCallbacks()
            player?.let { p -> dispatchPlayPause(p) }
        }
        
        buttonPrevious.setOnClickListener {
            resetHideCallbacks()
            onVideoSettingChangeListener?.onPrevious()
        }

        buttonNext.setOnClickListener {
            resetHideCallbacks()
            onVideoSettingChangeListener?.onNext()
        }

        buttonRewind.setOnClickListener {
            resetHideCallbacks()
            player?.let { p -> dispatchRewind(p) }
        }

        buttonFastForward.setOnClickListener {
            resetHideCallbacks()
            player?.let { p -> dispatchFastForward(p) }
        }
        
        buttonDmSwitch.setOnClickListener {
            resetHideCallbacks()
            dmEnabled = !dmEnabled
            updateDmSwitchIcon()
            if (dmEnabled) {
                onDmEnableChangeImpl?.onEnable()
            }
            onVideoSettingChangeListener?.onDmEnableChange(dmEnabled)
        }
        
        buttonSettings.setOnClickListener {
            resetHideCallbacks()
            onMenuShowImpl?.onShowHide(true)
        }

        buttonChooseEpisode.setOnClickListener {
            resetHideCallbacks()
            onVideoSettingChangeListener?.onChooseEpisode()
        }

        buttonMore.setOnClickListener {
            resetHideCallbacks()
            onVideoSettingChangeListener?.onMore()
        }

        buttonUpInfo.setOnClickListener {
            resetHideCallbacks()
            onVideoSettingChangeListener?.onUpInfo()
        }

        buttonSubtitle.setOnClickListener {
            resetHideCallbacks()
            onVideoSettingChangeListener?.onSubtitle()
        }

        buttonRelated.setOnClickListener {
            resetHideCallbacks()
            onVideoSettingChangeListener?.onRelated()
        }

        buttonRepeat.setOnClickListener {
            resetHideCallbacks()
            onVideoSettingChangeListener?.onRepeat()
        }

        buttonLiveSettings.setOnClickListener {
            removeHideCallbacks()
            onVideoSettingChangeListener?.onLiveSettings()
        }

        buttonRefresh.setOnClickListener {
            resetHideCallbacks()
            onVideoSettingChangeListener?.onRefresh()
        }

        buttonClose.setOnClickListener {
            resetHideCallbacks()
            onVideoSettingChangeListener?.onClose()
        }

        titleContainer.setOnClickListener {
            if (simpleKeyPressEnabled) {
                return@setOnClickListener
            }
            resetHideCallbacks()
            onVideoSettingChangeListener?.onVideoInfo()
        }

        timeBar.addListener(object : TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: TimeBar, position: Long) {
                cancelExternalSeekPreview(resetHideCallbacks = false)
                isScrubbing = true
                removeHideCallbacks()
                exoPosition.text = timelineCoordinator.formatTime(position)
                hideInfoOnlyLeftTimeBar()
            }

            override fun onScrubMove(timeBar: TimeBar, position: Long) {
                exoPosition.text = timelineCoordinator.formatTime(position)
            }

            override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                isScrubbing = false
                if (!canceled) {
                    player?.let { seekToTimeBarPosition(it, position) }
                }
                resetHideCallbacks()
                showInfoAfterEndFF()
            }
        })
    }

    fun setPlayer(player: Player?) {
        if (this.player === player) return
        this.player?.removeListener(componentListener)
        this.player = player
        player?.addListener(componentListener)
        updateAll()
        setRepeatMode(player?.repeatMode ?: Player.REPEAT_MODE_OFF)
    }

    fun show(focusPlayPause: Boolean = false) {
        controlViewLayoutManager.show(focusPlayPause)
    }

    fun hide() {
        controlViewLayoutManager.hide()
    }

    fun hideImmediately() {
        controlViewLayoutManager.hideImmediately()
    }

    fun isFullyVisible(): Boolean = controlViewLayoutManager.isFullyVisible()

    fun isScrubbingTimeBar(): Boolean = isScrubbing

    fun isTimebarFocused(): Boolean = ::timeBar.isInitialized && timeBar.hasFocus()

    fun enterSeekProgressOnly() {
        controlViewLayoutManager.enterSeekProgressOnly()
    }

    fun exitSeekProgressOnly() {
        controlViewLayoutManager.exitSeekProgressOnly()
    }

    fun setShowTimeoutMs(timeoutMs: Int) {
        showTimeoutMs = timeoutMs
        if (isFullyVisible()) {
            resetHideCallbacks()
        }
    }

    fun getShowTimeoutMs(): Int = showTimeoutMs

    private fun updatePlayPauseButton() {
        if (!isVisible() || !attachedToWindow) {
            return
        }
        player?.let { p ->
            if (p.isPlaying) {
                buttonPlay.setImageResource(R.drawable.ic_pause)
                buttonPlay.contentDescription = resources.getString(R.string.pause)
            } else {
                buttonPlay.setImageResource(R.drawable.ic_play)
                buttonPlay.contentDescription = resources.getString(R.string.play)
            }
        }
    }

    private fun updateProgress() {
        timelineCoordinator.updateProgress(
            player = player,
            isVisible = isVisible(),
            attachedToWindow = attachedToWindow,
            isScrubbing = isScrubbing || externalSeekPreviewActive,
            renderPosition = ::renderPosition,
            renderDuration = ::renderDuration,
            renderBufferedPosition = ::renderBufferedPosition,
            stopUpdates = { handler.removeCallbacks(progressRunnable) }
        )
    }

    private fun updateLoadingState() {
        val isLoading = player?.playbackState == Player.STATE_BUFFERING
        loadingProgressBar.visibility = if (isLoading) VISIBLE else GONE
    }

    private fun calculateProgressUpdateDelay(): Long {
        return timelineCoordinator.calculateProgressUpdateDelay(
            player = player,
            preferredDelayMs = timeBar.getPreferredUpdateDelay(),
            minUpdateIntervalMs = timeBarMinUpdateIntervalMs
        )
    }

    private fun updatePlaybackSpeedList() {
    }

    private fun updateTimeline() {
        timelineCoordinator.updateTimeline(
            player = player,
            showMultiWindowTimeBar = showMultiWindowTimeBar,
            renderDuration = ::renderDuration,
            updateProgress = ::updateProgress
        )
    }

    private fun renderPosition(positionMs: Long) {
        timelineCoordinator.renderPosition(positionMs) { sanitizedPositionMs ->
            exoPosition.text = timelineCoordinator.formatTime(sanitizedPositionMs)
            timeBar.setPosition(sanitizedPositionMs)
        }
    }

    private fun renderBufferedPosition(bufferedPositionMs: Long) {
        timelineCoordinator.renderBufferedPosition(bufferedPositionMs) { sanitizedBufferedPositionMs ->
            timeBar.setBufferedPosition(sanitizedBufferedPositionMs)
        }
    }

    private fun renderDuration(durationMs: Long) {
        timelineCoordinator.renderDuration(durationMs) { sanitizedDurationMs ->
            exoDuration.text = timelineCoordinator.formatTime(sanitizedDurationMs)
            timeBar.setDuration(sanitizedDurationMs)
        }
    }

    private fun seekToTimeBarPosition(player: Player, positionMs: Long) {
        timelineCoordinator.seekTo(player, positionMs, ::updateProgress)
        seekCommitListener?.invoke(positionMs.coerceAtLeast(0L))
    }

    fun setTitle(title: String?) {
        textTitle.text = title ?: ""
    }

    fun setSubTitle(subTitle: String?) {
        textSubTitle.text = subTitle ?: ""
    }

    fun setFfDuration(duration: Long) {
        ffDuration = duration
    }

    fun setOnSeekCommitListener(listener: ((Long) -> Unit)?) {
        seekCommitListener = listener
    }

    fun dmEnableButtonChange(enabled: Boolean) {
        dmEnabled = enabled
        updateDmSwitchIcon()
    }

    private fun updateDmSwitchIcon() {
        if (dmEnabled) {
            buttonDmSwitch.setImageResource(R.drawable.ic_dm_enable)
        } else {
            buttonDmSwitch.setImageResource(R.drawable.ic_dm_disable)
        }
    }

    fun showHideDmSwitchButton(show: Boolean) {
        setButtonVisibility(buttonDmSwitch, show)
    }

    fun showHideNextPrevious(show: Boolean) {
        setButtonVisibility(buttonPrevious, show)
        setButtonVisibility(buttonNext, show)
        updateButtonEnabled(buttonPrevious, previousButtonEnabled)
        updateButtonEnabled(buttonNext, nextButtonEnabled)
    }

    fun setEpisodeNavigationEnabled(previousEnabled: Boolean, nextEnabled: Boolean) {
        previousButtonEnabled = previousEnabled
        nextButtonEnabled = nextEnabled
        updateButtonEnabled(buttonPrevious, previousEnabled)
        updateButtonEnabled(buttonNext, nextEnabled)
    }

    fun showHideFfRe(show: Boolean) {
        setButtonVisibility(buttonRewind, show)
        setButtonVisibility(buttonFastForward, show)
    }

    fun showHideEpisodeButton(show: Boolean) {
        setButtonVisibility(buttonChooseEpisode, show)
    }

    fun showHideActionButton(show: Boolean) {
        setButtonVisibility(buttonMore, show)
    }

    fun showHideRelatedButton(show: Boolean) {
        setButtonVisibility(buttonRelated, show)
    }

    fun isRelatedButtonVisible(): Boolean {
        return buttonRelated.visibility == View.VISIBLE
    }

    fun onRelatedButtonClick() {
        onVideoSettingChangeListener?.onRelated()
    }

    fun showHideRepeatButton(show: Boolean) {
        setButtonVisibility(buttonRepeat, show)
    }

    fun setRepeatMode(repeatMode: Int) {
        buttonRepeat.setImageResource(
            if (repeatMode == Player.REPEAT_MODE_ONE) R.drawable.ic_loop_one_play else R.drawable.ic_order_all_play
        )
    }

    fun showHideSubtitleButton(show: Boolean) {
        setButtonVisibility(buttonSubtitle, show)
    }

    fun showHideLiveSettingButton(show: Boolean) {
        setButtonVisibility(buttonLiveSettings, show)
    }

    fun showHideRefreshButton(show: Boolean) {
        AppLog.d("LiveUI", "showHideRefreshButton: show=$show, before vis=${buttonRefresh.visibility}")
        setButtonVisibility(buttonRefresh, show)
        AppLog.d("LiveUI", "showHideRefreshButton: after vis=${buttonRefresh.visibility}")
    }

    fun showHideTimeBar(show: Boolean) {
        timeBar.visibility = if (show) VISIBLE else GONE
        AppLog.d("LiveUI", "showHideTimeBar: show=$show vis=${timeBar.visibility}")
    }

    fun showHideTimeText(show: Boolean) {
        controlViewLayoutManager.setTimeViewVisible(show)
        AppLog.d("LiveUI", "showHideTimeText: show=$show vis=${(exoPosition.parent as? View)?.visibility}")
    }

    fun showSettingButton(show: Boolean) {
        setButtonVisibility(buttonSettings, show)
    }

    fun setLiveDuration(text: String) {
        if (text.isNotEmpty()) {
            textLiveDuration.text = text
            textLiveDuration.visibility = VISIBLE
        } else {
            textLiveDuration.visibility = GONE
        }
    }

    fun setShowHideOwnerButton(show: Boolean) {
        setButtonVisibility(buttonUpInfo, show)
    }

    fun setOnMenuShowImpl(impl: OnMenuShowImpl?) {
        onMenuShowImpl = impl
    }

    fun setOnDmEnableChangeImpl(impl: OnDmEnableChangeImpl?) {
        onDmEnableChangeImpl = impl
    }

    fun setOnVideoSettingChangeListener(listener: OnVideoSettingChangeListener?) {
        onVideoSettingChangeListener = listener
    }

    fun clearVideoSettingChangeListener() {
        onVideoSettingChangeListener = null
    }

    fun addVisibilityListener(listener: VisibilityListener) {
        visibilityListeners.add(listener)
    }

    fun removeVisibilityListener(listener: VisibilityListener) {
        visibilityListeners.remove(listener)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return dispatchMediaKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    fun dispatchMediaKeyEvent(event: KeyEvent): Boolean {
        val p = player ?: return false
        val keyCode = event.keyCode
        if (!isHandledMediaKey(keyCode)) return false

        if (event.action != KeyEvent.ACTION_DOWN) return true

        if (keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
            dispatchFastForward(p)
            return true
        }

        if (keyCode == KeyEvent.KEYCODE_MEDIA_REWIND) {
            dispatchRewind(p)
            return true
        }

        if (event.repeatCount > 0) return true

        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                p.play()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                p.pause()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                dispatchPlayPause(p)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                onVideoSettingChangeListener?.onNext()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                onVideoSettingChangeListener?.onPrevious()
                return true
            }
        }
        return true
    }

    private fun dispatchPlayPause(p: Player) {
        val state = p.playbackState
        if (state == Player.STATE_IDLE || state == Player.STATE_ENDED || !p.playWhenReady) {
            dispatchPlay(p)
        } else {
            p.pause()
        }
    }

    private fun dispatchFastForward(p: Player) {
        if (p.playbackState == Player.STATE_ENDED) {
            return
        }
        val duration = p.duration
        val target = p.currentPosition + ffDuration
        val targetPositionMs = if (duration != C.TIME_UNSET) {
            target.coerceAtMost(duration)
        } else {
            target
        }
        p.seekTo(targetPositionMs)
        seekCommitListener?.invoke(targetPositionMs.coerceAtLeast(0L))
        beginSeekPreview(targetPositionMs.coerceAtLeast(0L))
        endSeekPreview(targetPositionMs.coerceAtLeast(0L))
    }

    private fun dispatchRewind(p: Player) {
        val target = (p.currentPosition - ffDuration).coerceAtLeast(0L)
        p.seekTo(target)
        seekCommitListener?.invoke(target)
        beginSeekPreview(target)
        endSeekPreview(target)
    }

    private fun dispatchPlay(p: Player) {
        val state = p.playbackState
        if (state == Player.STATE_IDLE) {
            p.prepare()
        } else if (state == Player.STATE_ENDED) {
            p.seekTo(p.currentMediaItemIndex, androidx.media3.common.C.TIME_UNSET)
            seekCommitListener?.invoke(0L)
        }
        p.play()
    }

    private fun isHandledMediaKey(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ||
            keyCode == KeyEvent.KEYCODE_MEDIA_REWIND ||
            keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
            keyCode == KeyEvent.KEYCODE_HEADSETHOOK ||
            keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
            keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE ||
            keyCode == KeyEvent.KEYCODE_MEDIA_NEXT ||
            keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS
    }

    fun handleDpadWhenSuperNotHandled(event: KeyEvent): Boolean {
        if (simpleKeyPressEnabled) return false
        return focusCoordinator.handleDpadWhenSuperNotHandled(event, findFocus())
    }

    fun setSimpleKeyPressEnabled(enabled: Boolean) {
        simpleKeyPressEnabled = enabled
        titleContainer.isClickable = !enabled
        titleContainer.isFocusable = false
    }

    fun focusButtonByKeyDown(event: KeyEvent) {
        focusCoordinator.focusButtonByKeyDown(
            event = event,
            onPlayPauseClick = { buttonPlay.performClick() },
            onRewind = { player?.let { dispatchRewind(it) } },
            onFastForward = { player?.let { dispatchFastForward(it) } }
        )
    }

    fun requestPlayPauseFocus() {
        focusCoordinator.requestPlayPauseFocus()
    }

    fun requestSettingButtonFocus() {
        focusCoordinator.requestSettingButtonFocus()
    }

    fun requestRelatedButtonFocus() {
        focusCoordinator.requestRelatedButtonFocus()
    }

    fun requestEpisodeButtonFocus() {
        focusCoordinator.requestEpisodeButtonFocus()
    }

    fun requestMoreButtonFocus() {
        focusCoordinator.requestMoreButtonFocus()
    }

    fun requestOwnerButtonFocus() {
        focusCoordinator.requestOwnerButtonFocus()
    }

    fun requestSubtitleButtonFocus() {
        focusCoordinator.requestSubtitleButtonFocus()
    }

    fun rememberCurrentFocusTarget() {
        focusCoordinator.rememberCurrentFocusTarget()
    }

    fun restoreRememberedFocus() {
        focusCoordinator.restoreRememberedFocus()
    }

    fun requestTimeBarFocus() {
        focusCoordinator.requestTimeBarFocus()
    }

    fun beginSeekPreview(positionMs: Long) {
        removeCallbacks(externalSeekPreviewFinishRunnable)
        if (!externalSeekPreviewActive) {
            externalSeekPreviewActive = true
            removeHideCallbacks()
            hideInfoOnlyLeftTimeBar()
        }
        renderPosition(positionMs)
    }

    fun endSeekPreview(positionMs: Long, delayMs: Long = 650L) {
        if (!externalSeekPreviewActive) {
            return
        }
        renderPosition(positionMs)
        removeCallbacks(externalSeekPreviewFinishRunnable)
        postDelayed(externalSeekPreviewFinishRunnable, delayMs)
    }

    fun cancelSeekPreview() {
        cancelExternalSeekPreview(resetHideCallbacks = true)
    }

    fun isTouchWithinInteractiveArea(x: Float, y: Float): Boolean {
        return isPointInsideView(centerControls, x, y) ||
            isPointInsideView(bottomBar, x, y) ||
            isPointInsideView(titleContainer, x, y)
    }

    fun previewSeek(targetPositionMs: Long, durationMs: Long, deltaMs: Long) {
        seekPreviewListener?.onSeekPreview(targetPositionMs, durationMs, deltaMs)
        beginSeekPreview(targetPositionMs)
    }

    private fun setButtonVisibility(button: View, show: Boolean) {
        if (controlViewLayoutManager.getShowButton(button) == show) {
            AppLog.d("LiveUI", "setButtonVisibility: skip ${button.transitionName} show=$show (already set)")
            return
        }
        AppLog.d("LiveUI", "setButtonVisibility: ${button.transitionName} show=$show")
        val needsFocusFallback = !show && button.isFocused
        controlViewLayoutManager.setShowButton(button, show)
        if (needsFocusFallback && isVisible()) {
            post { requestPlayPauseFocus() }
        }
    }

    private fun syncManagedButtonVisibility() {
        listOf(
            buttonPlay,
            buttonPrevious,
            buttonNext,
            buttonRewind,
            buttonFastForward,
            buttonDmSwitch,
            buttonSettings,
            buttonChooseEpisode,
            buttonMore,
            buttonUpInfo,
            buttonSubtitle,
            buttonRelated,
            buttonRepeat,
            buttonLiveSettings,
            buttonRefresh,
            buttonClose
        ).forEach { button ->
            controlViewLayoutManager.setShowButton(button, button.visibility == VISIBLE)
        }
    }

    private fun updateButtonEnabled(button: View, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) ENABLED_BUTTON_ALPHA else DISABLED_BUTTON_ALPHA
        if (!enabled && button.isFocused && isVisible()) {
            post { requestPlayPauseFocus() }
        }
    }

    private fun cancelExternalSeekPreview(resetHideCallbacks: Boolean) {
        if (!externalSeekPreviewActive) {
            return
        }
        removeCallbacks(externalSeekPreviewFinishRunnable)
        finishExternalSeekPreview(resetHideCallbacks)
    }

    private fun finishExternalSeekPreview(resetHideCallbacks: Boolean = true) {
        externalSeekPreviewActive = false
        showInfoAfterEndFF()
        if (resetHideCallbacks) {
            resetHideCallbacks()
        }
    }

    private fun isPointInsideView(view: View, x: Float, y: Float): Boolean {
        if (view.visibility != VISIBLE) {
            return false
        }
        val bounds = Rect()
        view.getHitRect(bounds)
        return bounds.contains(x.toInt(), y.toInt())
    }

    fun hideInfoOnlyLeftTimeBar() {
        controlViewLayoutManager.hideInfoOnlyLeftTimeBar()
    }

    fun showInfoAfterEndFF() {
        controlViewLayoutManager.showInfoAfterEndFF()
    }

    fun removeHideCallbacks() {
        controlViewLayoutManager.removeHideCallbacks()
    }

    fun resetHideCallbacks() {
        controlViewLayoutManager.resetHideCallbacks()
    }

    fun setAnimationEnabled(enabled: Boolean) {
        controlViewLayoutManager.setAnimationEnabled(enabled)
    }

    fun setProgressOnlyUiEnabled(enabled: Boolean) {
        controlViewLayoutManager.setProgressOnlyUiEnabled(enabled)
    }

    fun updateAll() {
        updatePlayPauseButton()
        updateNavigation()
        updatePlaybackSpeedList()
        updateTimeline()
        updateLoadingState()
    }

    fun setTimeBarMinUpdateInterval(intervalMs: Int) {
        timeBarMinUpdateIntervalMs = intervalMs.coerceIn(16, 1000)
    }

    fun setShowMultiWindowTimeBar(show: Boolean) {
        showMultiWindowTimeBar = show
        updateTimeline()
    }

    private fun updateNavigation() {
        if (!isVisible() || !attachedToWindow) {
            return
        }
        val p = player
        if (p == null) {
            timeBar.isEnabled = false
            return
        }
        timeBar.isEnabled = p.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
    }

    internal fun isAnyPrimaryControlFocused(): Boolean {
        return focusCoordinator.isAnyPrimaryControlFocused()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        controlViewLayoutManager.onAttachedToWindow()
        attachedToWindow = true
        updateAll()
        if (isFullyVisible()) {
            resetHideCallbacks()
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        controlViewLayoutManager.onLayout(changed, left, top, right, bottom)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        controlViewLayoutManager.onDetachedFromWindow()
        attachedToWindow = false
        handler.removeCallbacks(progressRunnable)
        focusCoordinator.clearPendingFocusStabilization(handler)
        removeHideCallbacks()
    }

    internal fun startProgressUpdates() {
        handler.removeCallbacks(progressRunnable)
        handler.post(progressRunnable)
    }

    internal fun stopProgressUpdates() {
        handler.removeCallbacks(progressRunnable)
    }

    fun notifyOnVisibilityChange() {
        visibilityListeners.forEach { it.onVisibilityChange(visibility) }
    }

    internal fun notifyChromeState(effectiveVisibility: Int) {
        visibilityListeners.forEach { it.onVisibilityChange(effectiveVisibility) }
    }

    fun isVisible(): Boolean = visibility == VISIBLE
}



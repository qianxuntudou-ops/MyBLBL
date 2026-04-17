package com.tutu.myblbl.ui.activity

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.R
import com.tutu.myblbl.core.common.content.ContentFilter
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.core.common.time.TimeUtils
import com.tutu.myblbl.core.ui.base.BaseActivity
import com.tutu.myblbl.core.ui.system.ViewUtils
import com.tutu.myblbl.databinding.FragmentVideoPlayerBinding
import com.tutu.myblbl.event.AppEventHub
import com.tutu.myblbl.feature.player.CdnLatencyProfile
import com.tutu.myblbl.feature.player.PlayerInstancePool
import com.tutu.myblbl.feature.player.PlaybackUiCoordinator
import com.tutu.myblbl.feature.player.PlayerLaunchContext
import com.tutu.myblbl.feature.player.PlayerOverlayCoordinator
import com.tutu.myblbl.feature.player.PlayerSessionCoordinator
import com.tutu.myblbl.feature.player.SeekSession
import com.tutu.myblbl.feature.player.SlimTimelineRenderer
import com.tutu.myblbl.feature.player.VideoPlayerAutoPlayController
import com.tutu.myblbl.feature.player.VideoPlayerOverlayController
import com.tutu.myblbl.feature.player.VideoPlayerProgressCoordinator
import com.tutu.myblbl.feature.player.VideoPlayerResumeHintController
import com.tutu.myblbl.feature.player.VideoPlayerViewModel
import com.tutu.myblbl.feature.player.settings.PlayerSettings
import com.tutu.myblbl.feature.player.settings.PlayerSettingsStore
import com.tutu.myblbl.feature.player.view.InteractionVideoHandleView
import com.tutu.myblbl.feature.player.view.MyPlayerView
import com.tutu.myblbl.feature.player.view.OnPlayerSettingChange
import com.tutu.myblbl.feature.player.view.OnVideoSettingChangeListener
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.model.video.detail.VideoDetailModel
import com.tutu.myblbl.model.video.quality.AudioQuality
import com.tutu.myblbl.model.video.quality.VideoCodecEnum
import com.tutu.myblbl.model.video.quality.VideoQuality
import com.tutu.myblbl.ui.adapter.VideoAdapter
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.io.Serializable
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

private const val RISK_CONTROL_USER_HINT = "账号被风控了，请到设置中完成验证"
private val riskControlUserHintShown = AtomicBoolean(false)

@UnstableApi
class PlayerActivity : BaseActivity<FragmentVideoPlayerBinding>() {

    companion object {
        private const val TAG = "PlayerActivity"
        private const val EXTRA_LAUNCH_CONTEXT = "player_launch_context"

        fun start(
            context: Context,
            aid: Long = 0L,
            bvid: String = "",
            cid: Long = 0L,
            epId: Long = 0L,
            seasonId: Long = 0L,
            seekPositionMs: Long = 0L,
            initialVideo: VideoModel? = null,
            playQueue: List<VideoModel> = emptyList(),
            startEpisodeIndex: Int = -1
        ) {
            val launchContext = PlayerLaunchContext.create(
                aid = aid,
                bvid = bvid,
                cid = cid,
                epId = epId,
                seasonId = seasonId,
                seekPositionMs = seekPositionMs,
                initialVideo = initialVideo,
                playQueue = playQueue,
                startEpisodeIndex = startEpisodeIndex
            )
            context.startActivity(Intent(context, PlayerActivity::class.java).apply {
                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_LAUNCH_CONTEXT, launchContext)
            })
        }

        fun start(
            context: Context,
            video: VideoModel,
            seekPositionMs: Long = 0L,
            playQueue: List<VideoModel> = emptyList(),
            startEpisodeIndex: Int = -1
        ) {
            start(
                context = context,
                aid = video.aid,
                bvid = video.bvid,
                cid = video.cid,
                epId = video.playbackEpId,
                seasonId = video.playbackSeasonId,
                seekPositionMs = seekPositionMs,
                initialVideo = video,
                playQueue = playQueue,
                startEpisodeIndex = startEpisodeIndex
            )
        }

        fun start(
            context: Context,
            bvid: String,
            cid: Long,
            epId: Long = 0L,
            seasonId: Long = 0L,
            seekPositionMs: Long = 0L
        ) {
            start(
                context = context,
                bvid = bvid,
                cid = cid,
                epId = epId,
                seasonId = seasonId,
                seekPositionMs = seekPositionMs
            )
        }

        fun buildPlayQueue(items: List<VideoModel>, current: VideoModel): ArrayList<VideoModel> {
            if (items.isEmpty()) {
                return arrayListOf()
            }
            val currentIndex = items.indexOfFirst { isSameVideo(it, current) }
            val queueSource = when {
                currentIndex >= 0 && currentIndex < items.lastIndex -> items.subList(currentIndex + 1, items.size)
                currentIndex >= 0 -> emptyList()
                else -> items.filterNot { isSameVideo(it, current) }
            }
            return ArrayList(queueSource.filter(::isPlayableVideo))
        }

        private fun isPlayableVideo(video: VideoModel): Boolean {
            return video.aid > 0L ||
                video.bvid.isNotBlank() ||
                video.playbackEpId > 0L ||
                video.playbackSeasonId > 0L
        }

        private fun isSameVideo(left: VideoModel, right: VideoModel): Boolean {
            return when {
                left.playbackEpId > 0L && right.playbackEpId > 0L -> left.playbackEpId == right.playbackEpId
                left.bvid.isNotBlank() && right.bvid.isNotBlank() -> left.bvid == right.bvid
                left.aid > 0L && right.aid > 0L -> left.aid == right.aid
                left.cid > 0L && right.cid > 0L -> left.cid == right.cid
                else -> left.title == right.title && left.coverUrl == right.coverUrl
            }
        }
    }

    private val appEventHub: AppEventHub by inject()

    override fun getViewBinding(): FragmentVideoPlayerBinding =
        FragmentVideoPlayerBinding.inflate(layoutInflater)

    private val viewModel: VideoPlayerViewModel by viewModel()

    private var player: ExoPlayer? = null
    private val uiCoordinator = PlaybackUiCoordinator()
    private val overlayCoordinator = PlayerOverlayCoordinator()

    private lateinit var playerView: MyPlayerView
    private lateinit var bottomProgressBar: ProgressBar
    private lateinit var textClock: TextClock
    private lateinit var textSubtitle: TextView
    private lateinit var viewDebug: View
    private lateinit var textDebug: TextView
    private lateinit var viewNext: View
    private lateinit var viewRelated: View
    private lateinit var recyclerViewRelated: RecyclerView
    private lateinit var textMoreTitle: TextView
    private lateinit var buttonCloseRelated: View
    private lateinit var imageNext: AppCompatImageView
    private lateinit var textNext: TextView
    private lateinit var interactionView: InteractionVideoHandleView

    private lateinit var relatedAdapter: VideoAdapter
    private lateinit var autoPlayController: VideoPlayerAutoPlayController
    private lateinit var overlayUiController: VideoPlayerOverlayController
    private lateinit var resumeHintController: VideoPlayerResumeHintController

    private var latestErrorMessage: String? = null
    private var latestLoadingState: Boolean = false
    private var latestVideoInfo: VideoDetailModel? = null
    private lateinit var playerSettings: PlayerSettings
    private var latestControllerVisibility: Int = View.GONE
    private var latestPlaybackPositionMs: Long = 0L
    private var latestPlaybackDurationMs: Long = 0L
    private lateinit var slimTimelineRenderer: SlimTimelineRenderer
    private val sessionCoordinator = PlayerSessionCoordinator()
    private var resumePlaybackWhenStarted: Boolean = false
    private var startupTrace: StartupTrace? = null
    private var startupTraceSequence: Int = 0
    private var suppressPlaybackEnvironmentSync: Boolean = false
    private var lastKeepScreenOnState: Boolean? = null

    private val gaiaVgateLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val gaiaVtoken = result.data?.getStringExtra(GaiaVgateActivity.EXTRA_GAIA_VTOKEN)
            if (!gaiaVtoken.isNullOrBlank()) {
                viewModel.onGaiaVgateResult(gaiaVtoken)
            }
        }
    }

    private val resumePlaybackRunnable = Runnable {
        resumePlaybackIfNeeded(reason = "delayed_resume")
    }

    private val progressCoordinator = VideoPlayerProgressCoordinator(
        playerProvider = { player },
        publishProgressStateProvider = { bottomProgressBar.isVisible },
        onProgressPublished = { positionMs, durationMs, publishProgressState ->
            viewModel.updatePlaybackPosition(positionMs, durationMs, publishProgressState)
        },
        onPlaybackPositionChanged = { positionMs ->
            playerView.syncDanmakuPosition(positionMs)
            interactionView.onPositionUpdate(positionMs)
        },
        onPlaybackStalled = { positionMs, stalledMs ->
            recoverFromPlaybackStall(positionMs, stalledMs)
        }
    )

    private fun cancelResume(): Boolean = resumeHintController.cancelResume()

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    viewModel.setLoading(true)
                    playerView.pauseDanmaku()
                }
                Player.STATE_READY -> {
                    startupTrace
                        ?.takeIf { !it.readyLogged }
                        ?.also {
                            it.readyLogged = true
                            AppLog.d(TAG, "startup trace #${it.sequence}: ready in ${SystemClock.elapsedRealtime() - it.startedAtMs}ms")
                        }
                    viewModel.setLoading(false)
                    if (player?.playWhenReady == true) {
                        playerView.resumeDanmaku()
                    }
                    hideNextPreview()
                }
                Player.STATE_ENDED -> {
                    viewModel.setLoading(false)
                    playerView.stopDanmaku()
                    handlePlaybackEnded()
                }
                Player.STATE_IDLE -> {
                    viewModel.setLoading(false)
                    playerView.pauseDanmaku()
                }
            }
            syncPlaybackEnvironment()
        }

        override fun onPlayerError(error: PlaybackException) {
            startupTrace = null
            viewModel.setLoading(false)
            viewModel.handlePlaybackError(error, player?.currentPosition ?: 0L)
            AppLog.e(TAG, "player error: ${error.message}", error)
            playerView.pauseDanmaku()
            syncPlaybackEnvironment()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                playerView.resumeDanmaku()
                progressCoordinator.restart()
            } else {
                playerView.pauseDanmaku()
                progressCoordinator.stop()
                progressCoordinator.syncNow(publishProgressState = true)
            }
            syncPlaybackEnvironment()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            resumePlaybackWhenStarted = playWhenReady
            syncPlaybackEnvironment()
        }
    }

    private var initialized = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val launchContext = intent.serializableExtraCompat<PlayerLaunchContext>(EXTRA_LAUNCH_CONTEXT)
            ?: PlayerLaunchContext.create()
        AppLog.d(TAG, "cardClick:start aid=${launchContext.aid}, bvid=${launchContext.bvid}, cid=${launchContext.cid}, clickElapsedRealtime=${SystemClock.elapsedRealtime()}")
        AppLog.d(TAG, "handleIntent: aid=${launchContext.aid}, bvid=${launchContext.bvid}, cid=${launchContext.cid}")
        if (
            launchContext.aid <= 0L &&
            launchContext.bvid.isBlank() &&
            launchContext.epId <= 0L &&
            launchContext.seasonId <= 0L
        ) {
            finish()
            return
        }

        if (!initialized) {
            initialized = true
            initViews()
            playerSettings = PlayerSettingsStore.load(this)
            setupAdapters()
            setupOverlayController()
            setupPlayer()
            setupBackHandler()
            setupObservers()
        }

        startPlayback(launchContext)
    }

    private fun startPlayback(launchContext: PlayerLaunchContext) {
        playerView.pauseDanmaku()
        viewModel.loadVideoInfo(
            aid = launchContext.aid,
            bvid = launchContext.bvid,
            cid = launchContext.cid,
            seasonId = launchContext.seasonId,
            epId = launchContext.epId,
            seekPositionMs = launchContext.seekPositionMs,
            startEpisodeIndex = launchContext.startEpisodeIndex
        )
    }

    private fun initViews() {
        playerView = binding.playerView
        playerView.setUiCoordinator(uiCoordinator)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            playerView.defaultFocusHighlightEnabled = false
        }
        bottomProgressBar = binding.bottomProgressBar
        slimTimelineRenderer = SlimTimelineRenderer(bottomProgressBar) {
            ::playerSettings.isInitialized && playerSettings.showBottomProgressBar && latestControllerVisibility != View.VISIBLE
        }
        textClock = binding.textClock
        textSubtitle = binding.textSubtitle
        viewDebug = binding.viewDebug
        textDebug = binding.textDebug
        viewNext = binding.viewNext
        viewRelated = binding.viewRelated
        recyclerViewRelated = binding.recyclerViewRelated
        textMoreTitle = binding.root.findViewById(R.id.title_more)
        buttonCloseRelated = binding.root.findViewById(R.id.button_close_related)
        imageNext = binding.root.findViewById(R.id.imageView_next)
        textNext = binding.root.findViewById(R.id.text_next)
        interactionView = binding.interactionView

        viewDebug.visibility = View.GONE
        viewRelated.visibility = View.GONE
        viewNext.visibility = View.GONE
        textSubtitle.visibility = View.GONE
        interactionView.visibility = View.GONE
        interactionView.setCallback(object : InteractionVideoHandleView.InteractionCallback {
            override fun onPauseVideo() { player?.pause() }
            override fun onJumpToCid(cid: Long, edgeId: Long) {
                player?.pause()
                viewModel.playInteractionChoice(cid, edgeId)
            }
            override fun onGetPlayerView(): View? = playerView
        })
        buttonCloseRelated.setOnClickListener { hideContentPanel() }
        playerView.setTouchInterceptListener { event ->
            if (event.action == MotionEvent.ACTION_DOWN && viewRelated.isVisible) {
                val location = IntArray(2)
                viewRelated.getLocationOnScreen(location)
                event.rawY < location[1]
            } else false
        }
    }

    private fun setupAdapters() {
        relatedAdapter = VideoAdapter(
            itemWidthPx = (resources.displayMetrics.widthPixels / 5).coerceAtLeast(1)
        )
        relatedAdapter.setOnItemClickListener { _, item ->
            hideContentPanel()
            hideNextPreview()
            sessionCoordinator.replacePlayQueue(buildPlayQueue(relatedAdapter.getItemsSnapshot(), item))
            sessionCoordinator.updateCurrentVideo(item)
            viewModel.playRelatedVideo(item)
        }
    }

    private fun setupOverlayController() {
        autoPlayController = VideoPlayerAutoPlayController(
            activity = this,
            viewNext = viewNext,
            imageNext = imageNext,
            textNext = textNext,
            canExecutePendingAction = { player?.playbackState == Player.STATE_ENDED }
        )
        overlayUiController = VideoPlayerOverlayController(
            activity = this,
            playerView = playerView,
            overlayCoordinator = overlayCoordinator,
            uiCoordinator = uiCoordinator,
            sessionCoordinator = sessionCoordinator,
            playerProvider = { player },
            latestVideoInfoProvider = { latestVideoInfo },
            relatedAdapter = relatedAdapter,
            viewRelated = viewRelated,
            recyclerViewRelated = recyclerViewRelated,
            textMoreTitle = textMoreTitle,
            onPlayEpisode = { index -> viewModel.playEpisode(index) },
            onPlayRelatedVideo = { video, playQueue ->
                sessionCoordinator.replacePlayQueue(playQueue)
                sessionCoordinator.updateCurrentVideo(video)
                updatePlaybackPreload()
                viewModel.playRelatedVideo(video)
            },
            onOpenFragmentFromHost = { _, _ -> },
            onHideNextPreview = { autoPlayController.hideNextPreview() },
            isViewActive = { true }
        )
        resumeHintController = VideoPlayerResumeHintController(
            activity = this,
            playerProvider = { player },
            onCancelResume = { viewModel.cancelResumeProgress() },
            onClearResumeHint = { viewModel.clearResumeHint() }
        )
    }

    private fun setupPlayer() {
        player = PlayerInstancePool.acquire(this).also {
            it.playWhenReady = false
            if (::playerSettings.isInitialized) {
                it.playbackParameters = PlaybackParameters(playerSettings.defaultPlaybackSpeed)
            }
            it.addListener(playerListener)
        }
        playerView.setPlayer(player)
        playerView.setRenderEventListener(object : MyPlayerView.RenderEventListener {
            override fun onRenderedFirstFrame() {
                viewModel.onPlaybackFirstFrame()
                startupTrace
                    ?.takeIf { !it.firstFrameLogged }
                    ?.also {
                        it.firstFrameLogged = true
                        AppLog.d(TAG, "startup trace #${it.sequence}: first frame in ${SystemClock.elapsedRealtime() - it.startedAtMs}ms")
                    }
                AppLog.d(TAG, "click->firstFrame: totalElapsedRealtime=${SystemClock.elapsedRealtime()}")
            }
        })
        playerView.setControllerVisibilityListener(object : MyPlayerView.ControllerVisibilityListener {
            override fun onVisibilityChanged(visibility: Int) {
                renderControllerChrome(visibility)
                if (visibility != View.VISIBLE) {
                    progressCoordinator.syncNow(publishProgressState = true)
                }
            }
        })
        playerView.setControllerAutoShow(false)
        playerView.hideController()
        playerView.onResumeProgressCancelled = { cancelResume() }
        playerView.seekPreviewUpdateListener = object : MyPlayerView.SeekPreviewUpdateListener {
            override fun onSeekPreviewUpdated() {
                renderBottomProgressBar()
            }
        }
        playerView.seekSession = SeekSession(
            coordinator = uiCoordinator,
            playerProvider = { player },
            seekPreviewRenderer = { targetMs, durationMs ->
                val controllerHandling = playerView.getController()?.isVisible() == true
                if (controllerHandling) {
                    playerView.getController()?.beginSeekPreview(targetMs)
                }
                // 控制器进度条已处理 preview 时，不更新细进度条，避免 hide/show 互相打架
                if (!controllerHandling && ::slimTimelineRenderer.isInitialized) {
                    slimTimelineRenderer.showPreview(targetMs, durationMs)
                }
            },
            danmakuSync = { positionMs -> playerView.syncDanmakuPosition(positionMs, forceSeek = true) }
        )
        playerView.showSettingButton(false)
        playerView.showHideNextPrevious(false)
        playerView.showHideFfRe(playerSettings.showRewindFastForward)
        playerView.showHideActionButton(false)
        playerView.showHideEpisodeButton(false)
        playerView.showHideRelatedButton(false)
        playerView.showHideDmSwitchButton(false)
        playerView.showHideLiveSettingButton(false)
        playerView.showHideSubtitleButton(false)
        playerView.setShowHideOwnerInfo(false)
        playerView.setRepeatMode(Player.REPEAT_MODE_OFF)
        applyPlayerSettings(playerSettings)
        syncPlaybackEnvironment()
        playerView.setOnPlayerSettingChange(object : OnPlayerSettingChange {
            override fun onVideoQualityChange(quality: VideoQuality) {
                val snapshot = capturePlaybackSnapshot()
                viewModel.selectVideoQuality(quality = quality, currentPositionMs = snapshot.first, playWhenReady = snapshot.second)
                playerView.showHideSettingView(false)
            }
            override fun onAudioQualityChange(quality: AudioQuality) {
                val snapshot = capturePlaybackSnapshot()
                viewModel.selectAudioQuality(quality = quality, currentPositionMs = snapshot.first, playWhenReady = snapshot.second)
                playerView.showHideSettingView(false)
            }
            override fun onPlaybackSpeedChange(speed: Float) { playerView.setPlaySpeed(speed) }
            override fun onSubtitleChange(position: Int) {
                viewModel.selectSubtitle(position)
                playerView.showHideSettingView(false)
            }
            override fun onVideoCodecChange(codec: VideoCodecEnum) {
                val snapshot = capturePlaybackSnapshot()
                viewModel.selectVideoCodec(codec = codec, currentPositionMs = snapshot.first, playWhenReady = snapshot.second)
                playerView.showHideSettingView(false)
            }
            override fun onAspectRatioChange(ratio: Int) {}
        })
        playerView.setOnVideoSettingChangeListener(object : OnVideoSettingChangeListener {
            override fun onPrevious() { viewModel.playPrevious() }
            override fun onNext() { viewModel.playNext() }
            override fun onClose() { finish() }
            override fun onChooseEpisode() { showChooseEpisodeDialog() }
            override fun onRelated() { showRelatedPanel() }
            override fun onUpInfo() { showOwnerDetailDialog() }
            override fun onMore() { showPlayerActionDialog() }
            override fun onVideoInfo() { showVideoInfoDialog() }
            override fun onSubtitle() {
                if (viewModel.subtitles.value.orEmpty().isNotEmpty()) {
                    playerView.showSubtitleSettingView()
                }
            }
            override fun onRepeat() {
                val currentPlayer = player ?: return
                currentPlayer.repeatMode = if (currentPlayer.repeatMode == Player.REPEAT_MODE_ONE) {
                    Player.REPEAT_MODE_OFF
                } else {
                    Player.REPEAT_MODE_ONE
                }
                playerView.setRepeatMode(currentPlayer.repeatMode)
                Toast.makeText(this@PlayerActivity, if (currentPlayer.repeatMode == Player.REPEAT_MODE_ONE) "单集循环" else "顺序播放", Toast.LENGTH_SHORT).show()
            }
            override fun onDmEnableChange(enabled: Boolean) { playerView.setDanmakuEnabled(enabled) }
        })
        renderControllerChrome(View.GONE)
    }

    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                uiCoordinator.handleBackPress(
                    nowMs = System.currentTimeMillis(),
                    isSettingShowing = playerView.isSettingViewShowing(),
                    hideSetting = { playerView.showHideSettingView(false) },
                    isControllerFullyVisible = playerView.isControllerFullyVisible(),
                    hideController = { playerView.hideController() },
                    hidePanel = { hideContentPanel() },
                    exitPlayer = { finish() },
                    showExitPrompt = {
                        Toast.makeText(this@PlayerActivity, "再按一次退出", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        })
    }

    private var preloadHeaderRefreshPosted = false

    private fun setupObservers() {
        lifecycleScope.launch {
            viewModel.playbackRequest.collect { request ->
                val currentPlayer = player ?: return@collect
                val playbackRequest = request ?: return@collect
                viewModel.setErrorMessage(null)
                resumePlaybackWhenStarted = playbackRequest.playWhenReady
                if (playbackRequest.replaceInPlace) {
                    playerView.showController()
                    playerView.removeControllerHideCallbacks()
                }
                progressCoordinator.reset()
                if (!playbackRequest.replaceInPlace) {
                    playerView.prepareForPlaybackTransition()
                }
                startupTrace = StartupTrace(
                    sequence = ++startupTraceSequence,
                    startedAtMs = SystemClock.elapsedRealtime()
                )
                AppLog.d(TAG, "startup trace #$startupTraceSequence: apply playback request replaceInPlace=${playbackRequest.replaceInPlace}")
                playerView.syncDanmakuPosition(playbackRequest.seekPositionMs, forceSeek = true)
                suppressPlaybackEnvironmentSync = true
                try {
                    currentPlayer.playWhenReady = false
                    currentPlayer.stop()
                    currentPlayer.setMediaSource(playbackRequest.mediaSource)
                    currentPlayer.seekTo(playbackRequest.seekPositionMs)
                    currentPlayer.prepare()
                    currentPlayer.playWhenReady = playbackRequest.playWhenReady
                } finally {
                    suppressPlaybackEnvironmentSync = false
                }
                syncPlaybackEnvironment()
            }
        }

        lifecycleScope.launch {
            viewModel.riskControlVVoucher.collect { vVoucher ->
                if (vVoucher.isNullOrBlank()) return@collect
                viewModel.consumeRiskControlVVoucher() ?: return@collect
                AppLog.w(TAG, "risk-control v_voucher received, launching GaiaVgateActivity")
                val intent = Intent(this@PlayerActivity, GaiaVgateActivity::class.java).apply {
                    putExtra(GaiaVgateActivity.EXTRA_V_VOUCHER, vVoucher)
                }
                gaiaVgateLauncher.launch(intent)
            }
        }

        lifecycleScope.launch {
            viewModel.riskControlTryLookBypass.collect { bypassed ->
                if (bypassed != true) return@collect
                if (riskControlUserHintShown.compareAndSet(false, true)) {
                    Toast.makeText(this@PlayerActivity, RISK_CONTROL_USER_HINT, Toast.LENGTH_LONG).show()
                }
            }
        }

        lifecycleScope.launch {
            viewModel.videoInfo.collect { info ->
                latestVideoInfo = info
                sessionCoordinator.updateVideoInfo(info)
                schedulePreloadAndHeaderRefresh()
                updatePrimaryActionVisibility()
            }
        }

        lifecycleScope.launch {
            viewModel.isLoading.collect { loading ->
                latestLoadingState = loading
                renderDebugState()
            }
        }

        lifecycleScope.launch {
            viewModel.error.collect { error ->
                latestErrorMessage = error
                if (!error.isNullOrBlank()) {
                    AppLog.e(TAG, "viewModel error: $error")
                }
                playerView.setCustomErrorMessage(error)
                renderDebugState()
            }
        }

        lifecycleScope.launch { viewModel.currentPosition.collect { positionMs -> latestPlaybackPositionMs = positionMs.coerceAtLeast(0L); renderBottomProgressBar() } }
        lifecycleScope.launch { viewModel.duration.collect { durationMs -> latestPlaybackDurationMs = durationMs.coerceAtLeast(0L); renderBottomProgressBar() } }

        lifecycleScope.launch {
            viewModel.currentSubtitleText.collect { subtitle ->
                val visible = !subtitle.isNullOrBlank()
                textSubtitle.isVisible = visible
                textSubtitle.text = subtitle.orEmpty()
                if (::playerSettings.isInitialized) {
                    textSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, playerSettings.subtitleTextSizePx.toFloat())
                }
            }
        }

        lifecycleScope.launch {
            viewModel.qualities.collect { qualities -> playerView.setQualities(qualities) }
        }
        lifecycleScope.launch {
            viewModel.selectedQuality.collect { quality -> quality?.let(playerView::selectQuality) }
        }
        lifecycleScope.launch {
            viewModel.audioQualities.collect { qualities -> playerView.setAudiosSelect(qualities) }
        }
        lifecycleScope.launch {
            viewModel.selectedAudioQuality.collect { quality -> quality?.let(playerView::selectAudio) }
        }
        lifecycleScope.launch {
            viewModel.videoCodecs.collect { codecs -> playerView.setVideoCodec(codecs) }
        }
        lifecycleScope.launch {
            viewModel.selectedVideoCodec.collect { codec -> codec?.let(playerView::selectVideoCodec) }
        }

        lifecycleScope.launch {
            viewModel.subtitles.collect { subtitles ->
                playerView.setSubtitles(subtitles)
                playerView.showHideSubtitleButton(subtitles.isNotEmpty())
            }
        }
        lifecycleScope.launch {
            viewModel.selectedSubtitleIndex.collect { index ->
                playerView.selectSubtitle(index)
            }
        }

        lifecycleScope.launch {
            viewModel.danmakuUpdates.collect { update ->
                if (update.replace) {
                    playerView.setDanmakuData(update.items)
                } else {
                    playerView.appendDanmakuData(update.items)
                }
            }
        }

        lifecycleScope.launch {
            viewModel.danmaku.collect {
                updateDanmakuSwitchVisibility()
            }
        }

        lifecycleScope.launch {
            viewModel.specialDanmaku.collect { data ->
                playerView.setSpecialDanmakuData(data)
                updateDanmakuSwitchVisibility()
            }
        }

        lifecycleScope.launch {
            viewModel.episodes.collect { episodes ->
                sessionCoordinator.updateEpisodes(episodes)
                schedulePreloadAndHeaderRefresh()
                playerView.showHideEpisodeButton(episodes.isNotEmpty())
                updateEpisodeNavigationVisibility()
            }
        }
        lifecycleScope.launch {
            viewModel.selectedEpisodeIndex.collect { index ->
                sessionCoordinator.updateSelectedEpisodeIndex(index)
                schedulePreloadAndHeaderRefresh()
                updateEpisodeNavigationVisibility()
            }
        }

        lifecycleScope.launch {
            viewModel.relatedVideos.collect { rawRelated ->
                val related = ContentFilter.filterVideos(this@PlayerActivity, rawRelated)
                sessionCoordinator.updateRelatedVideos(related)
                schedulePreloadAndHeaderRefresh()
                relatedAdapter.setData(related)
                playerView.showHideRelatedButton(related.isNotEmpty())
            }
        }

        lifecycleScope.launch {
            viewModel.resumeHint.collect { hint -> resumeHintController.onHintChanged(hint) }
        }

        lifecycleScope.launch {
            viewModel.interactionModel.collect { model ->
                if (model == null) {
                    interactionView.visibility = View.GONE
                    interactionView.removeAllViews()
                } else {
                    interactionView.visibility = View.VISIBLE
                    interactionView.setModel(model)
                }
            }
        }

        lifecycleScope.launch {
            viewModel.videoSnapshot.collect { snapshot ->
                snapshot?.let { playerView.setSeekPreviewSnapshot(it) }
            }
        }

        lifecycleScope.launch {
            viewModel.currentCidLive.collect { cid ->
                interactionView.setCurrentCid(cid)
            }
        }
    }

    // --- Lifecycle ---

    override fun onStart() {
        super.onStart()
        resumePlaybackIfNeeded(reason = "onStart")
        if (resumePlaybackWhenStarted) {
            playerView.resumeDanmaku()
        } else {
            playerView.pauseDanmaku()
        }
        syncPlaybackEnvironment()
        if (player != null) {
            restartProgressUpdates()
        }
        playerView.removeCallbacks(resumePlaybackRunnable)
        playerView.postDelayed(resumePlaybackRunnable, 250L)
    }

    override fun onStop() {
        super.onStop()
        playerView.removeCallbacks(resumePlaybackRunnable)
        progressCoordinator.syncNow()
        val (snapshotPositionMs, snapshotPlayWhenReady) = capturePlaybackSnapshot()
        postPlaybackProgressEvent(snapshotPositionMs)
        viewModel.reportPlaybackHeartbeat()
        viewModel.savePlayerSnapshot()
        resumePlaybackWhenStarted = snapshotPlayWhenReady
        player?.pause()
        player?.playWhenReady = false
        playerView.pauseDanmaku()
        stopProgressUpdates()
        syncPlaybackEnvironment()
    }

    override fun onDestroy() {
        playerView.removeCallbacks(resumePlaybackRunnable)
        stopProgressUpdates()
        resumeHintController.release()
        player?.removeListener(playerListener)
        playerView.destroy()
        playerView.stopDanmaku()
        PlayerInstancePool.softDetach(player)
        lastKeepScreenOnState = false
        ViewUtils.keepScreenOn(this, false)
        viewRelated.clearAnimation()
        player = null
        progressCoordinator.reset()
        super.onDestroy()
    }

    // --- Helper methods (delegated from Fragment logic) ---

    private data class StartupTrace(
        val sequence: Int,
        val startedAtMs: Long,
        var firstFrameLogged: Boolean = false,
        var readyLogged: Boolean = false
    )

    private fun updatePlaybackPreload() {
        viewModel.preloadPlayback(sessionCoordinator.buildPreloadTarget())
    }

    private fun schedulePreloadAndHeaderRefresh() {
        if (preloadHeaderRefreshPosted) return
        preloadHeaderRefreshPosted = true
        binding.root.post {
            preloadHeaderRefreshPosted = false
            updatePlaybackPreload()
            renderPlayerHeader()
        }
    }

    private fun updatePrimaryActionVisibility() {
        val view = latestVideoInfo?.view
        val hasOwner = view?.owner?.mid?.let { it > 0L } == true
        val hasVideoIdentity = (view?.aid ?: 0L) > 0L || !view?.bvid.isNullOrBlank()
        playerView.setShowHideOwnerInfo(hasOwner)
        playerView.showHideActionButton(hasVideoIdentity)
        playerView.showSettingButton(hasVideoIdentity)
    }

    private fun updateDanmakuSwitchVisibility() {
        val hasDanmaku = viewModel.danmaku.value.orEmpty().isNotEmpty() ||
            viewModel.specialDanmaku.value.orEmpty().isNotEmpty()
        playerView.showHideDmSwitchButton(playerSettings.showDanmakuSwitch && hasDanmaku)
    }

    private fun updateEpisodeNavigationVisibility() {
        val episodes = sessionCoordinator.getEpisodes()
        val showNavigation = playerSettings.showNextPrevious && episodes.size > 1
        playerView.showHideNextPrevious(showNavigation)
        playerView.setEpisodeNavigationEnabled(
            previousEnabled = showNavigation && viewModel.hasPreviousEpisode(),
            nextEnabled = showNavigation && viewModel.hasNextEpisode()
        )
    }

    private fun renderPlayerHeader() {
        val video = latestVideoInfo?.view ?: return
        val selectedEpisode = sessionCoordinator.getSelectedEpisode()
        playerView.setTitle(buildHeaderTitle(video.title, selectedEpisode))
        val metaParts = buildList {
            video.owner?.name?.takeIf { it.isNotBlank() }?.let(::add)
            video.stat?.view?.takeIf { it > 0 }?.let { add("${formatCount(it)}播放") }
            if (video.pubDate > 0) {
                add(TimeUtils.formatTime(video.pubDate))
            }
        }
        playerView.setSubTitle(metaParts.joinToString(" · "))
    }

    private fun buildHeaderTitle(
        videoTitle: String,
        selectedEpisode: VideoPlayerViewModel.PlayableEpisode?
    ): String {
        val episodeTitle = selectedEpisode?.title?.trim().orEmpty()
        if (episodeTitle.isBlank() || episodeTitle == videoTitle) {
            return videoTitle
        }
        return "$episodeTitle ｜ $videoTitle"
    }

    private fun renderDebugState() {
        if (!::playerSettings.isInitialized || !playerSettings.showDebugInfo) {
            viewDebug.isVisible = false
            textDebug.text = ""
            return
        }
        when {
            !latestErrorMessage.isNullOrBlank() -> {
                viewDebug.isVisible = true
                textDebug.text = latestErrorMessage
            }
            latestLoadingState -> {
                viewDebug.isVisible = true
                textDebug.text = getString(R.string.loading)
            }
            else -> {
                viewDebug.isVisible = false
                textDebug.text = ""
            }
        }
    }

    private fun formatCount(count: Long): String {
        return when {
            count >= 100000000L -> String.format(Locale.getDefault(), "%.1f亿", count / 100000000.0)
            count >= 10000L -> String.format(Locale.getDefault(), "%.1f万", count / 10000.0)
            else -> count.toString()
        }
    }

    private fun syncPlaybackEnvironment() {
        if (suppressPlaybackEnvironmentSync) {
            return
        }
        val currentPlayer = player
        val keepScreenOn = currentPlayer != null &&
            currentPlayer.playWhenReady &&
            currentPlayer.playbackState != Player.STATE_IDLE &&
            currentPlayer.playbackState != Player.STATE_ENDED
        if (lastKeepScreenOnState == keepScreenOn) {
            return
        }
        lastKeepScreenOnState = keepScreenOn
        ViewUtils.keepScreenOn(this, keepScreenOn)
    }

    private fun renderControllerChrome(visibility: Int = latestControllerVisibility) {
        latestControllerVisibility = visibility
        syncChromeStateToCoordinator(visibility)
        val subtitleBottomMarginRes = when (uiCoordinator.bottomOccupant) {
            PlaybackUiCoordinator.BottomOccupant.FullChrome -> R.dimen.px300
            PlaybackUiCoordinator.BottomOccupant.SlimTimeline -> R.dimen.px80
            PlaybackUiCoordinator.BottomOccupant.BottomPanel -> R.dimen.px300
            PlaybackUiCoordinator.BottomOccupant.None -> R.dimen.px60
        }
        (textSubtitle.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
            val targetMargin = resources.getDimensionPixelSize(subtitleBottomMarginRes)
            if (params.bottomMargin != targetMargin) {
                params.bottomMargin = targetMargin
                textSubtitle.layoutParams = params
            }
        }
        renderBottomProgressBar()
        textClock.visibility = when (uiCoordinator.hudState) {
            PlaybackUiCoordinator.HudState.Chrome -> View.VISIBLE
            else -> View.GONE
        }
    }

    private fun renderBottomProgressBar() {
        if (!::slimTimelineRenderer.isInitialized) {
            bottomProgressBar.isVisible = false
            return
        }
        if (uiCoordinator.seekState != PlaybackUiCoordinator.SeekState.None) {
            slimTimelineRenderer.hide()
        } else {
            slimTimelineRenderer.show(latestPlaybackPositionMs, latestPlaybackDurationMs)
        }
    }

    private fun capturePlaybackSnapshot(): Pair<Long, Boolean> {
        val positionMs = player?.currentPosition?.coerceAtLeast(0L) ?: 0L
        val playWhenReady = player?.playWhenReady ?: resumePlaybackWhenStarted
        return positionMs to playWhenReady
    }

    private fun postPlaybackProgressEvent(positionMs: Long) {
        val info = latestVideoInfo?.view ?: return
        val episodes = sessionCoordinator.getEpisodes()
        val selectedIndex = sessionCoordinator.getSelectedEpisodeIndex()
        if (episodes.isNotEmpty() && selectedIndex in episodes.indices) {
            val episode = episodes[selectedIndex]
            if (episode.epId > 0L) {
                appEventHub.dispatch(
                    AppEventHub.Event.EpisodePlaybackProgressUpdated(
                        episodeId = episode.epId,
                        progressMs = positionMs.coerceAtLeast(0L).plus(1L),
                        episodeIndex = episode.title
                    )
                )
                return
            }
        }
        val progressMs = positionMs.coerceAtLeast(0L).plus(1L)
        appEventHub.dispatch(
            AppEventHub.Event.PlaybackProgressUpdated(
                aid = info.aid,
                cid = info.cid,
                progressMs = progressMs
            )
        )
    }

    private fun resumePlaybackIfNeeded(reason: String) {
        val currentPlayer = player ?: return
        if (!resumePlaybackWhenStarted) {
            return
        }
        if (currentPlayer.playbackState == Player.STATE_ENDED) {
            return
        }
        if (currentPlayer.playbackState == Player.STATE_IDLE) {
            currentPlayer.prepare()
        }
        currentPlayer.playWhenReady = true
        currentPlayer.play()
    }

    private fun restartProgressUpdates() { progressCoordinator.restart() }
    private fun stopProgressUpdates() { progressCoordinator.stop(); autoPlayController.cancelPendingAction() }

    private fun recoverFromPlaybackStall(positionMs: Long, stalledMs: Long) {
        val currentPlayer = player ?: return
        if (stalledMs > 3000L) {
            currentPlayer.seekTo(positionMs + stalledMs)
        }
    }

    private fun handlePlaybackEnded() {
        when (
            val plan = sessionCoordinator.buildContinuationPlan(
                continuePlaybackAfterFinish = playerSettings.continuePlaybackAfterFinish,
                exitPlayerWhenPlaybackFinished = playerSettings.exitPlayerWhenPlaybackFinished,
                hasNextEpisode = viewModel.hasNextEpisode(),
                nextEpisode = viewModel.getNextEpisode(),
                playNextEpisode = { viewModel.playNext() },
                playVideo = {
                    sessionCoordinator.updateCurrentVideo(it)
                    viewModel.playRelatedVideo(it)
                }
            )
        ) {
            is PlayerSessionCoordinator.ContinuationPlan.PlayNextEpisode -> {
                autoPlayController.queueNextAction(plan.title, plan.coverUrl, plan.perform)
            }
            is PlayerSessionCoordinator.ContinuationPlan.PlayVideo -> {
                autoPlayController.queueNextAction(plan.title, plan.coverUrl, plan.perform)
            }
            is PlayerSessionCoordinator.ContinuationPlan.ExitPlayer -> finish()
            is PlayerSessionCoordinator.ContinuationPlan.ShowController -> {
                playerView.showController()
            }
        }
    }

    private fun hideNextPreview() { autoPlayController.hideNextPreview() }

    private fun hideContentPanel() {
        overlayCoordinator.onRelatedPanelHidden()
        viewRelated.visibility = View.GONE
    }

    private fun showChooseEpisodeDialog() { overlayUiController.showChooseEpisodeDialog() }
    private fun showRelatedPanel() { overlayUiController.showRelatedPanel() }
    private fun showOwnerDetailDialog() { overlayUiController.showOwnerDetailDialog() }
    private fun showPlayerActionDialog() { overlayUiController.showPlayerActionDialog() }
    private fun showVideoInfoDialog() { overlayUiController.showVideoInfoDialog() }

    private fun applyPlayerSettings(settings: PlayerSettings) {
        playerView.setPlaySpeed(settings.defaultPlaybackSpeed)
        playerView.setSeekSecond(settings.fastSeekSeconds)
        playerView.setSimpleKeyPressEnabled(settings.simpleKeyPress)
        playerView.setPersistentBottomProgressEnabled(settings.showBottomProgressBar)
        playerView.showHideFfRe(settings.showRewindFastForward)
        textSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, settings.subtitleTextSizePx.toFloat())
        renderDebugState()
        updateEpisodeNavigationVisibility()
        updateDanmakuSwitchVisibility()
        renderControllerChrome()
    }

    private inline fun <reified T : Serializable> Intent.serializableExtraCompat(key: String): T? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getSerializableExtra(key, T::class.java)
        } else {
            @Suppress("DEPRECATION")
            getSerializableExtra(key) as? T
        }
    }

    private fun syncChromeStateToCoordinator(visibility: Int) {
        uiCoordinator.withState { coord ->
            coord.chromeState = when (visibility) {
                View.VISIBLE -> PlaybackUiCoordinator.ChromeState.Full
                else -> PlaybackUiCoordinator.ChromeState.Hidden
            }
            coord.bottomOccupant = when (visibility) {
                View.VISIBLE -> PlaybackUiCoordinator.BottomOccupant.FullChrome
                else -> if (playerSettings.showBottomProgressBar) PlaybackUiCoordinator.BottomOccupant.SlimTimeline else PlaybackUiCoordinator.BottomOccupant.None
            }
            coord.hudState = when (visibility) {
                View.VISIBLE -> PlaybackUiCoordinator.HudState.Chrome
                else -> PlaybackUiCoordinator.HudState.Ambient
            }
        }
    }
}

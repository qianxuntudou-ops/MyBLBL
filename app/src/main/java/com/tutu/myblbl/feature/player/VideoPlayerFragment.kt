package com.tutu.myblbl.feature.player

import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.FragmentVideoPlayerBinding
import com.tutu.myblbl.event.AppEventHub
import com.tutu.myblbl.model.video.detail.VideoDetailModel
import com.tutu.myblbl.model.video.quality.AudioQuality
import com.tutu.myblbl.model.video.quality.VideoCodecEnum
import com.tutu.myblbl.model.video.quality.VideoQuality
import com.tutu.myblbl.ui.activity.GaiaVgateActivity
import com.tutu.myblbl.ui.activity.MainActivity
import com.tutu.myblbl.ui.activity.PlayerActivity
import com.tutu.myblbl.ui.adapter.VideoAdapter
import com.tutu.myblbl.feature.player.view.InteractionVideoHandleView
import com.tutu.myblbl.feature.player.view.MyPlayerView
import com.tutu.myblbl.feature.player.view.OnPlayerSettingChange
import com.tutu.myblbl.feature.player.view.OnVideoSettingChangeListener
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.core.common.content.ContentFilter
import com.tutu.myblbl.feature.player.settings.PlayerSettings
import com.tutu.myblbl.feature.player.settings.PlayerSettingsStore
import com.tutu.myblbl.core.common.time.TimeUtils
import com.tutu.myblbl.core.ui.navigation.navigateBackFromUi
import com.tutu.myblbl.core.ui.system.ViewUtils
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

private const val RISK_CONTROL_USER_HINT = "账号被风控了，请到设置中完成验证"
private val riskControlUserHintShown = AtomicBoolean(false)

@UnstableApi
class VideoPlayerFragment : Fragment() {

    companion object {
        private const val TAG = "VideoPlayerFragment"
        const val ARG_LAUNCH_CONTEXT = "launch_context"
        const val ARG_AID = "aid"
        const val ARG_BVID = "bvid"
        const val ARG_CID = "cid"
        const val ARG_EP_ID = "ep_id"
        const val ARG_SEASON_ID = "season_id"
        const val ARG_SEEK_POSITION_MS = "seek_position_ms"
        const val ARG_INITIAL_VIDEO = "initial_video"
        const val ARG_PLAY_QUEUE = "play_queue"
        const val ARG_START_EPISODE = "start_episode"

        fun newInstance(
            launchContext: PlayerLaunchContext
        ): VideoPlayerFragment {
            return VideoPlayerFragment().apply {
                arguments = Bundle().apply {
                    putSerializable(ARG_LAUNCH_CONTEXT, launchContext)
                }
            }
        }

        fun newInstance(
            aid: Long = 0L,
            bvid: String = "",
            cid: Long = 0L,
            epId: Long = 0L,
            seasonId: Long = 0L,
            seekPositionMs: Long = 0L,
            initialVideo: com.tutu.myblbl.model.video.VideoModel? = null,
            playQueue: List<com.tutu.myblbl.model.video.VideoModel> = emptyList(),
            startEpisodeIndex: Int = -1
        ): VideoPlayerFragment {
            return newInstance(
                PlayerLaunchContext.create(
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
            )
        }
    }

    private var _binding: FragmentVideoPlayerBinding? = null
    private val binding get() = _binding!!

    private val appEventHub: AppEventHub by inject()
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
        if (result.resultCode == androidx.appcompat.app.AppCompatActivity.RESULT_OK) {
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
        publishProgressStateProvider = { _binding != null && bottomProgressBar.isVisible },
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
                            AppLog.d(
                                TAG,
                                "startup trace #${it.sequence}: ready in ${SystemClock.elapsedRealtime() - it.startedAtMs}ms"
                            )
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

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVideoPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initViews(view)
        playerSettings = PlayerSettingsStore.load(requireContext())
        consumeLaunchContext()
        setupAdapters()
        setupOverlayController()
        setupPlayer()
        setupBackHandler()
        setupObservers()

        resolveLaunchContext()?.let { launchContext ->
            if (
                launchContext.aid > 0L ||
                launchContext.bvid.isNotBlank() ||
                launchContext.epId > 0L ||
                launchContext.seasonId > 0L
            ) {
                viewModel.loadVideoInfo(
                    aid = launchContext.aid,
                    bvid = launchContext.bvid,
                    cid = launchContext.cid,
                    seasonId = launchContext.seasonId,
                    epId = launchContext.epId,
                    seekPositionMs = launchContext.seekPositionMs,
                    startEpisodeIndex = launchContext.startEpisodeIndex
                )
            } else {
                val snapshot = viewModel.consumeSavedSnapshot()
                if (snapshot != null) {
                    viewModel.loadVideoInfo(
                        aid = snapshot.aid,
                        bvid = snapshot.bvid,
                        cid = snapshot.cid,
                        seasonId = snapshot.seasonId,
                        epId = snapshot.epId,
                        seekPositionMs = snapshot.seekPositionMs,
                        startEpisodeIndex = snapshot.episodeIndex,
                        preferredQualityId = snapshot.qualityId,
                        preferredAudioQualityId = snapshot.audioQualityId
                    )
                }
            }
        }
    }

    private fun initViews(rootView: View) {
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
        textMoreTitle = rootView.findViewById(R.id.title_more)
        buttonCloseRelated = rootView.findViewById(R.id.button_close_related)
        imageNext = rootView.findViewById(R.id.imageView_next)
        textNext = rootView.findViewById(R.id.text_next)
        interactionView = binding.interactionView

        viewDebug.visibility = View.GONE
        viewRelated.visibility = View.GONE
        viewNext.visibility = View.GONE
        textSubtitle.visibility = View.GONE
        interactionView.visibility = View.GONE
        interactionView.setCallback(object : InteractionVideoHandleView.InteractionCallback {
            override fun onPauseVideo() {
                player?.pause()
            }

            override fun onJumpToCid(cid: Long, edgeId: Long) {
                player?.pause()
                viewModel.playInteractionChoice(cid, edgeId)
            }

            override fun onGetPlayerView(): View? = playerView
        })

        buttonCloseRelated.setOnClickListener {
            hideContentPanel()
        }

        playerView.setTouchInterceptListener { event ->
            if (event.action == MotionEvent.ACTION_DOWN && viewRelated.isVisible) {
                val location = IntArray(2)
                viewRelated.getLocationOnScreen(location)
                if (event.rawY < location[1]) {
                    hideContentPanel()
                    true
                } else {
                    false
                }
            } else {
                false
            }
        }
    }

    private fun setupAdapters() {
        relatedAdapter = VideoAdapter(
            itemWidthPx = (resources.displayMetrics.widthPixels / 5).coerceAtLeast(1)
        )
        relatedAdapter.setOnItemClickListener { _, item ->
            hideContentPanel()
            hideNextPreview()
            sessionCoordinator.replacePlayQueue(PlayerActivity.buildPlayQueue(relatedAdapter.getItemsSnapshot(), item))
            sessionCoordinator.updateCurrentVideo(item)
            viewModel.playRelatedVideo(item)
        }
    }

    private fun setupOverlayController() {
        autoPlayController = VideoPlayerAutoPlayController(
            activity = requireActivity() as androidx.appcompat.app.AppCompatActivity,
            viewNext = viewNext,
            imageNext = imageNext,
            textNext = textNext,
            canExecutePendingAction = { _binding != null && player?.playbackState == Player.STATE_ENDED }
        )
        overlayUiController = VideoPlayerOverlayController(
            activity = requireActivity() as androidx.appcompat.app.AppCompatActivity,
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
            onOpenFragmentFromHost = ::openFragmentFromPlayerHost,
            onHideNextPreview = { autoPlayController.hideNextPreview() },
            isViewActive = { _binding != null }
        )
        resumeHintController = VideoPlayerResumeHintController(
            activity = requireActivity() as androidx.appcompat.app.AppCompatActivity,
            playerProvider = { player },
            onCancelResume = { viewModel.cancelResumeProgress() },
            onClearResumeHint = { viewModel.clearResumeHint() }
        )
    }

    private fun setupPlayer() {
        val startMs = SystemClock.elapsedRealtime()
        player = PlayerInstancePool.acquire(requireContext()).also {
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
                        AppLog.d(
                            TAG,
                            "startup trace #${it.sequence}: first frame in ${SystemClock.elapsedRealtime() - it.startedAtMs}ms"
                        )
                    }
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
        playerView.onResumeProgressCancelled = {
            cancelResume()
        }
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
                val playbackSnapshot = capturePlaybackSnapshot()
                viewModel.selectVideoQuality(
                    quality = quality,
                    currentPositionMs = playbackSnapshot.first,
                    playWhenReady = playbackSnapshot.second
                )
                playerView.showHideSettingView(false)
            }

            override fun onAudioQualityChange(quality: AudioQuality) {
                val playbackSnapshot = capturePlaybackSnapshot()
                viewModel.selectAudioQuality(
                    quality = quality,
                    currentPositionMs = playbackSnapshot.first,
                    playWhenReady = playbackSnapshot.second
                )
                playerView.showHideSettingView(false)
            }

            override fun onPlaybackSpeedChange(speed: Float) {
                playerView.setPlaySpeed(speed)
            }

            override fun onSubtitleChange(position: Int) {
                viewModel.selectSubtitle(position)
                playerView.showHideSettingView(false)
            }

            override fun onVideoCodecChange(codec: VideoCodecEnum) {
                val playbackSnapshot = capturePlaybackSnapshot()
                viewModel.selectVideoCodec(
                    codec = codec,
                    currentPositionMs = playbackSnapshot.first,
                    playWhenReady = playbackSnapshot.second
                )
                playerView.showHideSettingView(false)
            }

            override fun onAspectRatioChange(ratio: Int) {
            }
        })
        playerView.setOnVideoSettingChangeListener(object : OnVideoSettingChangeListener {
            override fun onPrevious() {
                viewModel.playPrevious()
            }

            override fun onNext() {
                viewModel.playNext()
            }

            override fun onClose() {
                AppLog.d(TAG, "controller request close")
                exitPlayerHost()
            }

            override fun onChooseEpisode() {
                showChooseEpisodeDialog()
            }

            override fun onRelated() {
                showRelatedPanel()
            }

            override fun onUpInfo() {
                showOwnerDetailDialog()
            }

            override fun onMore() {
                showPlayerActionDialog()
            }

            override fun onVideoInfo() {
                showVideoInfoDialog()
            }

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
                Toast.makeText(
                    requireContext(),
                    if (currentPlayer.repeatMode == Player.REPEAT_MODE_ONE) "单集循环" else "顺序播放",
                    Toast.LENGTH_SHORT
                ).show()
            }

            override fun onDmEnableChange(enabled: Boolean) {
                playerView.setDanmakuEnabled(enabled)
            }
        })
        renderControllerChrome(View.GONE)
    }

    private fun updatePlaybackPreload() {
        viewModel.preloadPlayback(sessionCoordinator.buildPreloadTarget())
    }

    private fun setupBackHandler() {
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (cancelResume()) {
                        AppLog.d(TAG, "onBackPressed: cancelled resume progress")
                        return
                    }
                    AppLog.d(
                        TAG,
                        "onBackPressed: panelVisible=${uiCoordinator.hasVisiblePanel()}, setting=${playerView.isSettingViewShowing()}"
                    )
                    uiCoordinator.handleBackPress(
                        nowMs = System.currentTimeMillis(),
                        isSettingShowing = playerView.isSettingViewShowing(),
                        hideSetting = { playerView.showHideSettingView(false) },
                        isControllerFullyVisible = playerView.isControllerFullyVisible(),
                        hideController = { playerView.hideController() },
                        hidePanel = { hideContentPanel() },
                        exitPlayer = {
                            isEnabled = false
                            exitPlayerHost()
                            isEnabled = true
                        },
                        showExitPrompt = {
                            Toast.makeText(requireContext(), R.string.video_play_exit, Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        )
    }

    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
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
                AppLog.d(
                    TAG,
                    "startup trace #$startupTraceSequence: apply playback request replaceInPlace=${playbackRequest.replaceInPlace}, seek=${playbackRequest.seekPositionMs}, playWhenReady=${playbackRequest.playWhenReady}"
                )
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

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.riskControlVVoucher.collect { vVoucher ->
                        if (vVoucher.isNullOrBlank()) return@collect
                        viewModel.consumeRiskControlVVoucher() ?: return@collect
                        AppLog.w(TAG, "risk-control v_voucher received, launching GaiaVgateActivity")
                        val intent = Intent(requireContext(), GaiaVgateActivity::class.java).apply {
                            putExtra(GaiaVgateActivity.EXTRA_V_VOUCHER, vVoucher)
                        }
                        gaiaVgateLauncher.launch(intent)
                    }
                }

                launch {
                    viewModel.riskControlTryLookBypass.collect { bypassed ->
                        if (bypassed != true) return@collect
                        if (riskControlUserHintShown.compareAndSet(false, true)) {
                            Toast.makeText(requireContext(), RISK_CONTROL_USER_HINT, Toast.LENGTH_LONG).show()
                        }
                    }
                }

                launch {
                    viewModel.videoInfo.collect { info ->
                        latestVideoInfo = info
                        sessionCoordinator.updateVideoInfo(info)
                        schedulePreloadAndHeaderRefresh()
                        updatePrimaryActionVisibility()
                    }
                }

                launch {
                    viewModel.resumeHint.collect { hint ->
                        resumeHintController.onHintChanged(hint)
                    }
                }

                launch {
                    viewModel.qualities.collect { qualities ->
                        playerView.setQualities(qualities)
                    }
                }

                launch {
                    viewModel.selectedQuality.collect { quality ->
                        quality?.let(playerView::selectQuality)
                    }
                }

                launch {
                    viewModel.audioQualities.collect { qualities ->
                        playerView.setAudiosSelect(qualities)
                    }
                }

                launch {
                    viewModel.selectedAudioQuality.collect { quality ->
                        quality?.let(playerView::selectAudio)
                    }
                }

                launch {
                    viewModel.videoCodecs.collect { codecs ->
                        playerView.setVideoCodec(codecs)
                    }
                }

                launch {
                    viewModel.selectedVideoCodec.collect { codec ->
                        codec?.let(playerView::selectVideoCodec)
                    }
                }

                launch {
                    viewModel.subtitles.collect { subtitles ->
                        playerView.setSubtitles(subtitles)
                        playerView.showHideSubtitleButton(subtitles.isNotEmpty())
                    }
                }

                launch {
                    viewModel.selectedSubtitleIndex.collect { index ->
                        playerView.selectSubtitle(index)
                    }
                }

                launch {
                    viewModel.currentSubtitleText.collect { subtitle ->
                        val visible = !subtitle.isNullOrBlank()
                        textSubtitle.isVisible = visible
                        textSubtitle.text = subtitle.orEmpty()
                        if (::playerSettings.isInitialized) {
                            textSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, playerSettings.subtitleTextSizePx.toFloat())
                        }
                    }
                }

                launch {
                    viewModel.danmakuUpdates.collect { update ->
                        if (update.replace) {
                            playerView.setDanmakuData(update.items)
                        } else {
                            playerView.appendDanmakuData(update.items)
                        }
                    }
                }

                launch {
                    viewModel.danmaku.collect {
                        updateDanmakuSwitchVisibility()
                    }
                }

                launch {
                    viewModel.specialDanmaku.collect { data ->
                        playerView.setSpecialDanmakuData(data)
                        updateDanmakuSwitchVisibility()
                    }
                }

                launch {
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

                launch {
                    viewModel.videoSnapshot.collect { snapshot ->
                        playerView.setSeekPreviewSnapshot(snapshot)
                    }
                }

                launch {
                    viewModel.currentCidLive.collect { cid ->
                        interactionView.setCurrentCid(cid)
                    }
                }

                launch {
                    viewModel.episodes.collect { episodes ->
                        sessionCoordinator.updateEpisodes(episodes)
                        schedulePreloadAndHeaderRefresh()
                        playerView.showHideEpisodeButton(episodes.isNotEmpty())
                        updateEpisodeNavigationVisibility()
                    }
                }

                launch {
                    viewModel.selectedEpisodeIndex.collect { index ->
                        sessionCoordinator.updateSelectedEpisodeIndex(index)
                        schedulePreloadAndHeaderRefresh()
                        updateEpisodeNavigationVisibility()
                    }
                }

                launch {
                    viewModel.relatedVideos.collect { rawRelated ->
                        val related = ContentFilter.filterVideos(requireContext(), rawRelated)
                        sessionCoordinator.updateRelatedVideos(related)
                        schedulePreloadAndHeaderRefresh()
                        relatedAdapter.setData(related)
                        playerView.showHideRelatedButton(related.isNotEmpty())
                    }
                }

                launch {
                    viewModel.currentPosition.collect { position ->
                        latestPlaybackPositionMs = position.coerceAtLeast(0L)
                        renderBottomProgressBar()
                    }
                }

                launch {
                    viewModel.duration.collect { duration ->
                        latestPlaybackDurationMs = duration.coerceAtLeast(0L)
                        renderBottomProgressBar()
                    }
                }

                launch {
                    viewModel.isLoading.collect { isLoading ->
                        latestLoadingState = isLoading
                        renderDebugState()
                    }
                }

                launch {
                    viewModel.error.collect { error ->
                        latestErrorMessage = error
                        if (!error.isNullOrBlank()) {
                            AppLog.e(TAG, "viewModel error: $error")
                        }
                        playerView.setCustomErrorMessage(error)
                        renderDebugState()
                    }
                }
            }
        }
    }

    private var preloadHeaderRefreshPosted = false

    private fun schedulePreloadAndHeaderRefresh() {
        if (preloadHeaderRefreshPosted) return
        preloadHeaderRefreshPosted = true
        view?.post {
            preloadHeaderRefreshPosted = false
            updatePlaybackPreload()
            renderPlayerHeader()
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
        activity?.let { ViewUtils.keepScreenOn(it, keepScreenOn) }
    }

    private fun recoverFromPlaybackStall(positionMs: Long, stalledMs: Long) {
        val currentPlayer = player ?: return
        if (
            currentPlayer.playbackState != Player.STATE_READY ||
            !currentPlayer.playWhenReady
        ) {
            return
        }
        AppLog.w(
            TAG,
            "playback stall detected: position=$positionMs, stalledMs=$stalledMs, isPlaying=${currentPlayer.isPlaying}, bufferedPosition=${currentPlayer.bufferedPosition}"
        )
        if (viewModel.handlePlaybackStall(positionMs, stalledMs)) {
            viewModel.setLoading(true)
            playerView.pauseDanmaku()
            progressCoordinator.restart()
            return
        }
        val snapshotPosition = currentPlayer.currentPosition
        currentPlayer.stop()
        currentPlayer.prepare()
        currentPlayer.seekTo(snapshotPosition.coerceAtLeast(0L))
        currentPlayer.play()
        playerView.syncDanmakuPosition(snapshotPosition, forceSeek = true)
        progressCoordinator.restart()
    }

    private fun Int.toPlaybackStateName(): String {
        return when (this) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> "UNKNOWN($this)"
        }
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

    private fun updateEpisodeNavigationVisibility() {
        val episodes = sessionCoordinator.getEpisodes()
        val showNavigation = playerSettings.showNextPrevious && episodes.size > 1
        playerView.showHideNextPrevious(showNavigation)
        playerView.setEpisodeNavigationEnabled(
            previousEnabled = showNavigation && viewModel.hasPreviousEpisode(),
            nextEnabled = showNavigation && viewModel.hasNextEpisode()
        )
    }

    private fun updateDanmakuSwitchVisibility() {
        val hasDanmaku = viewModel.danmaku.value.orEmpty().isNotEmpty() ||
            viewModel.specialDanmaku.value.orEmpty().isNotEmpty()
        playerView.showHideDmSwitchButton(playerSettings.showDanmakuSwitch && hasDanmaku)
    }

    private fun updatePrimaryActionVisibility() {
        val view = latestVideoInfo?.view
        val hasOwner = view?.owner?.mid?.let { it > 0L } == true
        val hasVideoIdentity = (view?.aid ?: 0L) > 0L || !view?.bvid.isNullOrBlank()
        playerView.setShowHideOwnerInfo(hasOwner)
        playerView.showHideActionButton(hasVideoIdentity)
        playerView.showSettingButton(hasVideoIdentity)
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
        val episodeTitle = selectedEpisode
            ?.title
            ?.trim()
            .orEmpty()
        if (episodeTitle.isBlank() || episodeTitle == videoTitle) {
            return videoTitle
        }
        return "$episodeTitle ｜ $videoTitle"
    }

    private fun restartProgressUpdates() {
        progressCoordinator.restart()
    }

    private fun stopProgressUpdates() {
        progressCoordinator.stop()
        autoPlayController.cancelPendingAction()
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

            PlayerSessionCoordinator.ContinuationPlan.ExitPlayer -> {
                exitPlayerHost()
            }

            PlayerSessionCoordinator.ContinuationPlan.ShowController -> {
                playerView.showController()
            }
        }
    }

    private fun hideNextPreview() {
        autoPlayController.hideNextPreview()
    }

    private fun showChooseEpisodeDialog() {
        overlayUiController.showChooseEpisodeDialog()
    }

    private fun showRelatedPanel() {
        overlayUiController.showRelatedPanel()
    }

    private fun hideContentPanel(restoreFocus: Boolean = true) {
        overlayUiController.hideContentPanel(restoreFocus)
    }

    private fun showVideoInfoDialog() {
        overlayUiController.showVideoInfoDialog()
    }

    private fun consumeLaunchContext() {
        sessionCoordinator.consumeLaunchContext(arguments) ?: return
        renderLaunchHeader()
    }

    private fun resolveLaunchContext(): PlayerLaunchContext? {
        return sessionCoordinator.resolveLaunchContext(arguments)
    }

    private fun renderLaunchHeader() {
        val video = sessionCoordinator.getLaunchVideo() ?: return
        playerView.setTitle(video.title)
        playerView.setSubTitle(
            buildList {
                video.owner?.name?.takeIf { it.isNotBlank() }?.let(::add)
                video.viewCount.takeIf { it > 0L }?.let { add("${formatCount(it)}播放") }
                val publishTime = when {
                    video.pubDate > 0L -> video.pubDate
                    video.createTime > 0L -> video.createTime
                    else -> 0L
                }
                if (publishTime > 0L) {
                    add(TimeUtils.formatTime(publishTime))
                }
            }.joinToString(" · ")
        )
    }

    private fun showPlayerActionDialog() {
        overlayUiController.showPlayerActionDialog()
    }

    private fun exitPlayerHost() {
        navigateBackFromUi(skipNextFocusRestore = activity is MainActivity)
    }

    private fun showOwnerDetailDialog() {
        overlayUiController.showOwnerDetailDialog()
    }

    private fun openFragmentFromPlayerHost(fragment: Fragment, tag: String) {
        val mainActivity = activity as? MainActivity
        if (mainActivity != null) {
            mainActivity.openInHostContainer(fragment)
            return
        }
        parentFragmentManager.commit {
            replace(R.id.player_container, fragment, tag)
            addToBackStack(tag)
        }
    }

    private fun formatCount(count: Long): String {
        return when {
            count >= 100000000L -> String.format(Locale.getDefault(), "%.1f亿", count / 100000000.0)
            count >= 10000L -> String.format(Locale.getDefault(), "%.1f万", count / 10000.0)
            else -> count.toString()
        }
    }

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
        player?.playWhenReady = false
        playerView.pauseDanmaku()
        stopProgressUpdates()
        syncPlaybackEnvironment()
    }

    override fun onDestroyView() {
        playerView.removeCallbacks(resumePlaybackRunnable)
        stopProgressUpdates()
        resumeHintController.release()
        player?.removeListener(playerListener)
        playerView.destroy()
        playerView.stopDanmaku()
        PlayerInstancePool.softDetach(player)
        lastKeepScreenOnState = false
        activity?.let { ViewUtils.keepScreenOn(it, false) }
        viewRelated.clearAnimation()
        player = null
        progressCoordinator.reset()
        _binding = null
        super.onDestroyView()
    }

    private fun capturePlaybackSnapshot(): Pair<Long, Boolean> {
        val currentPlayer = player
        val positionMs = currentPlayer?.currentPosition?.coerceAtLeast(0L)
            ?: viewModel.currentPosition.value
            ?: 0L
        val playWhenReady = currentPlayer?.playWhenReady ?: resumePlaybackWhenStarted
        return positionMs to playWhenReady
    }

    private data class StartupTrace(
        val sequence: Int,
        val startedAtMs: Long,
        var readyLogged: Boolean = false,
        var firstFrameLogged: Boolean = false
    )

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

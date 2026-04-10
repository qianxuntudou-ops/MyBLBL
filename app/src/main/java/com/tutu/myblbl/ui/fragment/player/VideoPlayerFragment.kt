package com.tutu.myblbl.ui.fragment.player

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
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.FragmentVideoPlayerBinding
import com.tutu.myblbl.model.video.detail.VideoDetailModel
import com.tutu.myblbl.model.video.quality.AudioQuality
import com.tutu.myblbl.model.video.quality.VideoCodecEnum
import com.tutu.myblbl.model.video.quality.VideoQuality
import com.tutu.myblbl.ui.activity.MainActivity
import com.tutu.myblbl.ui.activity.PlayerActivity
import com.tutu.myblbl.ui.adapter.VideoAdapter
import com.tutu.myblbl.ui.view.player.InteractionVideoHandleView
import com.tutu.myblbl.ui.view.player.MyPlayerView
import com.tutu.myblbl.ui.view.player.OnPlayerSettingChange
import com.tutu.myblbl.ui.view.player.OnVideoSettingChangeListener
import com.tutu.myblbl.utils.AppLog
import com.tutu.myblbl.utils.ContentFilter
import com.tutu.myblbl.utils.PlayerSettings
import com.tutu.myblbl.utils.PlayerSettingsStore
import com.tutu.myblbl.utils.TimeUtils
import com.tutu.myblbl.utils.ViewUtils
import org.greenrobot.eventbus.EventBus
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.util.Locale

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

    private val viewModel: VideoPlayerViewModel by viewModel()

    private var player: ExoPlayer? = null
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
    private var bottomProgressSeekPreviewActive: Boolean = false
    private var bottomProgressSeekPreviewPositionMs: Long = 0L
    private var bottomProgressSeekPreviewDurationMs: Long = 0L
    private val sessionCoordinator = PlayerSessionCoordinator()
    private var resumePlaybackWhenStarted: Boolean = false
    private var playbackTraceSessionId: Long = 0L
    private var pendingPlaybackOriginElapsedMs: Long = 0L
    private var currentPlaybackOriginElapsedMs: Long = 0L
    private var currentPlaybackRequestElapsedMs: Long = 0L
    private var didLogReadyForCurrentSession = false
    private var didLogFirstFrameForCurrentSession = false
    private var didLogFirstDanmakuForCurrentSession = false
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
            AppLog.d(
                TAG,
                "onPlaybackStateChanged: state=${playbackState.toPlaybackStateName()}, playWhenReady=${player?.playWhenReady}, isPlaying=${player?.isPlaying}, position=${player?.currentPosition}"
            )
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    viewModel.setLoading(true)
                    playerView.pauseDanmaku()
                }
                Player.STATE_READY -> {
                    viewModel.setLoading(false)
                    logPlaybackTraceEvent(
                        event = "state_ready",
                        onceFlag = didLogReadyForCurrentSession
                    ) {
                        didLogReadyForCurrentSession = true
                    }
                    if (player?.playWhenReady == true) {
                        playerView.resumeDanmaku()
                    }
                    hideNextPreview()
                    if (::playerSettings.isInitialized) {
                        playerView.setPlaySpeed(playerSettings.defaultPlaybackSpeed)
                    }
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
            viewModel.setLoading(false)
            viewModel.handlePlaybackError(error, player?.currentPosition ?: 0L)
            AppLog.e(TAG, "player error: ${error.message}", error)
            playerView.pauseDanmaku()
            syncPlaybackEnvironment()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            AppLog.d(
                TAG,
                "onIsPlayingChanged: isPlaying=$isPlaying, state=${player?.playbackState?.toPlaybackStateName()}, playWhenReady=${player?.playWhenReady}, position=${player?.currentPosition}"
            )
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
            AppLog.d(
                TAG,
                "onPlayWhenReadyChanged: playWhenReady=$playWhenReady, reason=$reason, state=${player?.playbackState?.toPlaybackStateName()}, position=${player?.currentPosition}"
            )
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
                AppLog.d(
                    TAG,
                    "load launchContext: aid=${launchContext.aid}, bvid=${launchContext.bvid}, cid=${launchContext.cid}, epId=${launchContext.epId}, seasonId=${launchContext.seasonId}, seek=${launchContext.seekPositionMs}, requestElapsedMs=${launchContext.requestElapsedMs}"
                )
                pendingPlaybackOriginElapsedMs = launchContext.requestElapsedMs
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
        }
    }

    private fun initViews(rootView: View) {
        playerView = binding.playerView
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            playerView.defaultFocusHighlightEnabled = false
        }
        bottomProgressBar = binding.bottomProgressBar
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
            fragment = this,
            viewNext = viewNext,
            imageNext = imageNext,
            textNext = textNext,
            canExecutePendingAction = { _binding != null && player?.playbackState == Player.STATE_ENDED }
        )
        overlayUiController = VideoPlayerOverlayController(
            fragment = this,
            playerView = playerView,
            overlayCoordinator = overlayCoordinator,
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
                viewModel.playRelatedVideo(video)
            },
            onOpenFragmentFromHost = ::openFragmentFromPlayerHost,
            onHideNextPreview = { autoPlayController.hideNextPreview() },
            isViewActive = { _binding != null }
        )
        resumeHintController = VideoPlayerResumeHintController(
            fragment = this,
            playerProvider = { player },
            onCancelResume = { viewModel.cancelResumeProgress() },
            onClearResumeHint = { viewModel.clearResumeHint() }
        )
    }

    private fun setupPlayer() {
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
                logPlaybackTraceEvent(
                    event = "first_frame",
                    onceFlag = didLogFirstFrameForCurrentSession
                ) {
                    didLogFirstFrameForCurrentSession = true
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
        playerView.setSeekPreviewListener(object : MyPlayerView.SeekPreviewListener {
            override fun onSeekPreviewStateChanged(active: Boolean, positionMs: Long, durationMs: Long) {
                bottomProgressSeekPreviewActive = active
                bottomProgressSeekPreviewPositionMs = positionMs
                bottomProgressSeekPreviewDurationMs = durationMs
                renderBottomProgressBar()
            }
        })
        playerView.setControllerAutoShow(false)
        playerView.hideController()
        playerView.onResumeProgressCancelled = {
            cancelResume()
        }
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
                requireActivity().onBackPressedDispatcher.onBackPressed()
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
                        "onBackPressed: panelVisible=${overlayCoordinator.hasVisiblePanel()}, setting=${playerView.isSettingViewShowing()}"
                    )
                    overlayCoordinator.handleBackPress(
                        nowMs = System.currentTimeMillis(),
                        isSettingShowing = playerView.isSettingViewShowing(),
                        hideSetting = { playerView.showHideSettingView(false) },
                        isControllerFullyVisible = playerView.isControllerFullyVisible(),
                        hideController = { playerView.hideController() },
                        hidePanel = { hideContentPanel() },
                        exitPlayer = {
                            (activity as? MainActivity)?.skipNextFocusRestore()
                            isEnabled = false
                            requireActivity().onBackPressedDispatcher.onBackPressed()
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
        viewModel.videoInfo.observe(viewLifecycleOwner) { info ->
            latestVideoInfo = info
            sessionCoordinator.updateVideoInfo(info)
            updatePrimaryActionVisibility()
            renderPlayerHeader()
        }

        viewModel.playbackRequest.observe(viewLifecycleOwner) { request ->
            val currentPlayer = player ?: return@observe
            val playbackRequest = request ?: return@observe
            viewModel.setErrorMessage(null)
            resumePlaybackWhenStarted = playbackRequest.playWhenReady
            AppLog.d(
                TAG,
                "apply playback request: seek=${playbackRequest.seekPositionMs}, playWhenReady=${playbackRequest.playWhenReady}, inPlace=${playbackRequest.replaceInPlace}"
            )
            beginPlaybackTrace(playbackRequest)

            if (playbackRequest.replaceInPlace) {
                playerView.showController()
                playerView.removeControllerHideCallbacks()
            }
            progressCoordinator.reset()
            if (!playbackRequest.replaceInPlace) {
                playerView.prepareForPlaybackTransition()
            }
            playerView.syncDanmakuPosition(playbackRequest.seekPositionMs, forceSeek = true)
            currentPlayer.playWhenReady = false
            currentPlayer.setMediaSource(playbackRequest.mediaSource)
            currentPlayer.seekTo(playbackRequest.seekPositionMs)
            currentPlayer.prepare()
            currentPlayer.playWhenReady = playbackRequest.playWhenReady
            
            if (::playerSettings.isInitialized) {
                playerView.setPlaySpeed(playerSettings.defaultPlaybackSpeed)
            }
        }

        viewModel.resumeHint.observe(viewLifecycleOwner) { hint ->
            resumeHintController.onHintChanged(hint)
        }

        viewModel.qualities.observe(viewLifecycleOwner) { qualities ->
            playerView.setQualities(qualities)
        }

        viewModel.selectedQuality.observe(viewLifecycleOwner) { quality ->
            quality?.let(playerView::selectQuality)
        }

        viewModel.audioQualities.observe(viewLifecycleOwner) { qualities ->
            playerView.setAudiosSelect(qualities)
        }

        viewModel.selectedAudioQuality.observe(viewLifecycleOwner) { quality ->
            quality?.let(playerView::selectAudio)
        }

        viewModel.videoCodecs.observe(viewLifecycleOwner) { codecs ->
            playerView.setVideoCodec(codecs)
        }

        viewModel.selectedVideoCodec.observe(viewLifecycleOwner) { codec ->
            codec?.let(playerView::selectVideoCodec)
        }

        viewModel.subtitles.observe(viewLifecycleOwner) { subtitles ->
            playerView.setSubtitles(subtitles)
            playerView.showHideSubtitleButton(subtitles.isNotEmpty())
        }

        viewModel.selectedSubtitleIndex.observe(viewLifecycleOwner) { index ->
            playerView.selectSubtitle(index)
        }

        viewModel.currentSubtitleText.observe(viewLifecycleOwner) { subtitle ->
            val visible = !subtitle.isNullOrBlank()
            textSubtitle.isVisible = visible
            textSubtitle.text = subtitle.orEmpty()
            if (::playerSettings.isInitialized) {
                textSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, playerSettings.subtitleTextSizePx.toFloat())
            }
        }

        viewModel.danmakuUpdates.observe(viewLifecycleOwner) { update ->
            if (update.items.isNotEmpty() && !didLogFirstDanmakuForCurrentSession) {
                logPlaybackTraceEvent(
                    event = "first_danmaku_applied",
                    onceFlag = didLogFirstDanmakuForCurrentSession
                ) {
                    didLogFirstDanmakuForCurrentSession = true
                }
            }
            if (update.replace) {
                playerView.setDanmakuData(update.items)
            } else {
                playerView.appendDanmakuData(update.items)
            }
        }

        viewModel.danmaku.observe(viewLifecycleOwner) {
            updateDanmakuSwitchVisibility()
        }

        viewModel.specialDanmaku.observe(viewLifecycleOwner) { data ->
            if (data.isNotEmpty() && !didLogFirstDanmakuForCurrentSession) {
                logPlaybackTraceEvent(
                    event = "first_danmaku_applied",
                    onceFlag = didLogFirstDanmakuForCurrentSession
                ) {
                    didLogFirstDanmakuForCurrentSession = true
                }
            }
            playerView.setSpecialDanmakuData(data)
            updateDanmakuSwitchVisibility()
        }

        viewModel.interactionModel.observe(viewLifecycleOwner) { model ->
            if (model == null) {
                interactionView.visibility = View.GONE
                interactionView.removeAllViews()
            } else {
                interactionView.visibility = View.VISIBLE
                interactionView.setModel(model)
            }
        }

        viewModel.videoSnapshot.observe(viewLifecycleOwner) { snapshot ->
            playerView.setSeekPreviewSnapshot(snapshot)
        }

        viewModel.currentCidLive.observe(viewLifecycleOwner) { cid ->
            interactionView.setCurrentCid(cid)
        }

        viewModel.episodes.observe(viewLifecycleOwner) { episodes ->
            sessionCoordinator.updateEpisodes(episodes)
            playerView.showHideEpisodeButton(episodes.isNotEmpty())
            updateEpisodeNavigationVisibility()
            renderPlayerHeader()
        }

        viewModel.selectedEpisodeIndex.observe(viewLifecycleOwner) { index ->
            sessionCoordinator.updateSelectedEpisodeIndex(index)
            updateEpisodeNavigationVisibility()
            renderPlayerHeader()
        }

        viewModel.relatedVideos.observe(viewLifecycleOwner) { rawRelated ->
            val related = ContentFilter.filterVideos(requireContext(), rawRelated)
            sessionCoordinator.updateRelatedVideos(related)
            relatedAdapter.setData(related)
            playerView.showHideRelatedButton(related.isNotEmpty())
        }

        viewModel.currentPosition.observe(viewLifecycleOwner) { position ->
            latestPlaybackPositionMs = position.coerceAtLeast(0L)
            renderBottomProgressBar()
        }

        viewModel.duration.observe(viewLifecycleOwner) { duration ->
            latestPlaybackDurationMs = duration.coerceAtLeast(0L)
            renderBottomProgressBar()
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            latestLoadingState = isLoading
            renderDebugState()
        }

        viewModel.error.observe(viewLifecycleOwner) { error ->
            latestErrorMessage = error
            if (!error.isNullOrBlank()) {
                AppLog.e(TAG, "viewModel error: $error")
            }
            playerView.setCustomErrorMessage(error)
            renderDebugState()
        }
    }

    private fun syncPlaybackEnvironment() {
        val currentPlayer = player
        val keepScreenOn = currentPlayer != null &&
            currentPlayer.playWhenReady &&
            currentPlayer.playbackState != Player.STATE_IDLE &&
            currentPlayer.playbackState != Player.STATE_ENDED
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
        currentPlayer.seekTo(positionMs.coerceAtLeast(0L))
        currentPlayer.play()
        playerView.syncDanmakuPosition(positionMs, forceSeek = true)
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

    private fun beginPlaybackTrace(playbackRequest: VideoPlayerViewModel.PlaybackRequest) {
        playbackTraceSessionId += 1L
        currentPlaybackRequestElapsedMs = SystemClock.elapsedRealtime()
        currentPlaybackOriginElapsedMs = pendingPlaybackOriginElapsedMs
            .takeIf { it > 0L }
            ?: currentPlaybackRequestElapsedMs
        pendingPlaybackOriginElapsedMs = 0L
        didLogReadyForCurrentSession = false
        didLogFirstFrameForCurrentSession = false
        didLogFirstDanmakuForCurrentSession = false
        AppLog.d(
            TAG,
            buildPlaybackTraceMessage(
                event = "playback_request",
                extra = "seek=${playbackRequest.seekPositionMs}, inPlace=${playbackRequest.replaceInPlace}"
            )
        )
    }

    private fun logPlaybackTraceEvent(
        event: String,
        onceFlag: Boolean,
        markLogged: () -> Unit
    ) {
        if (onceFlag || currentPlaybackRequestElapsedMs <= 0L) {
            return
        }
        markLogged()
        AppLog.d(TAG, buildPlaybackTraceMessage(event))
    }

    private fun buildPlaybackTraceMessage(event: String, extra: String = ""): String {
        val now = SystemClock.elapsedRealtime()
        val fromOriginMs = (now - currentPlaybackOriginElapsedMs).coerceAtLeast(0L)
        val fromRequestMs = (now - currentPlaybackRequestElapsedMs).coerceAtLeast(0L)
        val suffix = if (extra.isBlank()) "" else ", $extra"
        return "perf session=$playbackTraceSessionId event=$event fromOrigin=${fromOriginMs}ms fromRequest=${fromRequestMs}ms$suffix"
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
        if (visibility == View.VISIBLE) {
            clearBottomProgressSeekPreview()
        }
        val subtitleBottomMarginRes = if (visibility == View.VISIBLE) {
            R.dimen.px300
        } else {
            R.dimen.px60
        }
        (textSubtitle.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
            val targetMargin = resources.getDimensionPixelSize(subtitleBottomMarginRes)
            if (params.bottomMargin != targetMargin) {
                params.bottomMargin = targetMargin
                textSubtitle.layoutParams = params
            }
        }
        renderBottomProgressBar()
        textClock.visibility = visibility
    }

    private fun renderBottomProgressBar() {
        if (!::playerSettings.isInitialized) {
            bottomProgressBar.isVisible = false
            return
        }
        val shouldShow = playerSettings.showBottomProgressBar && latestControllerVisibility != View.VISIBLE
        bottomProgressBar.isVisible = shouldShow
        if (!shouldShow) {
            return
        }
        val durationMs = if (bottomProgressSeekPreviewActive) {
            bottomProgressSeekPreviewDurationMs
        } else {
            latestPlaybackDurationMs
        }
        val positionMs = if (bottomProgressSeekPreviewActive) {
            bottomProgressSeekPreviewPositionMs
        } else {
            latestPlaybackPositionMs
        }
        val safeDuration = durationMs.coerceAtLeast(0L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val safePosition = positionMs.coerceAtLeast(0L)
            .coerceAtMost(safeDuration.toLong())
            .toInt()
        bottomProgressBar.max = safeDuration
        bottomProgressBar.progress = safePosition
    }

    private fun clearBottomProgressSeekPreview() {
        if (!bottomProgressSeekPreviewActive &&
            bottomProgressSeekPreviewPositionMs == 0L &&
            bottomProgressSeekPreviewDurationMs == 0L
        ) {
            return
        }
        bottomProgressSeekPreviewActive = false
        bottomProgressSeekPreviewPositionMs = 0L
        bottomProgressSeekPreviewDurationMs = 0L
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
                requireActivity().onBackPressedDispatcher.onBackPressed()
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
        AppLog.d(TAG, "onStart")
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
        AppLog.d(TAG, "onStop")
        playerView.removeCallbacks(resumePlaybackRunnable)
        progressCoordinator.syncNow()
        val (snapshotPositionMs, snapshotPlayWhenReady) = capturePlaybackSnapshot()
        postPlaybackProgressEvent(snapshotPositionMs)
        viewModel.reportPlaybackHeartbeat()
        resumePlaybackWhenStarted = snapshotPlayWhenReady
        player?.playWhenReady = false
        playerView.pauseDanmaku()
        stopProgressUpdates()
        syncPlaybackEnvironment()
    }

    override fun onDestroyView() {
        AppLog.d(TAG, "onDestroyView")
        playerView.removeCallbacks(resumePlaybackRunnable)
        stopProgressUpdates()
        resumeHintController.release()
        player?.removeListener(playerListener)
        playerView.destroy()
        playerView.stopDanmaku()
        PlayerInstancePool.detach(player, allowReuse = true)
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

    private fun resumePlaybackIfNeeded(reason: String) {
        val currentPlayer = player ?: return
        if (!resumePlaybackWhenStarted) {
            AppLog.d(
                TAG,
                "resumePlaybackIfNeeded skipped: reason=$reason, resumePlaybackWhenStarted=false"
            )
            return
        }
        if (currentPlayer.playbackState == Player.STATE_ENDED) {
            AppLog.d(
                TAG,
                "resumePlaybackIfNeeded skipped: reason=$reason, playbackState=ENDED"
            )
            return
        }
        if (currentPlayer.playbackState == Player.STATE_IDLE) {
            currentPlayer.prepare()
        }
        currentPlayer.playWhenReady = true
        currentPlayer.play()
        AppLog.d(
            TAG,
            "resumePlaybackIfNeeded applied: reason=$reason, state=${currentPlayer.playbackState.toPlaybackStateName()}, position=${currentPlayer.currentPosition}"
        )
    }

    private fun postPlaybackProgressEvent(positionMs: Long) {
        val info = latestVideoInfo?.view ?: return
        val episodes = sessionCoordinator.getEpisodes()
        val selectedIndex = sessionCoordinator.getSelectedEpisodeIndex()
        if (episodes.isNotEmpty() && selectedIndex in episodes.indices) {
            val episode = episodes[selectedIndex]
            if (episode.epId > 0L) {
                EventBus.getDefault().post(
                    "playEpisode|${episode.epId}|${positionMs.coerceAtLeast(0L).plus(1L)}|${episode.title}"
                )
                return
            }
        }
        val progressMs = positionMs.coerceAtLeast(0L).plus(1L)
        EventBus.getDefault().post("playUgc|${info.aid}|${info.cid}|$progressMs")
    }
}

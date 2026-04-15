@file:Suppress("SpellCheckingInspection")

package com.tutu.myblbl.feature.player

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.MediaSource
import com.google.gson.Gson
import com.tutu.myblbl.core.common.media.VideoCodecSupport
import com.tutu.myblbl.model.dm.AdvancedDanmakuParser
import com.tutu.myblbl.model.dm.DmColorfulStyleParser
import com.tutu.myblbl.model.dm.DmModel
import com.tutu.myblbl.model.dm.SpecialDanmakuModel
import com.tutu.myblbl.model.dm.SpecialDanmakuParser
import com.tutu.myblbl.model.interaction.InteractionModel
import com.tutu.myblbl.model.player.PlayInfoModel
import com.tutu.myblbl.model.player.VideoSnapshotData
import com.tutu.myblbl.model.proto.DmProtoParser
import com.tutu.myblbl.model.proto.DmWebViewReplyProto
import com.tutu.myblbl.model.subtitle.SubtitleData
import com.tutu.myblbl.model.subtitle.SubtitleInfoModel
import com.tutu.myblbl.model.subtitle.SubtitleItem
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.model.video.detail.SubtitleItem as DetailSubtitleItem
import com.tutu.myblbl.model.video.detail.VideoDetailModel
import com.tutu.myblbl.model.video.quality.AudioQuality
import com.tutu.myblbl.model.video.quality.VideoCodecEnum
import com.tutu.myblbl.model.video.quality.VideoQuality
import com.tutu.myblbl.network.api.ApiService
import com.tutu.myblbl.network.security.NetworkSecurityGateway
import com.tutu.myblbl.network.session.NetworkSessionGateway
import com.tutu.myblbl.network.response.Base2Response
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.core.common.settings.AppSettingsDataStore
import com.tutu.myblbl.network.cookie.CookieManager
import com.tutu.myblbl.feature.player.settings.PlayerSettings
import com.tutu.myblbl.feature.player.settings.PlayerSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@UnstableApi
class VideoPlayerViewModel(
    private val apiService: ApiService,
    private val okHttpClient: OkHttpClient,
    private val cookieManager: CookieManager,
    private val sessionGateway: NetworkSessionGateway,
    private val securityGateway: NetworkSecurityGateway,
    private val appSettings: AppSettingsDataStore,
    private val noCookieApiService: ApiService,
    context: Context,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    enum class EpisodeCatalogSource {
        PAGES,
        UGC_SEASON,
        PGC_EPISODES
    }

    companion object {
        private const val TAG = "VideoPlayerViewModel"
        private const val MAX_FALLBACK_ATTEMPTS = 8
        private const val MAX_PLAYINFO_REFRESH_RETRY = 2
        private val verboseDanmakuCandidateLog =
            java.lang.Boolean.getBoolean("myblbl.verbose_danmaku_candidate_log")

        const val SAVED_AID = "saved_player_aid"
        const val SAVED_BVID = "saved_player_bvid"
        const val SAVED_CID = "saved_player_cid"
        const val SAVED_EP_ID = "saved_player_ep_id"
        const val SAVED_SEASON_ID = "saved_player_season_id"
        const val SAVED_EPISODE_INDEX = "saved_player_episode_index"
        const val SAVED_SEEK_POSITION_MS = "saved_player_seek_position_ms"
        const val SAVED_QUALITY_ID = "saved_player_quality_id"
        const val SAVED_AUDIO_QUALITY_ID = "saved_player_audio_quality_id"
        const val SAVED_SUBTITLE_INDEX = "saved_player_subtitle_index"
    }

    data class PlayableEpisode(
        val cid: Long,
        val title: String,
        val panelTitle: String = title,
        val subtitle: String = "",
        val cover: String = "",
        val aid: Long = 0,
        val bvid: String = "",
        val epId: Long = 0L,
        val seasonId: Long = 0L,
        val source: EpisodeCatalogSource = EpisodeCatalogSource.PAGES
    )

    data class PlaybackRequest(
        val mediaSource: MediaSource,
        val seekPositionMs: Long,
        val playWhenReady: Boolean,
        val replaceInPlace: Boolean
    )

    data class ResumeProgressHint(
        val targetPositionMs: Long
    )

    data class DanmakuUpdate(
        val items: List<DmModel>,
        val replace: Boolean
    )

    private data class SpecialDanmakuPayload(
        val regularItems: List<DmModel>,
        val specialItems: List<SpecialDanmakuModel>
    )

    private data class PlayRequestIdentity(
        val aid: Long?,
        val bvid: String?,
        val cid: Long,
        val epId: Long?
    )

    private data class PreparedPlayback(
        val identity: PlayRequestIdentity,
        val playInfo: PlayInfoModel,
        val selectionSnapshot: VideoPlayerStreamResolver.SelectionSnapshot,
        val mediaSource: MediaSource,
        val seekToStart: Long,
        val playWhenReady: Boolean,
        val resumeHintPositionMs: Long?,
        val replaceInPlace: Boolean,
        val requestDurationMs: Long
    )

    private data class PreloadedPlayback(
        val source: PlaybackPreloadTarget.Source,
        val preparedPlayback: PreparedPlayback
    )

    private data class PlayInfoFetchResult(
        val requestedQualityId: Int,
        val response: VideoPlayerPlayInfoGateway.PlayInfoResult
    )

    private val _resumeHint = MutableStateFlow<ResumeProgressHint?>(null)
    val resumeHint: StateFlow<ResumeProgressHint?> = _resumeHint

    fun cancelResumeProgress() {
        _resumeHint.value = null
    }

    fun clearResumeHint() {
        _resumeHint.value = null
    }

    private val gson = Gson()
    private val appContext = context.applicationContext
    private val playerOkHttpClient = okHttpClient.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val dataSourceFactory = OkHttpDataSource.Factory(playerOkHttpClient)
        .setUserAgent(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"
        )
        .setDefaultRequestProperties(
            mapOf(
                "Origin" to "https://www.bilibili.com",
                "Referer" to "https://www.bilibili.com"
            )
        )

    var useDashPlayback: Boolean = true

    // Keeps stream selection and fallback policy out of the ViewModel's lifecycle code.
    private val streamResolver = VideoPlayerStreamResolver(
        dataSourceFactory = dataSourceFactory,
        urlNormalizer = ::normalizeUrl
    )
    private val dashMediaSourceFactory = VideoPlayerDashMediaSourceFactory(
        dataSourceFactory = dataSourceFactory,
        manifestBuilder = VideoPlayerDashManifestBuilder
    )
    private var currentDashSession: VideoPlaybackSession? = null
    private val qualityPolicy = VideoPlayerQualityPolicy()
    // Keeps episode-list construction and PGC header mapping out of playback request flow.
    private val episodeCatalogBuilder = VideoPlayerEpisodeCatalogBuilder(apiService)
    // Encapsulates PGC/UGC play-info retries and WBI-dependent requests away from UI state changes.
    private val playInfoGateway = VideoPlayerPlayInfoGateway(
        apiService = apiService,
        noCookieApiService = noCookieApiService,
        okHttpClient = okHttpClient,
        cookieManager = cookieManager,
        sessionGateway = sessionGateway,
        securityGateway = securityGateway,
        logTag = TAG
    )

    private val subtitleCache = object : LinkedHashMap<String, SubtitleData>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SubtitleData>): Boolean {
            return size > 8
        }
    }

    private val _videoInfo = MutableStateFlow<VideoDetailModel?>(null)
    val videoInfo: StateFlow<VideoDetailModel?> = _videoInfo

    private val _relatedVideos = MutableStateFlow<List<VideoModel>>(emptyList())
    val relatedVideos: StateFlow<List<VideoModel>> = _relatedVideos

    private val _episodes = MutableStateFlow<List<PlayableEpisode>>(emptyList())
    val episodes: StateFlow<List<PlayableEpisode>> = _episodes

    private val _selectedEpisodeIndex = MutableStateFlow(0)
    val selectedEpisodeIndex: StateFlow<Int> = _selectedEpisodeIndex

    private val _playbackRequest = MutableStateFlow<PlaybackRequest?>(null)
    val playbackRequest: StateFlow<PlaybackRequest?> = _playbackRequest

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _qualities = MutableStateFlow<List<VideoQuality>>(emptyList())
    val qualities: StateFlow<List<VideoQuality>> = _qualities

    private val _selectedQuality = MutableStateFlow<VideoQuality?>(null)
    val selectedQuality: StateFlow<VideoQuality?> = _selectedQuality

    private val _audioQualities = MutableStateFlow<List<AudioQuality>>(emptyList())
    val audioQualities: StateFlow<List<AudioQuality>> = _audioQualities

    private val _selectedAudioQuality = MutableStateFlow<AudioQuality?>(null)
    val selectedAudioQuality: StateFlow<AudioQuality?> = _selectedAudioQuality

    private val _videoCodecs = MutableStateFlow<List<VideoCodecEnum>>(emptyList())
    val videoCodecs: StateFlow<List<VideoCodecEnum>> = _videoCodecs

    private val _selectedVideoCodec = MutableStateFlow<VideoCodecEnum?>(null)
    val selectedVideoCodec: StateFlow<VideoCodecEnum?> = _selectedVideoCodec

    private val _subtitles = MutableStateFlow<List<SubtitleInfoModel>>(emptyList())
    val subtitles: StateFlow<List<SubtitleInfoModel>> = _subtitles

    private val _selectedSubtitleIndex = MutableStateFlow(-1)
    val selectedSubtitleIndex: StateFlow<Int> = _selectedSubtitleIndex

    private val _currentSubtitleText = MutableStateFlow<String?>(null)
    val currentSubtitleText: StateFlow<String?> = _currentSubtitleText

    private val _danmaku = MutableStateFlow<List<DmModel>>(emptyList())
    val danmaku: StateFlow<List<DmModel>> = _danmaku

    private val _danmakuUpdates = MutableSharedFlow<DanmakuUpdate>(extraBufferCapacity = 1)
    val danmakuUpdates: SharedFlow<DanmakuUpdate> = _danmakuUpdates

    private val _specialDanmaku = MutableStateFlow<List<SpecialDanmakuModel>>(emptyList())
    val specialDanmaku: StateFlow<List<SpecialDanmakuModel>> = _specialDanmaku

    private val _interactionModel = MutableStateFlow<InteractionModel?>(null)
    val interactionModel: StateFlow<InteractionModel?> = _interactionModel

    private val _videoSnapshot = MutableStateFlow<VideoSnapshotData?>(null)
    val videoSnapshot: StateFlow<VideoSnapshotData?> = _videoSnapshot

    private val _currentCidLive = MutableStateFlow(0L)
    val currentCidLive: StateFlow<Long> = _currentCidLive

    private val _riskControlVVoucher = MutableStateFlow<String?>(null)
    val riskControlVVoucher: StateFlow<String?> = _riskControlVVoucher

    private val _riskControlTryLookBypass = MutableStateFlow(false)
    val riskControlTryLookBypass: StateFlow<Boolean> = _riskControlTryLookBypass

    fun consumeRiskControlVVoucher(): String? {
        val value = _riskControlVVoucher.value
        _riskControlVVoucher.value = null
        return value
    }

    fun onGaiaVgateResult(gaiaVtoken: String) {
        val expiresAt = System.currentTimeMillis() + 12 * 60 * 60 * 1000L
        cookieManager.saveCookies(
            listOf(
                "x-bili-gaia-vtoken=$gaiaVtoken; domain=bilibili.com; path=/; secure; expires=$expiresAt"
            )
        )
        playInfoRefreshRetryCount = 0
        loadPlayUrlWithCurrentContext(reason = "gaia_vgate_verified")
    }

    private var currentAid: Long? = null
    private var currentBvid: String? = null
    private var currentCid: Long = 0L
    private var currentSeasonId: Long? = null
    private var currentEpId: Long? = null
    private var currentPlayInfo: PlayInfoModel? = null
    private var currentSubtitleData: SubtitleData? = null
    private var currentSubtitleCueIndex: Int = 0
    private var currentGraphVersion: Long = 0L
    private var loadedDanmakuCid: Long = 0L
    private var currentSettings: PlayerSettings = PlayerSettingsStore.load(appContext)
    private var shouldAutoSelectSubtitle = currentSettings.showSubtitleByDefault
    private var sessionStartTimestampMs: Long = 0L
    private var lastReportedHeartbeatPositionSec: Long = -1L

    private var requestedQualityId: Int? = null
    private var requestedAudioId: Int? = null
    private var requestedCodec: VideoCodecEnum? = null
    private var selectedQualityId: Int? = null
    private var selectedAudioId: Int? = null
    private var selectedCodec: VideoCodecEnum? = null
    private var pendingSeekPositionMs: Long = 0L
    private var pendingPlayWhenReady: Boolean = true
    private var didApplyLastPlayPosition = false
    private var launchStartEpisodeIndex: Int = -1
    private var danmakuLoadJob: Job? = null
    private var danmakuLoadGeneration: Long = 0L
    private var videoLoadGeneration: Long = 0L

    private var currentStreamFallbackPlan: VideoPlayerStreamResolver.StreamFallbackPlan? = null
    private var fallbackRouteIndex: Int = 0
    private var fallbackCdnIndex: Int = 0
    private var playInfoRefreshRetryCount: Int = 0
    private var fallbackAttemptCount: Int = 0
    private val attemptedFallbackSignatures = linkedSetOf<String>()
    private var lastPlaybackPositionMs: Long = 0L
    private var preloadedPlayback: PreloadedPlayback? = null
    private var preloadingIdentity: PlayRequestIdentity? = null
    private var preloadJob: Job? = null
    private var pendingPlayerExtrasCid: Long = 0L
    private var pendingDanmakuRequest: PendingDanmakuRequest? = null
    private var loadedPlayerExtrasCid: Long = 0L
    private val hardwareSupportedVideoCodecs: Set<VideoCodecEnum>
        get() = VideoCodecSupport.getHardwareSupportedCodecs()

    private data class PendingDanmakuRequest(
        val cid: Long,
        val aid: Long,
        val durationMs: Long
    )


    data class SavedPlayerSnapshot(
        val aid: Long,
        val bvid: String,
        val cid: Long,
        val epId: Long,
        val seasonId: Long,
        val episodeIndex: Int,
        val seekPositionMs: Long,
        val qualityId: Int,
        val audioQualityId: Int,
        val subtitleIndex: Int
    )

    fun savePlayerSnapshot() {
        val aid = currentAid ?: 0L
        val bvid = currentBvid.orEmpty()
        val cid = currentCid
        if (cid <= 0L && aid <= 0L && bvid.isBlank()) return
        savedStateHandle[SAVED_AID] = aid
        savedStateHandle[SAVED_BVID] = bvid
        savedStateHandle[SAVED_CID] = cid
        savedStateHandle[SAVED_EP_ID] = currentEpId ?: 0L
        savedStateHandle[SAVED_SEASON_ID] = currentSeasonId ?: 0L
        savedStateHandle[SAVED_EPISODE_INDEX] = _selectedEpisodeIndex.value ?: 0
        savedStateHandle[SAVED_SEEK_POSITION_MS] = pendingSeekPositionMs.coerceAtLeast(0L)
        savedStateHandle[SAVED_QUALITY_ID] = requestedQualityId ?: selectedQualityId ?: 0
        savedStateHandle[SAVED_AUDIO_QUALITY_ID] = requestedAudioId ?: selectedAudioId ?: 0
        savedStateHandle[SAVED_SUBTITLE_INDEX] = _selectedSubtitleIndex.value ?: -1
    }

    fun consumeSavedSnapshot(): SavedPlayerSnapshot? {
        val aid = savedStateHandle.remove<Long>(SAVED_AID) ?: return null
        val bvid = savedStateHandle.remove<String>(SAVED_BVID).orEmpty()
        val cid = savedStateHandle.remove<Long>(SAVED_CID) ?: 0L
        if (cid <= 0L && aid <= 0L && bvid.isBlank()) return null
        val epId = savedStateHandle.remove<Long>(SAVED_EP_ID) ?: 0L
        val seasonId = savedStateHandle.remove<Long>(SAVED_SEASON_ID) ?: 0L
        val episodeIndex = savedStateHandle.remove<Int>(SAVED_EPISODE_INDEX) ?: 0
        val seekPositionMs = savedStateHandle.remove<Long>(SAVED_SEEK_POSITION_MS) ?: 0L
        val qualityId = savedStateHandle.remove<Int>(SAVED_QUALITY_ID) ?: 0
        val audioQualityId = savedStateHandle.remove<Int>(SAVED_AUDIO_QUALITY_ID) ?: 0
        val subtitleIndex = savedStateHandle.remove<Int>(SAVED_SUBTITLE_INDEX) ?: -1
        return SavedPlayerSnapshot(
            aid = aid,
            bvid = bvid,
            cid = cid,
            epId = epId,
            seasonId = seasonId,
            episodeIndex = episodeIndex,
            seekPositionMs = seekPositionMs,
            qualityId = qualityId,
            audioQualityId = audioQualityId,
            subtitleIndex = subtitleIndex
        )
    }

    fun loadVideoInfo(
        aid: Long? = null,
        bvid: String? = null,
        cid: Long = 0L,
        seasonId: Long = 0L,
        epId: Long = 0L,
        seekPositionMs: Long = 0L,
        startEpisodeIndex: Int = -1,
        preferredQualityId: Int = 0,
        preferredAudioQualityId: Int = 0
    ) {
        currentSettings = PlayerSettingsStore.load(appContext)
        currentAid = aid?.takeIf { it > 0L }
        currentBvid = bvid?.takeIf { it.isNotBlank() }
        currentCid = cid
        currentSeasonId = seasonId.takeIf { it > 0L }
        currentEpId = epId.takeIf { it > 0L }
        currentPlayInfo = null
        currentSubtitleData = null
        currentGraphVersion = 0L
        loadedDanmakuCid = 0L
        loadedPlayerExtrasCid = 0L
        pendingPlayerExtrasCid = 0L
        pendingDanmakuRequest = null
        requestedQualityId = preferredQualityId.takeIf { it > 0 } ?: currentSettings.defaultVideoQualityId
        requestedAudioId = preferredAudioQualityId.takeIf { it > 0 } ?: currentSettings.defaultAudioQualityId
        requestedCodec = currentSettings.defaultVideoCodec
        selectedQualityId = null
        selectedAudioId = null
        selectedCodec = null
        pendingSeekPositionMs = seekPositionMs.coerceAtLeast(0L)
        pendingPlayWhenReady = true
        launchStartEpisodeIndex = startEpisodeIndex
        didApplyLastPlayPosition = pendingSeekPositionMs > 0L
        shouldAutoSelectSubtitle = currentSettings.showSubtitleByDefault
        sessionStartTimestampMs = 0L
        lastReportedHeartbeatPositionSec = -1L
        resetFallbackState()
        clearPreloadedPlayback(cancelJob = true)
        val loadGeneration = ++videoLoadGeneration
        _selectedSubtitleIndex.value = -1
        _currentSubtitleText.value = null
        currentSubtitleCueIndex = 0
        clearDanmaku()
        _interactionModel.value = null
        _videoSnapshot.value = null
        _error.value = null
        _qualities.value = emptyList()
        _selectedQuality.value = null
        _audioQualities.value = emptyList()
        _selectedAudioQuality.value = null
        _videoCodecs.value = emptyList()
        _selectedVideoCodec.value = null
        viewModelScope.launch {
            runCatching { playInfoGateway.warmupWbiKeys() }
        }

        viewModelScope.launch {
            _isLoading.value = true
            try {
                if (isPgcPlayback()) {
                    loadPgcVideoInfo(loadGeneration)
                    return@launch
                }
                loadUgcVideoInfo(preferLastPlayTime = true, loadGeneration = loadGeneration)
            } catch (e: Exception) {
                AppLog.e(TAG, "loadVideoInfo exception: ${e.message}", e)
                _error.value = e.message ?: "播放器初始化失败"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun playPrevious() {
        val episodes = _episodes.value.orEmpty()
        val targetIndex = (_selectedEpisodeIndex.value ?: 0) - 1
        if (targetIndex in episodes.indices) {
            playEpisode(targetIndex)
        }
    }

    fun hasPreviousEpisode(): Boolean {
        val previousIndex = (_selectedEpisodeIndex.value ?: 0) - 1
        return previousIndex in _episodes.value.orEmpty().indices
    }

    fun playNext() {
        val episodes = _episodes.value.orEmpty()
        val targetIndex = (_selectedEpisodeIndex.value ?: 0) + 1
        if (targetIndex in episodes.indices) {
            playEpisode(targetIndex)
        }
    }

    fun hasNextEpisode(): Boolean {
        val episodes = _episodes.value.orEmpty()
        val nextIndex = (_selectedEpisodeIndex.value ?: 0) + 1
        return nextIndex in episodes.indices
    }

    fun getNextEpisode(): PlayableEpisode? {
        val nextIndex = (_selectedEpisodeIndex.value ?: 0) + 1
        return _episodes.value.orEmpty().getOrNull(nextIndex)
    }

    fun playEpisode(index: Int) {
        val episode = _episodes.value.orEmpty().getOrNull(index) ?: return
        reportPlaybackHeartbeat()
        savePlayerSnapshot()
        val targetBvid = episode.bvid.takeIf { it.isNotBlank() }
        val targetSeasonId = episode.seasonId.takeIf { it > 0L }
        val targetEpId = episode.epId.takeIf { it > 0L }
        if (
            isPgcPlayback() &&
            targetSeasonId != null &&
            currentSeasonId != null &&
            targetSeasonId != currentSeasonId
        ) {
            loadVideoInfo(
                aid = episode.aid,
                bvid = targetBvid,
                cid = episode.cid,
                seasonId = targetSeasonId,
                epId = targetEpId ?: 0L
            )
            return
        }
        if (
            !isPgcPlayback() &&
            targetBvid != null &&
            targetBvid != currentBvid
        ) {
            loadVideoInfo(episode.aid, targetBvid, episode.cid)
            return
        }
        _selectedEpisodeIndex.value = index
        pendingSeekPositionMs = 0L
        pendingPlayWhenReady = true
        didApplyLastPlayPosition = false
        currentCid = episode.cid
        _currentCidLive.value = currentCid
        currentAid = episode.aid.takeIf { it > 0 } ?: currentAid
        currentBvid = episode.bvid.takeIf { it.isNotBlank() } ?: targetBvid ?: currentBvid.orEmpty()
        currentSeasonId = targetSeasonId ?: currentSeasonId
        currentEpId = targetEpId ?: currentEpId
        currentSubtitleData = null
        currentSubtitleCueIndex = 0
        _selectedSubtitleIndex.value = -1
        _currentSubtitleText.value = null
        _interactionModel.value = null
        _videoSnapshot.value = null
        _error.value = null
        clearPreloadedPlayback(cancelJob = false)
        loadPlayUrl(preferLastPlayTime = false)
    }

    fun playRelatedVideo(video: VideoModel) {
        val targetAid = video.aid.takeIf { it > 0L } ?: currentAid
        val targetBvid = video.bvid.takeIf { it.isNotBlank() } ?: currentBvid
        val targetSeasonId = video.playbackSeasonId.takeIf { it > 0L }
        val targetEpId = video.playbackEpId.takeIf { it > 0L }
        if (targetAid == null && targetBvid.isNullOrBlank() && targetEpId == null && targetSeasonId == null) {
            _error.value = "相关推荐缺少视频标识"
            return
        }
        reportPlaybackHeartbeat()
        clearPreloadedPlayback(cancelJob = false)
        loadVideoInfo(
            aid = targetAid,
            bvid = targetBvid,
            cid = video.cid,
            seasonId = targetSeasonId ?: 0L,
            epId = targetEpId ?: 0L
        )
    }

    fun playInteractionChoice(cid: Long, edgeId: Long) {
        if (cid <= 0L) {
            return
        }
        reportPlaybackHeartbeat()
        currentCid = cid
        _currentCidLive.value = cid
        pendingSeekPositionMs = 0L
        pendingPlayWhenReady = true
        didApplyLastPlayPosition = false
        currentSeasonId = null
        currentEpId = null
        currentSubtitleData = null
        currentSubtitleCueIndex = 0
        _selectedSubtitleIndex.value = -1
        _currentSubtitleText.value = null
        clearPreloadedPlayback(cancelJob = false)
        loadPlayUrl(preferLastPlayTime = false)
        loadInteractionInfo(edgeId)
        loadVideoSnapshot()
    }

    fun selectVideoQuality(
        quality: VideoQuality,
        currentPositionMs: Long,
        playWhenReady: Boolean
    ) {
        requestedQualityId = quality.id
        _selectedQuality.value = quality
        savePlayerSnapshot()
        capturePlaybackSnapshot(currentPositionMs, playWhenReady)
        loadPlayUrl(preferLastPlayTime = false, replaceInPlace = true)
    }

    fun selectAudioQuality(
        quality: AudioQuality,
        currentPositionMs: Long,
        playWhenReady: Boolean
    ) {
        requestedAudioId = quality.id
        _selectedAudioQuality.value = quality
        savePlayerSnapshot()
        capturePlaybackSnapshot(currentPositionMs, playWhenReady)
        rebuildPlayback()
    }

    fun selectVideoCodec(
        codec: VideoCodecEnum,
        currentPositionMs: Long,
        playWhenReady: Boolean
    ) {
        requestedCodec = codec
        _selectedVideoCodec.value = codec
        capturePlaybackSnapshot(currentPositionMs, playWhenReady)
        rebuildPlayback()
    }

    fun selectSubtitle(index: Int) {
        _selectedSubtitleIndex.value = index
        savedStateHandle[SAVED_SUBTITLE_INDEX] = index
        if (index < 0) {
            currentSubtitleData = null
            currentSubtitleCueIndex = 0
            _currentSubtitleText.value = null
            return
        }
        val subtitle = _subtitles.value.orEmpty().getOrNull(index) ?: return
        viewModelScope.launch {
            currentSubtitleData = loadSubtitleData(subtitle)
            currentSubtitleCueIndex = 0
            updateSubtitleText(_currentPosition.value ?: 0L)
        }
    }

    fun updatePlaybackPosition(
        positionMs: Long,
        durationMs: Long,
        publishProgressState: Boolean = true
    ) {
        val sanitizedPositionMs = positionMs.coerceAtLeast(0L)
        val sanitizedDurationMs = durationMs.takeIf { it > 0L } ?: 0L
        pendingSeekPositionMs = sanitizedPositionMs
        if (publishProgressState) {
            if (_currentPosition.value != sanitizedPositionMs) {
                _currentPosition.value = sanitizedPositionMs
            }
            if (_duration.value != sanitizedDurationMs) {
                _duration.value = sanitizedDurationMs
            }
        }
        updateSubtitleText(sanitizedPositionMs)
    }

    fun setLoading(loading: Boolean) {
        _isLoading.value = loading
    }

    fun setErrorMessage(message: String?) {
        _error.value = message
    }

    fun preloadPlayback(target: PlaybackPreloadTarget?) {
        val identity = target?.toPlayRequestIdentity()
        val currentIdentity = currentPlayRequestIdentity()
        if (identity == null || identity == currentIdentity) {
            clearPreloadedPlayback(cancelJob = true)
            return
        }
        val cachedPreload = preloadedPlayback
        if (cachedPreload?.preparedPlayback?.identity == identity && cachedPreload.source == target.source) {
            return
        }
        if (preloadingIdentity == identity) {
            return
        }

        preloadJob?.cancel()
        preloadedPlayback = null
        preloadingIdentity = identity
        preloadJob = viewModelScope.launch {
            val preparedPlayback = runCatching {
                requestPreparedPlayback(
                    identity = identity,
                    preferLastPlayTime = false,
                    replaceInPlace = false,
                    playbackPositionMs = 0L,
                    playWhenReady = true,
                    suppressUiSignals = true
                )
            }.getOrNull()
            ensureActive()
            if (preloadingIdentity != identity) {
                return@launch
            }
            preloadingIdentity = null
            preloadJob = null
            if (preparedPlayback == null) {
                AppLog.d(TAG, "preload skipped: source=${target.source}, cid=${identity.cid}")
                return@launch
            }
            preloadedPlayback = PreloadedPlayback(
                source = target.source,
                preparedPlayback = preparedPlayback
            )
            AppLog.d(
                TAG,
                "preload ready: source=${target.source}, cid=${identity.cid}, quality=${preparedPlayback.selectionSnapshot.selectedQualityId}, cost=${preparedPlayback.requestDurationMs}ms"
            )
        }
    }

    fun reportPlaybackHeartbeat(playType: Int = 2) {
        val aid = currentAid
        val bvid = currentBvid
        val cid = currentCid
        val positionSec = ((_currentPosition.value ?: pendingSeekPositionMs).coerceAtLeast(0L)) / 1000L
        val csrf = sessionGateway.getCsrfToken()
        if ((aid == null || aid <= 0L) && bvid.isNullOrBlank()) {
            return
        }
        if (cid <= 0L || positionSec <= 0L || csrf.isBlank()) {
            return
        }
        if (positionSec == lastReportedHeartbeatPositionSec) {
            return
        }
        lastReportedHeartbeatPositionSec = positionSec
        val startTimestamp = sessionStartTimestampMs.takeIf { it > 0L } ?: System.currentTimeMillis()

        viewModelScope.launch {
            runCatching {
                val params = linkedMapOf(
                    "played_time" to positionSec.toString(),
                    "realtime" to positionSec.toString(),
                    "start_ts" to startTimestamp.toString(),
                    "type" to "3",
                    "refer_url" to "https://www.bilibili.com/",
                    "play_type" to playType.toString(),
                    "csrf" to csrf
                )
                aid?.takeIf { it > 0L }?.let { params["aid"] = it.toString() }
                bvid?.takeIf { it.isNotBlank() }?.let { params["bvid"] = it }
                params["cid"] = cid.toString()
                sessionGateway.getUserInfo()
                    ?.mid
                    ?.takeIf { it > 0L }
                    ?.let { params["mid"] = it.toString() }
                sessionGateway.syncAuthState(
                    apiService.playVideoHeartbeat(params),
                    source = "player.playVideoHeartbeat"
                )
            }.onFailure { throwable ->
                AppLog.e(TAG, "reportPlaybackHeartbeat failed: ${throwable.message}", throwable)
            }
        }
    }

    private fun rebuildPlayback() {
        val playInfo = currentPlayInfo ?: return
        val selectionSnapshot = resolveSelectionSnapshot(playInfo) ?: run {
            _error.value = "当前清晰度/音轨组合不可播放"
            return
        }

        var dashMediaSource: MediaSource? = null
        if (useDashPlayback) {
            val dashRoutePlan = streamResolver.resolveDashRoutePlan(
                playInfo = playInfo,
                lockedQualityId = selectionSnapshot.selectedQualityId ?: return,
                selectedAudioId = selectionSnapshot.selectedAudioId,
                preferredCodec = selectionSnapshot.selectedCodec,
                hardwareSupportedCodecs = hardwareSupportedVideoCodecs
            )
            if (dashRoutePlan != null && dashRoutePlan.routes.isNotEmpty()) {
                try {
                    dashMediaSource = dashMediaSourceFactory.createMediaSource(dashRoutePlan.routes.first())
                    currentDashSession = VideoPlaybackSession(
                        identity = currentDashSession?.identity ?: SessionIdentity(
                            aid = currentAid,
                            bvid = currentBvid,
                            cid = currentCid,
                            epId = currentEpId
                        ),
                        requestedQualityId = selectionSnapshot.selectedQualityId,
                        requestedAudioId = selectionSnapshot.selectedAudioId,
                        requestedCodec = selectionSnapshot.selectedCodec,
                        actualQualityId = selectionSnapshot.selectedQualityId,
                        actualAudioId = dashRoutePlan.selectedAudioId,
                        actualCodec = dashRoutePlan.routes.first().codec,
                        playInfo = playInfo,
                        routePlan = dashRoutePlan,
                        currentRoute = dashRoutePlan.routes.first(),
                        expiresAtMs = 0L
                    )
                } catch (_: Exception) {
                    dashMediaSource = null
                }
            }
        }

        val mediaSource = dashMediaSource ?: streamResolver.buildMediaSource(
            playInfo = playInfo,
            selectedQualityId = selectionSnapshot.selectedQualityId,
            selectedAudioId = selectionSnapshot.selectedAudioId,
            selectedCodec = selectionSnapshot.selectedCodec
        )?.mediaSource ?: run {
            _error.value = "当前清晰度/音轨组合不可播放"
            return
        }
        applySelectionSnapshot(selectionSnapshot)
        _playbackRequest.value = PlaybackRequest(
            mediaSource = mediaSource,
            seekPositionMs = pendingSeekPositionMs,
            playWhenReady = pendingPlayWhenReady,
            replaceInPlace = true
        )
    }

    private fun loadPlayUrl(
        preferLastPlayTime: Boolean,
        replaceInPlace: Boolean = false,
        loadGeneration: Long = videoLoadGeneration
    ) {
        val identity = currentPlayRequestIdentity()
        if (identity == null) {
            _error.value = "CID 无效"
            return
        }
        if (!isPgcPlayback() && identity.bvid.isNullOrBlank()) {
            _error.value = "BVID 无效"
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            try {
                val preparedPlayback = consumePreloadedPlayback(
                    identity = identity,
                    preferLastPlayTime = preferLastPlayTime,
                    replaceInPlace = replaceInPlace
                ) ?: requestPreparedPlayback(
                    identity = identity,
                    preferLastPlayTime = preferLastPlayTime,
                    replaceInPlace = replaceInPlace
                )
                if (!isActiveVideoLoad(loadGeneration)) {
                    return@launch
                }
                if (preparedPlayback == null) {
                    _error.value = "播放地址请求失败"
                    return@launch
                }
                applyPreparedPlayback(preparedPlayback)
            } catch (e: Exception) {
                AppLog.e(TAG, "loadPlayUrl exception: ${e.message}", e)
                _error.value = e.message ?: "播放地址加载失败"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun loadPgcVideoInfo(loadGeneration: Long) {
        val seasonId = currentSeasonId
        val epId = currentEpId
        securityGateway.prewarmWebSession()
        val detailResponse = apiService.getVideoEpisodes(seasonId, epId)
        val detail = detailResponse.result
        if (!detailResponse.isSuccess || detail == null) {
            if (shouldFallbackToUgcPlayback(detailResponse)) {
                AppLog.d(
                    TAG,
                    "loadPgcVideoInfo fallback to ugc: seasonId=${seasonId ?: 0L}, epId=${epId ?: 0L}, aid=${currentAid ?: 0L}, bvid=${currentBvid.orEmpty()}, cid=$currentCid"
                )
                currentSeasonId = null
                currentEpId = null
                loadUgcVideoInfo(preferLastPlayTime = true, loadGeneration = loadGeneration)
                return
            }
            AppLog.e(
                TAG,
                "loadPgcVideoInfo failure: code=${detailResponse.code}, message=${detailResponse.errorMessage}, seasonId=${seasonId ?: 0L}, epId=${epId ?: 0L}"
            )
            _error.value = detailResponse.message.ifBlank { "番剧详情加载失败" }
            return
        }

        val resolvedSeasonId = detail.seasonId.takeIf { it > 0L } ?: seasonId
        val sectionResult = resolvedSeasonId
            ?.takeIf { it > 0L }
            ?.let { apiService.getVideoEpisodeSections(it).result }
        val mergedDetail = detail.copy(
            episodes = sectionResult?.mainSection?.episodes.orEmpty(),
            section = sectionResult?.section.orEmpty()
        )
        val episodeItems = episodeCatalogBuilder.buildPgcEpisodes(mergedDetail)
        val selectedIndex = episodeCatalogBuilder.resolvePgcEpisodeIndex(
            episodes = episodeItems,
            targetEpId = currentEpId,
            targetCid = currentCid,
            targetBvid = currentBvid,
            fallbackIndex = launchStartEpisodeIndex
        )
        val selectedEpisode = episodeItems.getOrNull(selectedIndex)

        currentSeasonId = resolvedSeasonId
        currentEpId = selectedEpisode?.epId ?: epId
        currentCid = selectedEpisode?.cid ?: currentCid
        currentAid = selectedEpisode?.aid?.takeIf { it > 0L } ?: currentAid
        currentBvid = selectedEpisode?.bvid?.takeIf { it.isNotBlank() } ?: currentBvid

        _videoInfo.value = episodeCatalogBuilder.buildPgcVideoDetail(
            detail = mergedDetail,
            selectedEpisode = selectedEpisode,
            fallbackAid = currentAid ?: 0L,
            fallbackBvid = currentBvid.orEmpty(),
            fallbackCid = currentCid
        )
        _episodes.value = episodeItems
        _selectedEpisodeIndex.value = selectedIndex
        _currentCidLive.value = currentCid
        _relatedVideos.value = emptyList()
        _subtitles.value = emptyList()

        AppLog.d(
            TAG,
            "loadPgcVideoInfo success: seasonId=${currentSeasonId ?: 0L}, epId=${currentEpId ?: 0L}, aid=${currentAid ?: 0L}, bvid=${currentBvid.orEmpty()}, cid=$currentCid, episodes=${episodeItems.size}, selectedIndex=$selectedIndex"
        )

        if (currentCid <= 0L) {
            _error.value = "未找到可播放剧集"
            return
        }

        loadPlayUrl(preferLastPlayTime = true, loadGeneration = loadGeneration)
    }

    private suspend fun loadUgcVideoInfo(preferLastPlayTime: Boolean, loadGeneration: Long) = coroutineScope {
        val initialIdentity = currentPlayRequestIdentity()
        val preparedPlaybackDeferred = initialIdentity
            ?.takeIf { it.cid > 0L && !it.bvid.isNullOrBlank() }
            ?.let { identity ->
                async {
                    requestPreparedPlayback(
                        identity = identity,
                        preferLastPlayTime = preferLastPlayTime,
                        replaceInPlace = false
                    )
                }
            }
        val detailResponse = apiService.getVideoDetail(currentAid, currentBvid)
        if (!detailResponse.isSuccess || detailResponse.data == null) {
            AppLog.e(
                TAG,
                "loadUgcVideoInfo detail failure: code=${detailResponse.code}, message=${detailResponse.errorMessage}"
            )
            _error.value = detailResponse.message.ifBlank { "视频详情加载失败" }
            return@coroutineScope
        }

        val detail = detailResponse.data
        _videoInfo.value = detail
        currentAid = detail.view?.aid ?: currentAid
        currentBvid = detail.view?.bvid?.takeIf { it.isNotBlank() } ?: currentBvid

        val episodeItems = episodeCatalogBuilder.buildUgcEpisodes(detail)
        _episodes.value = episodeItems
        val selectedIndex = episodeItems.indexOfFirst { it.cid == currentCid }.takeIf { it >= 0 } ?: 0
        _selectedEpisodeIndex.value = selectedIndex
        val selectedEpisode = episodeItems.getOrNull(selectedIndex)
        currentCid = selectedEpisode?.cid
            ?: detail.view?.cid
            ?: currentCid
        currentAid = selectedEpisode?.aid?.takeIf { it > 0L } ?: currentAid
        currentBvid = selectedEpisode?.bvid?.takeIf { it.isNotBlank() } ?: currentBvid
        _currentCidLive.value = currentCid

        if (currentCid <= 0L) {
            _error.value = "未找到可播放分P"
            return@coroutineScope
        }

        val related = detail.related.orEmpty()
        _subtitles.value = detail.view?.subtitle?.list
            ?.map { it.toSubtitleInfoModel() }
            .orEmpty()
        maybeAutoSelectSubtitle()
        val resolvedIdentity = currentPlayRequestIdentity()
        val preparedPlayback = if (
            preparedPlaybackDeferred != null &&
            canReusePreparedPlayback(initialIdentity, resolvedIdentity)
        ) {
            preparedPlaybackDeferred.await()
        } else {
            null
        }
        if (!isActiveVideoLoad(loadGeneration)) {
            return@coroutineScope
        }
        if (preparedPlayback != null) {
            applyPreparedPlayback(preparedPlayback)
        } else {
            loadPlayUrl(preferLastPlayTime = preferLastPlayTime, loadGeneration = loadGeneration)
        }

        if (related.isNotEmpty()) {
            _relatedVideos.value = related
        } else {
            viewModelScope.launch {
                val latestRelated = runCatching {
                    apiService.getRelated(currentAid, currentBvid).data.orEmpty()
                }.getOrDefault(emptyList())
                if (currentCid == detail.view?.cid || currentBvid == detail.view?.bvid) {
                    _relatedVideos.value = latestRelated
                }
            }
        }
    }

    private fun canReusePreparedPlayback(
        initialIdentity: PlayRequestIdentity?,
        resolvedIdentity: PlayRequestIdentity?
    ): Boolean {
        if (initialIdentity == null || resolvedIdentity == null) {
            return false
        }
        if (initialIdentity.cid != resolvedIdentity.cid) {
            return false
        }
        if (initialIdentity.epId != resolvedIdentity.epId) {
            return false
        }
        val initialBvid = initialIdentity.bvid.orEmpty()
        val resolvedBvid = resolvedIdentity.bvid.orEmpty()
        return initialBvid.isNotBlank() && initialBvid == resolvedBvid
    }

    private suspend fun requestPreparedPlayback(
        identity: PlayRequestIdentity,
        preferLastPlayTime: Boolean,
        replaceInPlace: Boolean,
        playbackPositionMs: Long = pendingSeekPositionMs,
        playWhenReady: Boolean = pendingPlayWhenReady,
        qualityCandidates: List<Int> = qualityPolicy.buildCandidates(
            requestedQualityId ?: selectedQualityId
        ),
        suppressUiSignals: Boolean = false
    ): PreparedPlayback? {
        val requestStartMs = System.currentTimeMillis()
        AppLog.d(TAG, "playurl:start cid=${identity.cid}")
        val preferredQualityId = qualityCandidates.firstOrNull()
            ?: requestedQualityId
            ?: selectedQualityId
            ?: 80

        val cachedPlayInfo = identity.bvid
            ?.takeIf { !replaceInPlace && it.isNotBlank() && identity.epId == null }
            ?.let { bvid -> VideoPlayerPlayInfoCache.get(bvid = bvid, cid = identity.cid) }
            ?.takeIf(::hasPlayableMedia)

        val (initialPlayInfo, effectiveRequestedQualityId) = if (cachedPlayInfo != null) {
            cachedPlayInfo to preferredQualityId
        } else {
            val playInfoFetch = requestPlayInfoWithQualityFallback(
                identity = identity,
                qualityCandidates = qualityCandidates,
                suppressUiSignals = suppressUiSignals
            )
            if (playInfoFetch == null) {
                AppLog.e(TAG, "loadPlayUrl requestPlayInfoWithQualityFallback returned null")
                return null
            }
            val response = playInfoFetch.response
            if (!response.isSuccess || response.data == null) {
                val vVoucher = response.vVoucher.trim()
                if (vVoucher.isNotBlank()) {
                    AppLog.w(TAG, "loadPlayUrl v_voucher detected, posting to UI: vVoucherLen=${vVoucher.length}")
                    if (!suppressUiSignals) {
                        appSettings.putStringAsync("gaia_vgate_v_voucher", vVoucher)
                        appSettings.putStringAsync("gaia_vgate_v_voucher_saved_at_ms", System.currentTimeMillis().toString())
                        _riskControlVVoucher.value = vVoucher
                        _error.value = "账号被风控，正在请求人机验证…"
                    }
                } else if (response.isTryLookBypass) {
                    if (!suppressUiSignals) {
                        _error.value = "账号被风控，已降级为试看模式"
                        _riskControlTryLookBypass.value = true
                    }
                }
                AppLog.e(
                    TAG,
                    "loadPlayUrl response failure: code=${response.code}, message=${response.message}"
                )
                return null
            }
            response.data to playInfoFetch.requestedQualityId
        }

        identity.bvid
            ?.takeIf { it.isNotBlank() && identity.epId == null }
            ?.let { bvid -> VideoPlayerPlayInfoCache.put(bvid = bvid, cid = identity.cid, playInfo = initialPlayInfo) }

        val initialQualities = streamResolver.buildQualityList(initialPlayInfo)
        val resolvedQualityId = resolvePlayableQualityId(
            requestedQualityId = effectiveRequestedQualityId,
            playInfo = initialPlayInfo,
            availableQualities = initialQualities,
            reason = "initial_request"
        )

        var selectionSnapshot = streamResolver.resolveSelections(
            playInfo = initialPlayInfo,
            preferredQualityId = resolvedQualityId,
            preferredAudioId = requestedAudioId ?: selectedAudioId,
            preferredCodec = requestedCodec ?: selectedCodec,
            hardwareSupportedCodecs = hardwareSupportedVideoCodecs
        )
        if (selectionSnapshot.selectedQualityId != resolvedQualityId) {
            selectionSnapshot = selectionSnapshot.copy(selectedQualityId = resolvedQualityId)
        }
        AppLog.d(TAG, "playurl:done cost=${System.currentTimeMillis() - requestStartMs}ms cid=${identity.cid}")

        AppLog.d(TAG, "useDashPlayback=$useDashPlayback cid=${identity.cid}")

        var dashMediaSource: MediaSource? = null
        if (useDashPlayback) {
            val dashRoutePlan = streamResolver.resolveDashRoutePlan(
                playInfo = initialPlayInfo,
                lockedQualityId = resolvedQualityId,
                selectedAudioId = selectionSnapshot.selectedAudioId,
                preferredCodec = selectionSnapshot.selectedCodec,
                hardwareSupportedCodecs = hardwareSupportedVideoCodecs
            )
            if (dashRoutePlan != null && dashRoutePlan.routes.isNotEmpty()) {
                val firstRoute = dashRoutePlan.routes.first()
                AppLog.d(TAG, "dashRoute:resolved cid=${identity.cid} codec=${firstRoute.codec} routes=${dashRoutePlan.routes.size}")
                try {
                    dashMediaSource = dashMediaSourceFactory.createMediaSource(firstRoute)
                    currentDashSession = VideoPlaybackSession(
                        identity = SessionIdentity(
                            aid = identity.aid,
                            bvid = identity.bvid,
                            cid = identity.cid,
                            epId = identity.epId
                        ),
                        requestedQualityId = resolvedQualityId,
                        requestedAudioId = selectionSnapshot.selectedAudioId,
                        requestedCodec = selectionSnapshot.selectedCodec,
                        actualQualityId = resolvedQualityId,
                        actualAudioId = dashRoutePlan.selectedAudioId,
                        actualCodec = firstRoute.codec,
                        playInfo = initialPlayInfo,
                        routePlan = dashRoutePlan,
                        currentRoute = firstRoute,
                        expiresAtMs = 0L
                    )
                    AppLog.d(TAG, "dashMediaSource:created cid=${identity.cid}")
                } catch (e: Exception) {
                    AppLog.e(TAG, "dashMediaSource:failed cid=${identity.cid} error=${e.message}", e)
                    dashMediaSource = null
                    currentDashSession = null
                }
            } else {
                AppLog.d(TAG, "dashRoute:unavailable cid=${identity.cid}, falling back to progressive")
            }
        }

        val mediaSource: MediaSource = dashMediaSource ?: run {
            AppLog.d(TAG, "progressiveMediaSource:build:start cid=${identity.cid}")
            val progressiveSelection = streamResolver.buildMediaSource(
                playInfo = initialPlayInfo,
                selectedQualityId = resolvedQualityId,
                selectedAudioId = selectionSnapshot.selectedAudioId,
                selectedCodec = selectionSnapshot.selectedCodec
            )
            progressiveSelection?.mediaSource ?: run {
                AppLog.e(TAG, "loadPlayUrl mediaSource missing: cid=${identity.cid}")
                return null
            }
        }

        val useServerResume = preferLastPlayTime &&
            initialPlayInfo.lastPlayCid == identity.cid &&
            initialPlayInfo.lastPlayTime > 5000L

        val rawResumePosition = when {
            useServerResume -> initialPlayInfo.lastPlayTime
            else -> playbackPositionMs
        }

        val shouldResume = rawResumePosition > 5000L

        val startPosition = rawResumePosition.takeIf { shouldResume } ?: 0L

        val resumeHintPositionMs = startPosition.takeIf { shouldResume && !replaceInPlace }
        val seekToStart = if (resumeHintPositionMs != null) 0L else startPosition
        AppLog.d(TAG, "playerPrepare:start cid=${identity.cid} totalCost=${System.currentTimeMillis() - requestStartMs}ms")
        return PreparedPlayback(
            identity = identity,
            playInfo = initialPlayInfo,
            selectionSnapshot = selectionSnapshot,
            mediaSource = mediaSource,
            seekToStart = seekToStart,
            playWhenReady = playWhenReady,
            resumeHintPositionMs = resumeHintPositionMs,
            replaceInPlace = replaceInPlace,
            requestDurationMs = System.currentTimeMillis() - requestStartMs
        )
    }

    private fun applyPreparedPlayback(
        preparedPlayback: PreparedPlayback,
        resetFallbackAttempts: Boolean = true,
        countCurrentAttemptAsFallback: Boolean = false
    ) {
        clearPreloadedPlayback(cancelJob = false)
        currentPlayInfo = preparedPlayback.playInfo
        applySelectionSnapshot(preparedPlayback.selectionSnapshot)
        if (preparedPlayback.resumeHintPositionMs != null) {
            didApplyLastPlayPosition = true
        }
        if (!preparedPlayback.replaceInPlace) {
            sessionStartTimestampMs = System.currentTimeMillis()
            lastReportedHeartbeatPositionSec = -1L
        }

        initializeFallbackPlan(
            playInfo = preparedPlayback.playInfo,
            lockedQualityId = requestedQualityId
                ?: preparedPlayback.selectionSnapshot.selectedQualityId
                ?: selectedQualityId
                ?: 80,
            selectedAudioId = requestedAudioId ?: preparedPlayback.selectionSnapshot.selectedAudioId,
            preferredCodec = requestedCodec ?: preparedPlayback.selectionSnapshot.selectedCodec,
            resetAttempts = resetFallbackAttempts
        )
        rememberCurrentFallbackAttempt(countAsFallback = countCurrentAttemptAsFallback)

        _playbackRequest.value = PlaybackRequest(
            mediaSource = preparedPlayback.mediaSource,
            seekPositionMs = preparedPlayback.seekToStart,
            playWhenReady = preparedPlayback.playWhenReady,
            replaceInPlace = preparedPlayback.replaceInPlace
        )
        preparedPlayback.resumeHintPositionMs?.let { targetPositionMs ->
            _resumeHint.value = ResumeProgressHint(targetPositionMs = targetPositionMs)
        }
        _error.value = null
        if (loadedPlayerExtrasCid != preparedPlayback.identity.cid) {
            pendingPlayerExtrasCid = preparedPlayback.identity.cid
        }
        if (loadedDanmakuCid != preparedPlayback.identity.cid) {
            val danmakuAid = currentAid
                ?: preparedPlayback.identity.aid
                ?: 0L
            pendingDanmakuRequest = PendingDanmakuRequest(
                cid = preparedPlayback.identity.cid,
                aid = danmakuAid,
                durationMs = preparedPlayback.playInfo.timeLength
            )
        }
    }

    fun onPlaybackFirstFrame() {
        val cid = currentCid.takeIf { it > 0L } ?: return
        if (pendingPlayerExtrasCid == cid && loadedPlayerExtrasCid != cid) {
            pendingPlayerExtrasCid = 0L
            loadedPlayerExtrasCid = cid
            loadPlayerExtras()
        }
        pendingDanmakuRequest
            ?.takeIf { it.cid == cid && loadedDanmakuCid != cid }
            ?.also { request ->
                pendingDanmakuRequest = null
                loadedDanmakuCid = request.cid
                loadDanmaku(
                    cid = request.cid,
                    aid = request.aid,
                    durationMs = request.durationMs
                )
            }
    }

    fun handlePlaybackError(error: androidx.media3.common.PlaybackException, currentPositionMs: Long) {
        lastPlaybackPositionMs = currentPositionMs.coerceAtLeast(0L)
        val plan = currentStreamFallbackPlan
        if (plan == null || plan.routes.isEmpty()) {
            _error.value = error.message ?: "加载失败"
            return
        }

        val errorType = classifyPlaybackError(error)
        AppLog.d(
            TAG,
            "handlePlaybackError: type=$errorType, code=${error.errorCode}, message=${error.message}, routeIndex=$fallbackRouteIndex, cdnIndex=$fallbackCdnIndex, attempts=$fallbackAttemptCount/$MAX_FALLBACK_ATTEMPTS"
        )

        val handled = when (errorType) {
            PlaybackErrorType.DECODER -> {
                trySwitchToCodec(VideoCodecEnum.AVC, lastPlaybackPositionMs, reason = "decoder_error_prefer_avc") ||
                    trySwitchCodec(lastPlaybackPositionMs, reason = "decoder_error") ||
                    tryNextCdnInCurrentCodec(lastPlaybackPositionMs, reason = "decoder_error_cdn_retry") ||
                    tryRefreshPlayInfo(reason = "decoder_error_refresh")
            }
            PlaybackErrorType.NETWORK -> {
                trySwitchToCodec(VideoCodecEnum.AVC, lastPlaybackPositionMs, reason = "network_error_prefer_avc") ||
                    tryNextCdnInCurrentCodec(lastPlaybackPositionMs, reason = "network_error") ||
                    tryRefreshPlayInfo(reason = "network_error_refresh") ||
                    trySwitchCodec(lastPlaybackPositionMs, reason = "network_error_codec_switch")
            }
            PlaybackErrorType.UNKNOWN -> {
                trySwitchToCodec(VideoCodecEnum.AVC, lastPlaybackPositionMs, reason = "unknown_error_prefer_avc") ||
                    tryNextCdnInCurrentCodec(lastPlaybackPositionMs, reason = "unknown_error") ||
                    trySwitchCodec(lastPlaybackPositionMs, reason = "unknown_error_codec_switch") ||
                    tryRefreshPlayInfo(reason = "unknown_error_refresh")
            }
        }

        if (!handled) {
            AppLog.e(TAG, "fallback exhausted: qualityLocked=${plan.qualityId}, attempts=$fallbackAttemptCount")
            _error.value = "当前清晰度下所有线路与编码器都不可用"
        }
    }

    fun handlePlaybackStall(positionMs: Long, stalledMs: Long): Boolean {
        lastPlaybackPositionMs = positionMs.coerceAtLeast(0L)
        val handled =
            trySwitchToCodec(VideoCodecEnum.AVC, lastPlaybackPositionMs, reason = "stall_${stalledMs}ms_prefer_avc") ||
                tryNextCdnInCurrentCodec(lastPlaybackPositionMs, reason = "stall_${stalledMs}ms") ||
                tryRefreshPlayInfo(reason = "stall_${stalledMs}ms_refresh") ||
                trySwitchCodec(lastPlaybackPositionMs, reason = "stall_${stalledMs}ms_codec_switch")
        if (handled) {
            AppLog.w(
                TAG,
                "handlePlaybackStall: position=$positionMs, stalledMs=$stalledMs, codec=$selectedCodec"
            )
        }
        return handled
    }

    private enum class PlaybackErrorType {
        DECODER,
        NETWORK,
        UNKNOWN
    }

    private fun classifyPlaybackError(error: androidx.media3.common.PlaybackException): PlaybackErrorType {
        val code = error.errorCode
        if (code in 4000..4999) {
            return PlaybackErrorType.DECODER
        }
        if (code in 2000..2999) {
            return PlaybackErrorType.NETWORK
        }
        val message = buildString {
            append(error.message.orEmpty())
            append(' ')
            append(error.cause?.javaClass?.name.orEmpty())
            append(' ')
            append(error.cause?.message.orEmpty())
        }.lowercase()
        return when {
            message.contains("decoder") ||
                message.contains("mediacodec") ||
                message.contains("codec init") ||
                message.contains("no suitable decoder") -> PlaybackErrorType.DECODER
            message.contains("timeout") ||
                message.contains("network") ||
                message.contains("http") ||
                message.contains("source error") ||
                message.contains("connection") -> PlaybackErrorType.NETWORK
            else -> PlaybackErrorType.UNKNOWN
        }
    }

    private fun tryNextCdnInCurrentCodec(positionMs: Long, reason: String): Boolean {
        val plan = currentStreamFallbackPlan ?: return false
        if (fallbackRouteIndex !in plan.routes.indices) {
            return false
        }
        return tryDispatchPlaybackAttempt(
            routeIndex = fallbackRouteIndex,
            startCdnIndex = fallbackCdnIndex + 1,
            seekPositionMs = positionMs,
            reason = reason
        )
    }

    private fun trySwitchCodec(positionMs: Long, reason: String): Boolean {
        val plan = currentStreamFallbackPlan ?: return false
        for (routeIndex in (fallbackRouteIndex + 1) until plan.routes.size) {
            if (tryDispatchPlaybackAttempt(routeIndex, 0, positionMs, reason)) {
                return true
            }
        }
        return false
    }

    private fun trySwitchToCodec(
        targetCodec: VideoCodecEnum,
        positionMs: Long,
        reason: String
    ): Boolean {
        val plan = currentStreamFallbackPlan ?: return false
        if (selectedCodec == targetCodec) {
            return false
        }
        val routeIndex = plan.routes.indexOfFirst { it.codec == targetCodec }
        if (routeIndex < 0) {
            return false
        }
        return tryDispatchPlaybackAttempt(routeIndex, 0, positionMs, reason)
    }

    private fun tryRefreshPlayInfo(reason: String): Boolean {
        if (playInfoRefreshRetryCount >= MAX_PLAYINFO_REFRESH_RETRY) {
            return false
        }
        playInfoRefreshRetryCount += 1
        AppLog.d(
            TAG,
            "fallback refresh playurl: reason=$reason, retry=$playInfoRefreshRetryCount/$MAX_PLAYINFO_REFRESH_RETRY"
        )
        return loadPlayUrlWithCurrentContext(reason = reason)
    }

    private fun loadPlayUrlWithCurrentContext(reason: String): Boolean {
        val identity = currentPlayRequestIdentity() ?: return false
        val lockedQualityId = requestedQualityId ?: selectedQualityId ?: 80
        viewModelScope.launch {
            val preparedPlayback = requestPreparedPlayback(
                identity = identity,
                preferLastPlayTime = false,
                replaceInPlace = true,
                playbackPositionMs = lastPlaybackPositionMs,
                playWhenReady = true,
                qualityCandidates = qualityPolicy.buildCandidates(lockedQualityId)
            )
            if (preparedPlayback != null) {
                applyPreparedPlayback(
                    preparedPlayback = preparedPlayback,
                    resetFallbackAttempts = false,
                    countCurrentAttemptAsFallback = true
                )
                AppLog.d(
                    TAG,
                    "refresh playurl success: reason=$reason, requestedQuality=$lockedQualityId, actualQuality=${preparedPlayback.selectionSnapshot.selectedQualityId}"
                )
                return@launch
            }
            if (!trySwitchCodec(lastPlaybackPositionMs, reason = "refresh_failed")) {
                _error.value = "当前清晰度下播放失败，请稍后重试"
            }
        }
        return true
    }

    private fun tryDispatchPlaybackAttempt(
        routeIndex: Int,
        startCdnIndex: Int,
        seekPositionMs: Long,
        reason: String
    ): Boolean {
        val plan = currentStreamFallbackPlan ?: return false
        if (routeIndex !in plan.routes.indices) {
            return false
        }
        if (fallbackAttemptCount >= MAX_FALLBACK_ATTEMPTS) {
            return false
        }
        val route = plan.routes[routeIndex]
        if (route.videoUrls.isEmpty()) {
            return false
        }
        val audioVariantCount = route.audioUrls.size.takeIf { it > 0 } ?: 1
        val totalVariants = maxOf(route.videoUrls.size, audioVariantCount)
        for (cdnIndex in startCdnIndex until totalVariants) {
            val videoUrl = route.videoUrls.getOrElse(cdnIndex) { route.videoUrls.first() }
            val audioUrl = route.audioUrls
                .takeIf { it.isNotEmpty() }
                ?.getOrElse(cdnIndex) { route.audioUrls.first() }
            val signature = buildFallbackSignature(plan.qualityId, route.codec, videoUrl, audioUrl)
            if (!attemptedFallbackSignatures.add(signature)) {
                continue
            }
            fallbackAttemptCount += 1
            fallbackRouteIndex = routeIndex
            fallbackCdnIndex = cdnIndex
            selectedCodec = route.codec
            _selectedVideoCodec.value = route.codec
            val selection = streamResolver.buildMediaSourceForRoute(
                route = route,
                videoUrl = videoUrl,
                audioUrl = audioUrl,
                availableCodecs = plan.routes.map { it.codec },
                selectedCodec = route.codec,
                durationMs = plan.durationMs,
                minBufferTimeMs = plan.minBufferTimeMs
            )
            _error.value = null
            _playbackRequest.value = PlaybackRequest(
                mediaSource = selection.mediaSource,
                seekPositionMs = seekPositionMs,
                playWhenReady = true,
                replaceInPlace = true
            )
            AppLog.d(
                TAG,
                "fallback dispatch: reason=$reason, quality=${plan.qualityId}, codec=${route.codec}, routeIndex=$routeIndex/${plan.routes.lastIndex}, cdnIndex=$cdnIndex/${totalVariants - 1}, attempts=$fallbackAttemptCount/$MAX_FALLBACK_ATTEMPTS"
            )
            return true
        }
        return false
    }

    private fun initializeFallbackPlan(
        playInfo: PlayInfoModel,
        lockedQualityId: Int,
        selectedAudioId: Int?,
        preferredCodec: VideoCodecEnum?,
        resetAttempts: Boolean
    ) {
        currentStreamFallbackPlan = streamResolver.buildStreamFallbackPlan(
            playInfo = playInfo,
            lockedQualityId = lockedQualityId,
            selectedAudioId = selectedAudioId,
            preferredCodec = preferredCodec,
            hardwareSupportedCodecs = hardwareSupportedVideoCodecs
        )
        fallbackRouteIndex = currentStreamFallbackPlan
            ?.routes
            ?.indexOfFirst { it.codec == preferredCodec }
            ?.takeIf { it >= 0 }
            ?: 0
        fallbackCdnIndex = 0
        if (resetAttempts) {
            fallbackAttemptCount = 0
            attemptedFallbackSignatures.clear()
        }
    }

    private fun resetFallbackState() {
        currentStreamFallbackPlan = null
        fallbackRouteIndex = 0
        fallbackCdnIndex = 0
        fallbackAttemptCount = 0
        attemptedFallbackSignatures.clear()
        playInfoRefreshRetryCount = 0
    }

    private fun rememberCurrentFallbackAttempt(countAsFallback: Boolean): Boolean {
        val plan = currentStreamFallbackPlan ?: return false
        val route = plan.routes.getOrNull(fallbackRouteIndex) ?: return false
        val videoUrl = route.videoUrls.getOrNull(fallbackCdnIndex)
            ?: route.videoUrls.firstOrNull()
            ?: return false
        val audioUrl = route.audioUrls
            .takeIf { it.isNotEmpty() }
            ?.getOrElse(fallbackCdnIndex) { route.audioUrls.first() }
        val signature = buildFallbackSignature(plan.qualityId, route.codec, videoUrl, audioUrl)
        val added = attemptedFallbackSignatures.add(signature)
        if (added && countAsFallback) {
            fallbackAttemptCount += 1
        }
        return added
    }

    private fun buildFallbackSignature(
        qualityId: Int,
        codec: VideoCodecEnum,
        videoUrl: String,
        audioUrl: String?
    ): String {
        return "$qualityId|${codec.name}|$videoUrl|${audioUrl.orEmpty()}"
    }

    private suspend fun requestPlayInfoWithQualityFallback(
        identity: PlayRequestIdentity,
        qualityCandidates: List<Int>,
        suppressUiSignals: Boolean
    ): PlayInfoFetchResult? {
        val attemptedQualities = linkedSetOf<Int>()
        val requestedQualityId = qualityCandidates.firstOrNull()
            ?: this.requestedQualityId
            ?: selectedQualityId
            ?: 80
        var lastResult: PlayInfoFetchResult? = null
        var allowWbi = true
        qualityCandidates.forEach { qualityId ->
            if (!attemptedQualities.add(qualityId)) {
                return@forEach
            }
            val response = playInfoGateway.requestPlayInfo(
                aid = identity.aid,
                bvid = identity.bvid,
                cid = identity.cid,
                epId = identity.epId,
                qualityId = qualityId,
                fnval = streamResolver.buildFnval(qualityId),
                fourk = streamResolver.buildFourk(qualityId),
                allowWbi = allowWbi
            ) ?: return@forEach
            allowWbi = false
            lastResult = PlayInfoFetchResult(
                requestedQualityId = qualityId,
                response = response
            )
            val playInfo = response.data
            if (response.isSuccess && hasPlayableMedia(playInfo)) {
                if (qualityId != requestedQualityId) {
                    AppLog.w(
                        TAG,
                        "playurl fallback request succeeded: requested=$requestedQualityId, actualRequest=$qualityId, cid=${identity.cid}"
                    )
                }
                if (response.isTryLookBypass) {
                    AppLog.w(
                        TAG,
                        "playurl try_look bypass activated: requested=$requestedQualityId, actualRequest=$qualityId, cid=${identity.cid}"
                    )
                    if (!suppressUiSignals) {
                        _riskControlTryLookBypass.value = true
                    }
                }
                return lastResult
            }
            if (response.code == -351 || response.code == -412 || response.code == -352) {
                AppLog.w(
                    TAG,
                    "playurl risk-control detected: code=${response.code}, tried=$qualityId, cid=${identity.cid}, stopFurtherFallback=true"
                )
                return lastResult
            }
            if (response.code == 0 && playInfo != null && !hasPlayableMedia(playInfo)) {
                if (shouldContinueQualityFallback(qualityId, response.message, playInfo)) {
                    AppLog.w(
                        TAG,
                        "playurl empty media payload but quality fallback should continue: tried=$qualityId, cid=${identity.cid}, declared=${playInfo.acceptQuality.orEmpty()}, responseQuality=${playInfo.quality}, message=${response.message}"
                    )
                    return@forEach
                }
                AppLog.w(
                    TAG,
                    "playurl empty media payload (soft risk-control): tried=$qualityId, cid=${identity.cid}, stopFurtherFallback=true"
                )
                return lastResult
            }
            AppLog.w(
                TAG,
                "playurl fallback request skipped: requested=$requestedQualityId, tried=$qualityId, code=${response.code}, hasData=${playInfo != null}, dashVideo=${playInfo?.dash?.video?.size ?: 0}, durl=${playInfo?.durl?.size ?: 0}, quality=${playInfo?.quality ?: 0}"
            )
        }
        return lastResult
    }

    private fun hasPlayableMedia(playInfo: PlayInfoModel?): Boolean {
        if (playInfo == null) {
            return false
        }
        val hasDashVideo = playInfo.dash?.video.orEmpty().isNotEmpty()
        val hasDurl = playInfo.durl.orEmpty().any { it.url.isNotBlank() }
        return hasDashVideo || hasDurl
    }

    private fun shouldContinueQualityFallback(
        requestedQualityId: Int,
        message: String,
        playInfo: PlayInfoModel
    ): Boolean {
        val normalizedMessage = message.lowercase()
        if (
            normalizedMessage.contains("请求的画质太高") ||
            normalizedMessage.contains("画质太高") ||
            normalizedMessage.contains("quality is too high") ||
            normalizedMessage.contains("requested quality is too high")
        ) {
            return true
        }

        val highestDeclaredQuality = buildList {
            addAll(playInfo.acceptQuality.orEmpty())
            addAll(playInfo.supportFormats.orEmpty().map { it.quality })
            add(playInfo.quality)
        }.filter { it > 0 }.maxOrNull()

        return highestDeclaredQuality != null && highestDeclaredQuality < requestedQualityId
    }

    private fun resolvePlayableQualityId(
        requestedQualityId: Int,
        playInfo: PlayInfoModel,
        availableQualities: List<VideoQuality>,
        reason: String
    ): Int {
        val streamQualityIds = playInfo.dash?.video
            .orEmpty()
            .map { it.id }
            .distinct()
        if (streamQualityIds.isNotEmpty()) {
            if (requestedQualityId in streamQualityIds) {
                return requestedQualityId
            }
            val fallbackQualityId = playInfo.quality
                .takeIf { it in streamQualityIds }
                ?: streamQualityIds.maxOrNull()
                ?: requestedQualityId
            AppLog.w(
                TAG,
                "quality fallback: reason=$reason, requested=$requestedQualityId, fallback=$fallbackQualityId, streamAvailable=$streamQualityIds, declared=${availableQualities.map { it.id }}, responseQuality=${playInfo.quality}"
            )
            return fallbackQualityId
        }
        if (availableQualities.isEmpty()) {
            return requestedQualityId
        }
        if (availableQualities.any { it.id == requestedQualityId }) {
            return requestedQualityId
        }
        val fallbackQualityId = availableQualities.maxByOrNull { it.id }?.id
            ?: availableQualities.firstOrNull()?.id
            ?: requestedQualityId
        AppLog.w(
            TAG,
            "quality fallback: reason=$reason, requested=$requestedQualityId, fallback=$fallbackQualityId, available=${availableQualities.map { it.id }}"
        )
        return fallbackQualityId
    }

    private fun currentPlayRequestIdentity(): PlayRequestIdentity? {
        val cid = currentCid.takeIf { it > 0L } ?: return null
        return PlayRequestIdentity(
            aid = currentAid,
            bvid = currentBvid?.takeIf { it.isNotBlank() },
            cid = cid,
            epId = currentEpId?.takeIf { it > 0L }
        )
    }

    private fun PlaybackPreloadTarget.toPlayRequestIdentity(): PlayRequestIdentity? {
        val resolvedCid = cid.takeIf { it > 0L } ?: return null
        val resolvedAid = aid?.takeIf { it > 0L }
        val resolvedBvid = bvid?.takeIf { it.isNotBlank() }
        val resolvedEpId = epId?.takeIf { it > 0L }
        if (resolvedAid == null && resolvedBvid.isNullOrBlank() && resolvedEpId == null) {
            return null
        }
        return PlayRequestIdentity(
            aid = resolvedAid,
            bvid = resolvedBvid,
            cid = resolvedCid,
            epId = resolvedEpId
        )
    }

    private fun consumePreloadedPlayback(
        identity: PlayRequestIdentity,
        preferLastPlayTime: Boolean,
        replaceInPlace: Boolean
    ): PreparedPlayback? {
        if (preferLastPlayTime || replaceInPlace) {
            return null
        }
        val preloaded = preloadedPlayback ?: return null
        if (preloaded.preparedPlayback.identity != identity) {
            return null
        }
        preloadedPlayback = null
        AppLog.d(TAG, "preload consumed: source=${preloaded.source}, cid=${identity.cid}")
        return preloaded.preparedPlayback.copy(
            playWhenReady = pendingPlayWhenReady,
            replaceInPlace = false
        )
    }

    private fun clearPreloadedPlayback(cancelJob: Boolean) {
        preloadedPlayback = null
        if (cancelJob) {
            preloadJob?.cancel()
            preloadJob = null
            preloadingIdentity = null
        }
    }

    private fun isActiveVideoLoad(loadGeneration: Long): Boolean {
        return loadGeneration == videoLoadGeneration
    }

    private fun shouldFallbackToUgcPlayback(response: Base2Response<*>): Boolean {
        if ((currentAid ?: 0L) <= 0L && currentBvid.isNullOrBlank()) {
            return false
        }
        val message = response.message.trim()
        return response.code == -404 ||
            message.contains("啥都木有") ||
            message.contains("啥都没有")
    }

    private fun loadPlayerExtras() {
        val cid = currentCid
        if (cid <= 0L) {
            _videoSnapshot.value = null
            return
        }

        viewModelScope.launch {
            val aid = currentAid
            val bvid = currentBvid
            val playerInfoDeferred = async {
                playInfoGateway.requestPlayerInfoData(
                    aid = aid,
                    bvid = bvid,
                    cid = cid
                )
            }
            val snapshotDeferred = async {
                playInfoGateway.requestVideoSnapshot(
                    aid = aid,
                    bvid = bvid,
                    cid = cid
                )
            }

            playerInfoDeferred.await()?.let { wrapper ->
                val subtitleTracks = wrapper.subtitle?.subtitles.orEmpty()
                if (subtitleTracks.isNotEmpty()) {
                    _subtitles.value = subtitleTracks
                    maybeAutoSelectSubtitle()
                }
                val interaction = wrapper.interaction
                if (interaction != null && interaction.graphVersion > 0L && !currentBvid.isNullOrBlank() && (currentAid ?: 0L) > 0L) {
                    currentGraphVersion = interaction.graphVersion
                    loadInteractionInfo(0L, interaction.graphVersion)
                } else if (interaction == null) {
                    currentGraphVersion = 0L
                    _interactionModel.value = null
                }
            } ?: AppLog.e(TAG, "loadPlayerExtras failed: cid=$cid")

            val snapshot = snapshotDeferred.await()
            if (currentCid == cid && currentAid == aid && currentBvid == bvid) {
                _videoSnapshot.value = snapshot
            }
        }
    }

    private fun loadVideoSnapshot() {
        val cid = currentCid.takeIf { it > 0L } ?: run {
            _videoSnapshot.value = null
            return
        }
        val aid = currentAid
        val bvid = currentBvid
        if ((aid == null || aid <= 0L) && bvid.isNullOrBlank()) {
            _videoSnapshot.value = null
            return
        }

        viewModelScope.launch {
            val snapshot = playInfoGateway.requestVideoSnapshot(
                aid = aid,
                bvid = bvid,
                cid = cid
            )
            if (currentCid == cid && currentAid == aid && currentBvid == bvid) {
                _videoSnapshot.value = snapshot
            }
        }
    }

    private fun loadInteractionInfo(edgeId: Long = 0L, graphVersion: Long? = null) {
        val bvid = currentBvid ?: return
        val aid = currentAid ?: return
        val resolvedGraphVersion = graphVersion
            ?: currentGraphVersion
        if (resolvedGraphVersion <= 0L) {
            return
        }

        viewModelScope.launch {
            val response = runCatching {
                apiService.getInteractionVideoInfo(
                    bvid = bvid,
                    aid = aid,
                    graphVersion = resolvedGraphVersion,
                    edgeId = edgeId
                )
            }.getOrNull()
            _interactionModel.value = response?.data
        }
    }

    private fun loadDanmaku(cid: Long, aid: Long, durationMs: Long) {
        danmakuLoadJob?.cancel()
        if (cid <= 0L || aid <= 0L) {
            clearDanmaku()
            return
        }
        val loadGeneration = ++danmakuLoadGeneration
        danmakuLoadJob = viewModelScope.launch {
            val danmakuStartMs = System.currentTimeMillis()
            val danmakuView = playInfoGateway.requestDanmakuViewBytes(
                cid = cid,
                aid = aid
            )?.let { bytes ->
                runCatching { DmProtoParser.parseView(bytes) }.getOrNull()
            }
            val segmentCount = danmakuView?.totalSegments
                ?.takeIf { it > 0 }
                ?: maxOf(1, ((durationMs.coerceAtLeast(1L) - 1L) / 360000L + 1L).toInt())
            logDanmakuMeta(
                cid = cid,
                aid = aid,
                durationMs = durationMs,
                segmentCount = segmentCount,
                danmakuView = danmakuView
            )
            val initialPayload = coroutineScope {
                val firstBatchDeferred = async(Dispatchers.IO) {
                    loadDanmakuSegmentPayload(
                        cid = cid,
                        aid = aid,
                        segmentIndices = listOf(1)
                    )
                }
                val specialPayloadDeferred = async(Dispatchers.IO) {
                    loadSpecialDanmakuPayload(danmakuView?.specialDanmakuUrls.orEmpty())
                }
                val firstPayload = firstBatchDeferred.await()
                val specialPayload = specialPayloadDeferred.await()
                SpecialDanmakuPayload(
                    regularItems = (firstPayload.regularItems + specialPayload.regularItems)
                        .sortedBy { it.progress },
                    specialItems = (firstPayload.specialItems + specialPayload.specialItems)
                        .sortedBy { it.progress }
                )
            }
            if (!isActiveDanmakuRequest(loadGeneration)) {
                return@launch
            }
            publishDanmaku(initialPayload.regularItems, replace = true)
            _specialDanmaku.value = initialPayload.specialItems
            logDanmakuDiagnostics(
                label = "first-batch",
                items = initialPayload.regularItems,
                danmakuView = danmakuView
            )
            if (segmentCount <= 1) {
                return@launch
            }

            val fullDanmaku = mutableListOf<DmModel>().apply { addAll(initialPayload.regularItems) }
            val fullSpecialDanmaku = mutableListOf<SpecialDanmakuModel>().apply { addAll(initialPayload.specialItems) }
            val remainingIndices = (2..segmentCount).toList()
            coroutineScope {
                val deferredResults = remainingIndices.map { segmentIndex ->
                    async(Dispatchers.IO) {
                        segmentIndex to loadDanmakuSegmentPayload(
                            cid = cid,
                            aid = aid,
                            segmentIndices = listOf(segmentIndex)
                        )
                    }
                }
                deferredResults.forEach { deferred ->
                    if (!isActiveDanmakuRequest(loadGeneration)) return@coroutineScope
                    val (segmentIndex, payload) = deferred.await()
                    val chunkDanmaku = payload.regularItems
                    val chunkSpecialDanmaku = payload.specialItems
                    if (chunkDanmaku.isEmpty() && chunkSpecialDanmaku.isEmpty()) return@forEach
                    if (chunkDanmaku.isNotEmpty()) {
                        fullDanmaku.addAll(chunkDanmaku)
                        publishDanmaku(chunkDanmaku, replace = false)
                    }
                    if (chunkSpecialDanmaku.isNotEmpty()) {
                        fullSpecialDanmaku.addAll(chunkSpecialDanmaku)
                        _specialDanmaku.value = fullSpecialDanmaku.toList()
                    }
                }
            }
            if (!isActiveDanmakuRequest(loadGeneration)) {
                return@launch
            }
            _danmaku.value = fullDanmaku.toList()
            _specialDanmaku.value = fullSpecialDanmaku.toList()
            logDanmakuDiagnostics(
                label = "full",
                items = fullDanmaku,
                danmakuView = danmakuView
            )
        }
    }

    private suspend fun loadDanmakuSegmentPayload(
        cid: Long,
        aid: Long,
        segmentIndices: List<Int>
    ): SpecialDanmakuPayload = withContext(Dispatchers.IO) {
        val regularItems = mutableListOf<DmModel>()
        val specialItems = mutableListOf<SpecialDanmakuModel>()
        segmentIndices.forEach { segmentIndex ->
            val bytes = playInfoGateway.requestDanmakuSegmentBytes(
                cid = cid,
                aid = aid,
                segmentIndex = segmentIndex
            ) ?: return@forEach
            val segment = runCatching {
                DmProtoParser.parseSegment(bytes)
            }.getOrNull() ?: return@forEach
            val regularStartIndex = regularItems.size
            val specialStartIndex = specialItems.size
            var advancedCount = 0
            val aiFlagsById = segment.aiFlag.dmFlags.associate { it.dmid to it.flag }
            val colorfulSrcByType = segment.colorfulSrc
                .filter { it.type != 0 && it.src.isNotBlank() }
                .associate { it.type to it.src }
            segment.elems.forEach { elem ->
                if (elem.mode == 7) {
                    advancedCount += 1
                    AdvancedDanmakuParser.parse(
                        id = elem.id.takeIf { it > 0L } ?: elem.progress.toLong(),
                        progressMs = elem.progress,
                        color = elem.color,
                        fontSize = elem.fontSize,
                        rawContent = elem.content
                    )?.let(specialItems::add)
                } else {
                    val rawColorfulSrc = colorfulSrcByType[elem.colorful].orEmpty()
                    regularItems += DmModel(
                        id = elem.id,
                        color = elem.color,
                        colorful = elem.colorful,
                        colorfulSrc = rawColorfulSrc,
                        colorfulStyle = DmColorfulStyleParser.parse(rawColorfulSrc),
                        content = elem.content,
                        mode = elem.mode,
                        progress = elem.progress,
                        fontSize = elem.fontSize,
                        weight = elem.weight,
                        pool = elem.pool,
                        attr = elem.attr,
                        aiFlagScore = aiFlagsById[elem.id] ?: 0,
                        midHash = elem.midHash,
                        ctime = elem.ctime,
                        action = elem.action,
                        idStr = elem.idStr,
                        animation = elem.animation
                    )
                }
            }
        }
        SpecialDanmakuPayload(
            regularItems = regularItems,
            specialItems = specialItems
        )
    }

    private suspend fun loadSpecialDanmakuPayload(urls: List<String>): SpecialDanmakuPayload = withContext(Dispatchers.IO) {
        val regularItems = mutableListOf<DmModel>()
        val specialItems = mutableListOf<SpecialDanmakuModel>()
        urls.forEach { url ->
            val bytes = playInfoGateway.requestAbsoluteBytes(url) ?: return@forEach
            val segment = runCatching {
                DmProtoParser.parseSegment(bytes)
            }.getOrNull() ?: return@forEach
            val colorfulSrcByType = segment.colorfulSrc
                .filter { it.type != 0 && it.src.isNotBlank() }
                .associate { it.type to it.src }
            segment.elems.forEach { elem ->
                when {
                    elem.mode == 7 -> {
                        AdvancedDanmakuParser.parse(
                            id = elem.id.takeIf { it > 0L } ?: elem.progress.toLong(),
                            progressMs = elem.progress,
                            color = elem.color,
                            fontSize = elem.fontSize,
                            rawContent = elem.content
                        )?.let(specialItems::add)
                    }
                    elem.mode == 9 || elem.content.contains("def text", ignoreCase = true) -> {
                        specialItems += SpecialDanmakuParser.parse(
                            parentId = elem.id.takeIf { it > 0L } ?: elem.progress.toLong(),
                            progressMs = elem.progress,
                            fallbackColor = elem.color,
                            script = elem.content
                        )
                    }
                    else -> {
                        val rawColorfulSrc = colorfulSrcByType[elem.colorful].orEmpty()
                        regularItems += DmModel(
                            id = elem.id,
                            color = elem.color,
                            colorful = elem.colorful,
                            colorfulSrc = rawColorfulSrc,
                            colorfulStyle = DmColorfulStyleParser.parse(rawColorfulSrc),
                            content = elem.content,
                            mode = elem.mode,
                            progress = elem.progress,
                            fontSize = elem.fontSize,
                            weight = elem.weight,
                            pool = elem.pool,
                            attr = elem.attr,
                            midHash = elem.midHash,
                            ctime = elem.ctime,
                            action = elem.action,
                            idStr = elem.idStr,
                            animation = elem.animation
                        )
                    }
                }
            }
        }
        SpecialDanmakuPayload(
            regularItems = regularItems,
            specialItems = specialItems
        )
    }

    private fun logDanmakuMeta(
        cid: Long,
        aid: Long,
        durationMs: Long,
        segmentCount: Int,
        danmakuView: DmWebViewReplyProto?
    ) {
        if (danmakuView == null) {
            AppLog.w(
                TAG,
                "loadDanmaku meta unavailable: cid=$cid, aid=$aid, durationMs=$durationMs, fallbackSegments=$segmentCount"
            )
            return
        }
        val specialCount = danmakuView.specialDanmakuUrls.size
        if (specialCount > 0) {
            AppLog.w(
                TAG,
                "loadDanmaku found $specialCount special BAS package(s): ${danmakuView.specialDanmakuUrls.take(2)}"
            )
        }
    }

    private fun logDanmakuDiagnostics(
        label: String,
        items: List<DmModel>,
        danmakuView: DmWebViewReplyProto?
    ) {
        if (items.isEmpty()) {
            return
        }
        val advancedCount = items.count { it.mode == 7 }
        val unsupportedCount = items.count { it.mode !in setOf(1, 4, 5, 6, 7) }
        if (advancedCount > 0) {
            AppLog.w(
                TAG,
                "loadDanmaku diagnostics[$label]: advanced mode=7 danmaku will be downgraded to plain text rendering"
            )
        }
        if (unsupportedCount > 0) {
            AppLog.w(
                TAG,
                "loadDanmaku diagnostics[$label]: found $unsupportedCount unsupported danmaku item(s) whose mode is outside rolling/top/bottom/reverse/advanced"
            )
        }
        logSpecialColorCandidates(label, items)
    }

    private fun logSpecialColorCandidates(
        label: String,
        items: List<DmModel>
    ) {
        val candidates = items.filter { item ->
            item.mode in setOf(1, 6) && (
                item.action.isNotBlank() ||
                    item.animation.isNotBlank() ||
                    item.colorful != 0 ||
                    item.colorfulSrc.isNotBlank() ||
                    item.attr != 0 ||
                    item.pool != 0 ||
                    item.aiFlagScore > 0
                )
        }
        if (candidates.isEmpty()) {
            return
        }
        val colorfulSummary = candidates.groupingBy { it.colorful }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .joinToString(separator = ",") { "${it.key}:${it.value}" }
        val attrSummary = candidates.groupingBy {
            "${it.attr}/${it.colorful}/${if (it.colorfulSrc.isNotBlank()) "src" else "nosrc"}"
        }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .joinToString(separator = ",") { "${it.key}:${it.value}" }
        AppLog.w(
            TAG,
            "loadDanmaku candidates[$label]: found ${candidates.size} rolling candidate(s) with extra style fields, colorfulSummary=$colorfulSummary, attrSummary=$attrSummary"
        )
        if (!verboseDanmakuCandidateLog) {
            return
        }
        candidates
            .sortedWith(
                compareByDescending<DmModel> { it.colorfulSrc.isNotBlank() }
                    .thenByDescending { it.colorful != 0 }
                    .thenByDescending { it.attr != 0 }
                    .thenBy { it.progress }
            )
            .distinctBy { Triple("${it.attr}/${it.colorful}", it.color, it.content.take(12)) }
            .take(8)
            .forEachIndexed { index, item ->
                AppLog.w(
                    TAG,
                    "loadDanmaku candidate[$label][$index]: id=${item.id}, progress=${item.progress}, mode=${item.mode}, color=${item.color.toColorHex()}, colorful=${item.colorful}, colorfulSrc=${item.colorfulSrc.toPreview(120)}, pool=${item.pool}, attr=${item.attr}, ai=${item.aiFlagScore}, action=${item.action.toPreview(120)}, animation=${item.animation.toPreview(120)}, content=${item.content.toPreview(60)}"
                )
            }
    }

    private fun Int.toColorHex(): String {
        return "0x" + toUInt().toString(16).uppercase().padStart(8, '0')
    }

    private fun String.toPreview(limit: Int): String {
        val normalized = replace('\n', ' ').replace('\r', ' ').trim()
        if (normalized.isEmpty()) {
            return "<empty>"
        }
        return if (normalized.length <= limit) {
            normalized
        } else {
            normalized.take(limit) + "..."
        }
    }

    private fun publishDanmaku(items: List<DmModel>, replace: Boolean) {
        _danmaku.value = if (replace) {
            items
        } else {
            _danmaku.value.orEmpty() + items
        }
        _danmakuUpdates.tryEmit(DanmakuUpdate(
            items = items,
            replace = replace
        ))
    }

    private fun clearDanmaku() {
        danmakuLoadJob?.cancel()
        _danmaku.value = emptyList()
        _specialDanmaku.value = emptyList()
        _danmakuUpdates.tryEmit(DanmakuUpdate(
            items = emptyList(),
            replace = true
        ))
    }

    private fun isActiveDanmakuRequest(loadGeneration: Long): Boolean {
        return danmakuLoadGeneration == loadGeneration && currentCid == loadedDanmakuCid
    }

    private fun capturePlaybackSnapshot(positionMs: Long, playWhenReady: Boolean) {
        pendingSeekPositionMs = positionMs.coerceAtLeast(0L)
        pendingPlayWhenReady = playWhenReady
    }

    private fun applySelectionSnapshot(snapshot: VideoPlayerStreamResolver.SelectionSnapshot) {
        selectedQualityId = snapshot.selectedQualityId
        selectedAudioId = snapshot.selectedAudioId
        selectedCodec = snapshot.selectedCodec
        _qualities.value = snapshot.qualities
        _selectedQuality.value = snapshot.qualities.firstOrNull { it.id == selectedQualityId }
        _audioQualities.value = snapshot.audios
        _selectedAudioQuality.value = snapshot.audios.firstOrNull { it.id == selectedAudioId }
        _videoCodecs.value = snapshot.codecs
        _selectedVideoCodec.value = snapshot.selectedCodec
    }

    private fun resolveSelectionSnapshot(
        playInfo: PlayInfoModel
    ): VideoPlayerStreamResolver.SelectionSnapshot? {
        val selectionSnapshot = streamResolver.resolveSelections(
            playInfo = playInfo,
            preferredQualityId = requestedQualityId ?: selectedQualityId,
            preferredAudioId = requestedAudioId ?: selectedAudioId,
            preferredCodec = requestedCodec ?: selectedCodec,
            hardwareSupportedCodecs = hardwareSupportedVideoCodecs
        )
        return selectionSnapshot.takeIf { it.selectedQualityId != null || it.selectedAudioId != null || it.selectedCodec != null }
    }

    private suspend fun loadSubtitleData(track: SubtitleInfoModel): SubtitleData? =
        withContext(Dispatchers.IO) {
            val normalizedUrl = normalizeUrl(track.subtitleUrl)
            subtitleCache[normalizedUrl]?.let { return@withContext it }

            runCatching {
                val request = Request.Builder()
                    .url(normalizedUrl)
                    .header("Referer", "https://www.bilibili.com")
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"
                    )
                    .build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@use null
                    }
                    response.body?.charStream()?.use { reader ->
                        gson.fromJson(reader, SubtitleData::class.java)
                    }
                }
            }.getOrNull()?.also {
                subtitleCache[normalizedUrl] = it
            }
        }

    private fun maybeAutoSelectSubtitle() {
        if (!shouldAutoSelectSubtitle) {
            return
        }
        val subtitles = _subtitles.value.orEmpty()
        if (subtitles.isEmpty()) {
            return
        }
        shouldAutoSelectSubtitle = false
        selectSubtitle(0)
    }

    private fun updateSubtitleText(positionMs: Long) {
        val data = currentSubtitleData?.body.orEmpty()
        if ((_selectedSubtitleIndex.value ?: -1) < 0 || data.isEmpty()) {
            currentSubtitleCueIndex = 0
            if (_currentSubtitleText.value != null) {
                _currentSubtitleText.value = null
            }
            return
        }
        val positionSeconds = positionMs / 1000f
        val cue = data.findCueAt(positionSeconds)
        val subtitleText = cue?.content
        if (_currentSubtitleText.value != subtitleText) {
            _currentSubtitleText.value = subtitleText
        }
    }

    private fun List<SubtitleItem>.findCueAt(positionSeconds: Float): SubtitleItem? {
        if (isEmpty()) {
            currentSubtitleCueIndex = 0
            return null
        }
        var index = currentSubtitleCueIndex.coerceIn(0, lastIndex)
        if (positionSeconds < this[index].from) {
            while (index > 0 && positionSeconds < this[index].from) {
                index--
            }
        } else {
            while (index < lastIndex && positionSeconds > this[index].to) {
                index++
            }
        }
        currentSubtitleCueIndex = index
        val cue = this[index]
        return cue.takeIf { positionSeconds >= it.from && positionSeconds <= it.to }
    }

    private fun normalizeUrl(rawUrl: String): String {
        return when {
            rawUrl.startsWith("//") -> "https:$rawUrl"
            rawUrl.startsWith("http://") || rawUrl.startsWith("https://") -> rawUrl
            else -> "https://$rawUrl"
        }
    }

    private fun DetailSubtitleItem.toSubtitleInfoModel(): SubtitleInfoModel {
        return SubtitleInfoModel(
            id = id,
            lan = lan,
            lanDoc = lanDoc,
            isLock = isLock,
            subtitleUrl = subtitleUrl
        )
    }

    private fun isPgcPlayback(): Boolean {
        return (currentEpId ?: 0L) > 0L || (currentSeasonId ?: 0L) > 0L
    }
}





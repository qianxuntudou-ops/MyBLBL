@file:Suppress("SpellCheckingInspection")

package com.tutu.myblbl.feature.player

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.MediaSource
import com.google.gson.Gson
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
import com.tutu.myblbl.network.cookie.CookieManager
import com.tutu.myblbl.feature.player.cache.PlayerMediaCache
import com.tutu.myblbl.feature.player.settings.PlayerSettings
import com.tutu.myblbl.feature.player.settings.PlayerSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    context: Context
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

    private data class PlayInfoFetchResult(
        val requestedQualityId: Int,
        val response: VideoPlayerPlayInfoGateway.PlayInfoResult
    )

    private val _resumeHint = MutableLiveData<ResumeProgressHint?>()
    val resumeHint: LiveData<ResumeProgressHint?> = _resumeHint

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
    private val dataSourceFactory = PlayerMediaCache.buildDataSourceFactory(
        context = appContext,
        upstreamFactory = OkHttpDataSource.Factory(playerOkHttpClient)
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
    )

    // Keeps stream selection and fallback policy out of the ViewModel's lifecycle code.
    private val streamResolver = VideoPlayerStreamResolver(
        dataSourceFactory = dataSourceFactory,
        urlNormalizer = ::normalizeUrl
    )
    private val qualityPolicy = VideoPlayerQualityPolicy()
    // Keeps episode-list construction and PGC header mapping out of playback request flow.
    private val episodeCatalogBuilder = VideoPlayerEpisodeCatalogBuilder(apiService)
    // Encapsulates PGC/UGC play-info retries and WBI-dependent requests away from UI state changes.
    private val playInfoGateway = VideoPlayerPlayInfoGateway(
        apiService = apiService,
        okHttpClient = okHttpClient,
        cookieManager = cookieManager,
        sessionGateway = sessionGateway,
        securityGateway = securityGateway,
        logTag = TAG
    )

    private val subtitleCache = mutableMapOf<String, SubtitleData>()

    private val _videoInfo = MutableLiveData<VideoDetailModel?>()
    val videoInfo: LiveData<VideoDetailModel?> = _videoInfo

    private val _relatedVideos = MutableLiveData<List<VideoModel>>(emptyList())
    val relatedVideos: LiveData<List<VideoModel>> = _relatedVideos

    private val _episodes = MutableLiveData<List<PlayableEpisode>>(emptyList())
    val episodes: LiveData<List<PlayableEpisode>> = _episodes

    private val _selectedEpisodeIndex = MutableLiveData(0)
    val selectedEpisodeIndex: LiveData<Int> = _selectedEpisodeIndex

    private val _playbackRequest = MutableLiveData<PlaybackRequest?>()
    val playbackRequest: LiveData<PlaybackRequest?> = _playbackRequest

    private val _currentPosition = MutableLiveData(0L)
    val currentPosition: LiveData<Long> = _currentPosition

    private val _duration = MutableLiveData(0L)
    val duration: LiveData<Long> = _duration

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>(null)
    val error: LiveData<String?> = _error

    private val _qualities = MutableLiveData<List<VideoQuality>>(emptyList())
    val qualities: LiveData<List<VideoQuality>> = _qualities

    private val _selectedQuality = MutableLiveData<VideoQuality?>(null)
    val selectedQuality: LiveData<VideoQuality?> = _selectedQuality

    private val _audioQualities = MutableLiveData<List<AudioQuality>>(emptyList())
    val audioQualities: LiveData<List<AudioQuality>> = _audioQualities

    private val _selectedAudioQuality = MutableLiveData<AudioQuality?>(null)
    val selectedAudioQuality: LiveData<AudioQuality?> = _selectedAudioQuality

    private val _videoCodecs = MutableLiveData<List<VideoCodecEnum>>(emptyList())
    val videoCodecs: LiveData<List<VideoCodecEnum>> = _videoCodecs

    private val _selectedVideoCodec = MutableLiveData<VideoCodecEnum?>(null)
    val selectedVideoCodec: LiveData<VideoCodecEnum?> = _selectedVideoCodec

    private val _subtitles = MutableLiveData<List<SubtitleInfoModel>>(emptyList())
    val subtitles: LiveData<List<SubtitleInfoModel>> = _subtitles

    private val _selectedSubtitleIndex = MutableLiveData(-1)
    val selectedSubtitleIndex: LiveData<Int> = _selectedSubtitleIndex

    private val _currentSubtitleText = MutableLiveData<String?>(null)
    val currentSubtitleText: LiveData<String?> = _currentSubtitleText

    private val _danmaku = MutableLiveData<List<DmModel>>(emptyList())
    val danmaku: LiveData<List<DmModel>> = _danmaku

    private val _danmakuUpdates = MutableLiveData<DanmakuUpdate>()
    val danmakuUpdates: LiveData<DanmakuUpdate> = _danmakuUpdates

    private val _specialDanmaku = MutableLiveData<List<SpecialDanmakuModel>>(emptyList())
    val specialDanmaku: LiveData<List<SpecialDanmakuModel>> = _specialDanmaku

    private val _interactionModel = MutableLiveData<InteractionModel?>(null)
    val interactionModel: LiveData<InteractionModel?> = _interactionModel

    private val _videoSnapshot = MutableLiveData<VideoSnapshotData?>(null)
    val videoSnapshot: LiveData<VideoSnapshotData?> = _videoSnapshot

    private val _currentCidLive = MutableLiveData(0L)
    val currentCidLive: LiveData<Long> = _currentCidLive

    private val _riskControlVVoucher = MutableLiveData<String?>(null)
    val riskControlVVoucher: LiveData<String?> = _riskControlVVoucher

    fun consumeRiskControlVVoucher(): String? {
        val value = _riskControlVVoucher.value
        _riskControlVVoucher.value = null
        return value
    }

    fun onGaiaVgateResult(gaiaVtoken: String) {
        val expiresAt = System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000
        cookieManager.saveCookies(
            listOf(
                "x-bili-gaia-vtoken=$gaiaVtoken; domain=bilibili.com; path=/; secure; expires=$expiresAt"
            )
        )
        AppLog.i(TAG, "gaia_vtoken saved, triggering playInfo refresh")
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
    private val fallbackCodecPriorityOrder = listOf(
        VideoCodecEnum.AV1,
        VideoCodecEnum.HEVC,
        VideoCodecEnum.AVC
    )

    fun loadVideoInfo(
        aid: Long? = null,
        bvid: String? = null,
        cid: Long = 0L,
        seasonId: Long = 0L,
        epId: Long = 0L,
        seekPositionMs: Long = 0L,
        startEpisodeIndex: Int = -1
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
        selectedQualityId = currentSettings.defaultVideoQualityId
        selectedAudioId = currentSettings.defaultAudioQualityId
        selectedCodec = currentSettings.defaultVideoCodec
        pendingSeekPositionMs = seekPositionMs.coerceAtLeast(0L)
        pendingPlayWhenReady = true
        launchStartEpisodeIndex = startEpisodeIndex
        didApplyLastPlayPosition = pendingSeekPositionMs > 0L
        shouldAutoSelectSubtitle = currentSettings.showSubtitleByDefault
        sessionStartTimestampMs = 0L
        lastReportedHeartbeatPositionSec = -1L
        resetFallbackState()
        val loadGeneration = ++videoLoadGeneration
        _selectedSubtitleIndex.value = -1
        _currentSubtitleText.value = null
        currentSubtitleCueIndex = 0
        clearDanmaku()
        _interactionModel.value = null
        _videoSnapshot.value = null
        _error.value = null
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { playInfoGateway.warmupWbiKeys() }
        }

        viewModelScope.launch {
            _isLoading.value = true
            try {
                AppLog.d(
                    TAG,
                    "loadVideoInfo start: aid=${currentAid ?: 0L}, bvid=${currentBvid.orEmpty()}, cid=$currentCid, seasonId=${currentSeasonId ?: 0L}, epId=${currentEpId ?: 0L}, seek=$pendingSeekPositionMs"
                )
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
        loadPlayUrl(preferLastPlayTime = false)
    }

    fun playRelatedVideo(video: VideoModel) {
        val targetAid = video.aid.takeIf { it > 0L } ?: currentAid
        val targetBvid = video.bvid.takeIf { it.isNotBlank() } ?: currentBvid
        val targetSeasonId = video.playbackSeasonId.takeIf { it > 0L }
        val targetEpId = video.epid.takeIf { it > 0L }
        if (targetAid == null && targetBvid.isNullOrBlank() && targetEpId == null && targetSeasonId == null) {
            _error.value = "相关推荐缺少视频标识"
            return
        }
        reportPlaybackHeartbeat()
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
        loadPlayUrl(preferLastPlayTime = false)
        loadInteractionInfo(edgeId)
        loadVideoSnapshot()
    }

    fun selectVideoQuality(
        quality: VideoQuality,
        currentPositionMs: Long,
        playWhenReady: Boolean
    ) {
        selectedQualityId = quality.id
        _selectedQuality.value = quality
        capturePlaybackSnapshot(currentPositionMs, playWhenReady)
        loadPlayUrl(preferLastPlayTime = false, replaceInPlace = true)
    }

    fun selectAudioQuality(
        quality: AudioQuality,
        currentPositionMs: Long,
        playWhenReady: Boolean
    ) {
        selectedAudioId = quality.id
        _selectedAudioQuality.value = quality
        capturePlaybackSnapshot(currentPositionMs, playWhenReady)
        rebuildPlayback()
    }

    fun selectVideoCodec(
        codec: VideoCodecEnum,
        currentPositionMs: Long,
        playWhenReady: Boolean
    ) {
        selectedCodec = codec
        _selectedVideoCodec.value = codec
        capturePlaybackSnapshot(currentPositionMs, playWhenReady)
        rebuildPlayback()
    }

    fun selectSubtitle(index: Int) {
        _selectedSubtitleIndex.value = index
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
        val mediaSource = resolveMediaSource(playInfo) ?: run {
            _error.value = "当前清晰度/音轨组合不可播放"
            return
        }
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
                val preparedPlayback = requestPreparedPlayback(
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
        AppLog.d(
            TAG,
            "loadUgcVideoInfo detail success: aid=${currentAid ?: 0L}, bvid=${currentBvid.orEmpty()}, cid=$currentCid, episodes=${episodeItems.size}, selectedIndex=$selectedIndex"
        )

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
        qualityCandidates: List<Int> = qualityPolicy.buildCandidates(selectedQualityId)
    ): PreparedPlayback? {
        val requestStartMs = System.currentTimeMillis()
        val preferredQualityId = qualityCandidates.firstOrNull() ?: selectedQualityId ?: 80
        AppLog.d(
            TAG,
            "loadPlayUrl start: aid=${identity.aid ?: 0L}, bvid=${identity.bvid.orEmpty()}, cid=${identity.cid}, seasonId=${currentSeasonId ?: 0L}, epId=${identity.epId ?: 0L}, qn=$preferredQualityId, candidates=$qualityCandidates, fnval=${streamResolver.buildFnval(preferredQualityId)}, fourk=${streamResolver.buildFourk(preferredQualityId)}, hasWbi=${playInfoGateway.hasWbiKeys()}"
        )

        val cachedPlayInfo = identity.bvid
            ?.takeIf { !replaceInPlace && it.isNotBlank() && identity.epId == null }
            ?.let { bvid -> VideoPlayerPlayInfoCache.get(bvid = bvid, cid = identity.cid) }
            ?.takeIf(::hasPlayableMedia)

        val (initialPlayInfo, effectiveRequestedQualityId) = if (cachedPlayInfo != null) {
            AppLog.d(
                TAG,
                "loadPlayUrl cache hit: bvid=${identity.bvid.orEmpty()}, cid=${identity.cid}, qn=$preferredQualityId"
            )
            cachedPlayInfo to preferredQualityId
        } else {
            val playInfoFetch = requestPlayInfoWithQualityFallback(
                identity = identity,
                qualityCandidates = qualityCandidates
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
                    _riskControlVVoucher.value = vVoucher
                    _error.value = "账号触发风控验证，正在请求人机验证…"
                } else if (response.isTryLookBypass) {
                    _error.value = "当前账号可能被风控，已降级为试看模式"
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
            preferredAudioId = selectedAudioId,
            preferredCodec = selectedCodec
        )
        if (selectionSnapshot.selectedQualityId != resolvedQualityId) {
            selectionSnapshot = selectionSnapshot.copy(selectedQualityId = resolvedQualityId)
        }
        val mediaSourceSelection = streamResolver.buildMediaSource(
            playInfo = initialPlayInfo,
            selectedQualityId = resolvedQualityId,
            selectedAudioId = selectionSnapshot.selectedAudioId,
            selectedCodec = selectionSnapshot.selectedCodec
        )

        AppLog.d(
            TAG,
            "loadPlayUrl success: dashVideo=${initialPlayInfo.dash?.video?.size ?: 0}, dashAudio=${initialPlayInfo.dash?.audio?.size ?: 0}, durl=${initialPlayInfo.durl?.size ?: 0}, quality=${initialPlayInfo.quality}"
        )

        val useServerResume = preferLastPlayTime &&
            initialPlayInfo.lastPlayCid == identity.cid &&
            initialPlayInfo.lastPlayTime > 5000L

        val rawResumePosition = when {
            useServerResume -> initialPlayInfo.lastPlayTime
            else -> playbackPositionMs
        }

        val shouldResume = rawResumePosition > 5000L

        val startPosition = rawResumePosition.takeIf { shouldResume } ?: 0L

        AppLog.d(
            TAG,
            "loadPlayUrl resume check: preferLastPlayTime=$preferLastPlayTime, lastPlayCid=${initialPlayInfo.lastPlayCid}, cid=${identity.cid}, lastPlayTime=${initialPlayInfo.lastPlayTime}, pendingSeekPositionMs=$pendingSeekPositionMs, useServerResume=$useServerResume, rawResumePosition=$rawResumePosition, shouldResume=$shouldResume"
        )

        val mediaSource = mediaSourceSelection?.mediaSource ?: run {
            AppLog.e(TAG, "loadPlayUrl mediaSource missing: cid=${identity.cid}")
            return null
        }

        val resumeHintPositionMs = startPosition.takeIf { shouldResume && !replaceInPlace }
        val seekToStart = if (resumeHintPositionMs != null) 0L else startPosition
        AppLog.d(
            TAG,
            "loadPlayUrl: startPosition=$startPosition, shouldShowHint=${resumeHintPositionMs != null}, seekToStart=$seekToStart, replaceInPlace=$replaceInPlace"
        )
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
        currentPlayInfo = preparedPlayback.playInfo
        selectedQualityId = preparedPlayback.selectionSnapshot.selectedQualityId
        selectedAudioId = preparedPlayback.selectionSnapshot.selectedAudioId
        selectedCodec = preparedPlayback.selectionSnapshot.selectedCodec
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
            lockedQualityId = preparedPlayback.selectionSnapshot.selectedQualityId ?: selectedQualityId ?: 80,
            selectedAudioId = preparedPlayback.selectionSnapshot.selectedAudioId,
            preferredCodec = preparedPlayback.selectionSnapshot.selectedCodec,
            resetAttempts = resetFallbackAttempts
        )
        rememberCurrentFallbackAttempt(countAsFallback = countCurrentAttemptAsFallback)

        _playbackRequest.value = PlaybackRequest(
            mediaSource = preparedPlayback.mediaSource,
            seekPositionMs = preparedPlayback.seekToStart,
            playWhenReady = preparedPlayback.playWhenReady,
            replaceInPlace = preparedPlayback.replaceInPlace
        )
        AppLog.d(
            TAG,
            "loadPlayUrl prepared playback request in ${preparedPlayback.requestDurationMs}ms: cid=${preparedPlayback.identity.cid}, seek=${preparedPlayback.seekToStart}, replaceInPlace=${preparedPlayback.replaceInPlace}"
        )
        preparedPlayback.resumeHintPositionMs?.let { targetPositionMs ->
            _resumeHint.value = ResumeProgressHint(targetPositionMs = targetPositionMs)
        }
        _error.value = null
        loadPlayerExtras()
        if (loadedDanmakuCid != preparedPlayback.identity.cid) {
            loadedDanmakuCid = preparedPlayback.identity.cid
            val danmakuAid = currentAid
                ?: preparedPlayback.identity.aid
                ?: 0L
            loadDanmaku(
                cid = preparedPlayback.identity.cid,
                aid = danmakuAid,
                durationMs = preparedPlayback.playInfo.timeLength
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
                trySwitchCodec(lastPlaybackPositionMs, reason = "decoder_error") ||
                    tryNextCdnInCurrentCodec(lastPlaybackPositionMs, reason = "decoder_error_cdn_retry") ||
                    tryRefreshPlayInfo(reason = "decoder_error_refresh")
            }
            PlaybackErrorType.NETWORK -> {
                tryNextCdnInCurrentCodec(lastPlaybackPositionMs, reason = "network_error") ||
                    tryRefreshPlayInfo(reason = "network_error_refresh") ||
                    trySwitchCodec(lastPlaybackPositionMs, reason = "network_error_codec_switch")
            }
            PlaybackErrorType.UNKNOWN -> {
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
        val lockedQualityId = selectedQualityId ?: 80
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
            val selection = streamResolver.buildMediaSourceWithUrls(
                videoUrl = videoUrl,
                audioUrl = audioUrl,
                videoMimeType = route.videoMimeType,
                audioMimeType = route.audioMimeType,
                availableCodecs = plan.routes.map { it.codec },
                selectedCodec = route.codec
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
            codecPriorityOrder = fallbackCodecPriorityOrder
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
        qualityCandidates: List<Int>
    ): PlayInfoFetchResult? {
        val attemptedQualities = linkedSetOf<Int>()
        val requestedQualityId = qualityCandidates.firstOrNull() ?: selectedQualityId ?: 80
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
        val fallbackQualityId = availableQualities.firstOrNull()?.id
            ?: availableQualities.maxByOrNull { it.id }?.id
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
                AppLog.d(
                    TAG,
                    "loadPlayerExtras success: cid=$cid, subtitles=${wrapper.subtitle?.subtitles?.size ?: 0}, interaction=${wrapper.interaction != null}"
                )
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
            AppLog.d(
                TAG,
                "loadDanmaku first batch ready in ${System.currentTimeMillis() - danmakuStartMs}ms: cid=$cid, items=${initialPayload.regularItems.size}, specialItems=${initialPayload.specialItems.size}, segments=$segmentCount"
            )
            if (segmentCount <= 1) {
                return@launch
            }

            val fullDanmaku = mutableListOf<DmModel>().apply { addAll(initialPayload.regularItems) }
            val fullSpecialDanmaku = mutableListOf<SpecialDanmakuModel>().apply { addAll(initialPayload.specialItems) }
            val remainingIndices = (2..segmentCount).toList()
            coroutineScope {
                remainingIndices.chunked(3).forEach { chunk ->
                    val batchResults = chunk.map { segmentIndex ->
                        async(Dispatchers.IO) {
                            loadDanmakuSegmentPayload(
                                cid = cid,
                                aid = aid,
                                segmentIndices = listOf(segmentIndex)
                            )
                        }
                    }.awaitAll()
                    val chunkDanmaku = buildList {
                        batchResults.forEach { addAll(it.regularItems) }
                    }
                    val chunkSpecialDanmaku = buildList {
                        batchResults.forEach { addAll(it.specialItems) }
                    }
                    if (!isActiveDanmakuRequest(loadGeneration) || (chunkDanmaku.isEmpty() && chunkSpecialDanmaku.isEmpty())) {
                        return@forEach
                    }
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
            AppLog.d(
                TAG,
                "loadDanmaku fully loaded in ${System.currentTimeMillis() - danmakuStartMs}ms: cid=$cid, items=${fullDanmaku.size}, segments=$segmentCount"
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
            AppLog.d(
                TAG,
                "loadDanmaku segment[$segmentIndex]: bytes=${bytes.size}, state=${segment.state}, elems=${segment.elems.size}, regularAdded=${regularItems.size - regularStartIndex}, specialAdded=${specialItems.size - specialStartIndex}, advancedRaw=$advancedCount, colorfulSrc=${segment.colorfulSrc.size}"
            )
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
        val smartFilter = danmakuView.smartFilterConfig
        AppLog.d(
            TAG,
            "loadDanmaku meta: cid=$cid, aid=$aid, durationMs=$durationMs, segments=$segmentCount, totalCount=${danmakuView.totalCount}, pageSizeMs=${danmakuView.segmentDurationMs}, specialPackages=$specialCount, smartFilterCloudLevel=${smartFilter.cloudLevel}, smartFilterCloudSwitch=${smartFilter.cloudSwitch}, smartFilterCloudText=${smartFilter.cloudText.ifBlank { "-" }}, smartFilterPlayerLevel=${smartFilter.playerLevel}, smartFilterPlayerEnabled=${smartFilter.playerEnabled}, smartFilterResolvedLevel=${smartFilter.resolvedLevel}, smartFilterResolvedEnabled=${smartFilter.resolvedEnabled}"
        )
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
            AppLog.d(TAG, "loadDanmaku diagnostics[$label]: empty")
            return
        }
        val modeSummary = items.groupingBy { it.mode }
            .eachCount()
            .toSortedMap()
            .entries
            .joinToString(separator = ",") { "${it.key}:${it.value}" }
        val coloredCount = items.count { color ->
            color.color != 0 && color.color != 0xFFFFFF
        }
        val advancedCount = items.count { it.mode == 7 }
        val colorfulCount = items.count { it.colorful != 0 }
        val colorfulSrcCount = items.count { it.colorfulSrc.isNotBlank() }
        val actionCount = items.count { it.action.isNotBlank() }
        val animationCount = items.count { it.animation.isNotBlank() }
        val unsupportedCount = items.count { it.mode !in setOf(1, 4, 5, 6, 7) }
        AppLog.d(
            TAG,
            "loadDanmaku diagnostics[$label]: total=${items.size}, modes=$modeSummary, colored=$coloredCount, colorful=$colorfulCount, colorfulSrc=$colorfulSrcCount, advanced=$advancedCount, action=$actionCount, animation=$animationCount, unsupportedModes=$unsupportedCount, specialPackages=${danmakuView?.specialDanmakuUrls?.size ?: 0}"
        )
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
            AppLog.d(TAG, "loadDanmaku candidates[$label]: no rolling special-color candidates")
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
        _danmakuUpdates.value = DanmakuUpdate(
            items = items,
            replace = replace
        )
    }

    private fun clearDanmaku() {
        danmakuLoadJob?.cancel()
        _danmaku.value = emptyList()
        _specialDanmaku.value = emptyList()
        _danmakuUpdates.value = DanmakuUpdate(
            items = emptyList(),
            replace = true
        )
    }

    private fun isActiveDanmakuRequest(loadGeneration: Long): Boolean {
        return danmakuLoadGeneration == loadGeneration && currentCid == loadedDanmakuCid
    }

    private fun capturePlaybackSnapshot(positionMs: Long, playWhenReady: Boolean) {
        pendingSeekPositionMs = positionMs.coerceAtLeast(0L)
        pendingPlayWhenReady = playWhenReady
    }

    private fun applySelectionSnapshot(snapshot: VideoPlayerStreamResolver.SelectionSnapshot) {
        if (selectedQualityId != null && snapshot.selectedQualityId != selectedQualityId) {
            AppLog.d(
                TAG,
                "syncSelections: quality downgraded to ${VideoQuality.fromId(snapshot.selectedQualityId ?: 0).name}"
            )
        }
        if (selectedAudioId != null && snapshot.selectedAudioId != selectedAudioId && snapshot.selectedAudioId != null) {
            AppLog.d(
                TAG,
                "syncSelections: audio quality downgraded to ${AudioQuality.fromId(snapshot.selectedAudioId).name}"
            )
        }
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

    private fun resolveMediaSource(playInfo: PlayInfoModel): MediaSource? {
        val selection = streamResolver.buildMediaSource(
            playInfo = playInfo,
            selectedQualityId = selectedQualityId,
            selectedAudioId = selectedAudioId,
            selectedCodec = selectedCodec
        ) ?: return null
        if (selection.availableCodecs.isNotEmpty()) {
            _videoCodecs.value = selection.availableCodecs
        }
        if (selectedCodec != selection.selectedCodec) {
            selectedCodec = selection.selectedCodec
            _selectedVideoCodec.value = selection.selectedCodec
        }
        return selection.mediaSource
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
            _currentSubtitleText.value = null
            return
        }
        val positionSeconds = positionMs / 1000f
        val cue = data.findCueAt(positionSeconds)
        val subtitleText = cue?.content
        _currentSubtitleText.value = subtitleText
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





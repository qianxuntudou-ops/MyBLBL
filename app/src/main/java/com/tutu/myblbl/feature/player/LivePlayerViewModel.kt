package com.tutu.myblbl.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.repository.LiveRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.util.Locale

class LivePlayerViewModel(
    private val repository: LiveRepository
) : ViewModel() {

    companion object {
        private const val TAG = "LivePlayerViewModel"
        private const val HEARTBEAT_INTERVAL_MS = 60_000L
    }

    private val _playUrl = MutableStateFlow<String?>(null)
    val playUrl: StateFlow<String?> = _playUrl.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _qualities = MutableStateFlow<List<LiveQualityInfo>>(emptyList())
    val qualities: StateFlow<List<LiveQualityInfo>> = _qualities.asStateFlow()
    private val _selectedQuality = MutableStateFlow<LiveQualityInfo?>(null)
    val selectedQuality: StateFlow<LiveQualityInfo?> = _selectedQuality.asStateFlow()

    private val _liveDuration = MutableStateFlow("")
    val liveDuration: StateFlow<String> = _liveDuration.asStateFlow()

    private val _roomTitle = MutableStateFlow("")
    val roomTitle: StateFlow<String> = _roomTitle.asStateFlow()

    private val _anchorName = MutableStateFlow("")
    val anchorName: StateFlow<String> = _anchorName.asStateFlow()

    private val _refreshEvent = Channel<String>(Channel.BUFFERED)
    val refreshEvent = _refreshEvent.receiveAsFlow()

    private var currentRoomId: Long = 0
    private var heartbeatJob: Job? = null
    private var durationJob: Job? = null

    fun loadLiveStream(roomId: Long) {
        currentRoomId = roomId
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            // 步骤1：先获取IP信息（为CDN优选提供数据）
            runCatching { repository.getIpInfo() }
                .onFailure { AppLog.w(TAG, "getIpInfo failed: ${it.message}") }

            // 步骤2：获取直播流（此时remote层已缓存IP信息，CDN优选生效）
            repository.getLivePlayInfo(roomId).fold(
                onSuccess = { data ->
                    val durl = data.durl
                    if (!durl.isNullOrEmpty()) {
                        _playUrl.value = durl[0].url

                        AppLog.d(TAG, "loadLiveStream: liveTime=${data.liveTime} title=${data.roomTitle} anchor=${data.anchorName} qualities=${data.qualityDescription?.size}")
                        _roomTitle.value = data.roomTitle ?: ""
                        _anchorName.value = data.anchorName ?: ""
                        data.liveTime?.let { startLiveDuration(it) }

                        data.qualityDescription?.let { qualities ->
                            val mappedQualities = qualities.map {
                                LiveQualityInfo(it.qn, it.desc)
                            }
                            _qualities.value = mappedQualities
                            _selectedQuality.value = mappedQualities.firstOrNull {
                                it.qn == data.currentQn
                            } ?: mappedQualities.firstOrNull()
                        }
                    } else {
                        _error.value = "无法获取直播流地址"
                    }

                    // 步骤3-6：播放成功后，后台补充风控行为链（不阻塞播放）
                    launchSupplementaryCalls(roomId)
                },
                onFailure = { e ->
                    _error.value = e.message ?: "加载直播失败"
                }
            )

            _isLoading.value = false
        }
    }

    private fun launchSupplementaryCalls(roomId: Long) {
        // 进房上报
        viewModelScope.launch {
            runCatching { repository.reportRoomEntry(roomId) }
                .onFailure { AppLog.w(TAG, "reportRoomEntry failed: ${it.message}") }
        }

        // 心跳密钥
        viewModelScope.launch {
            runCatching { repository.getHeartbeatKey(roomId) }
                .onFailure { AppLog.w(TAG, "getHeartbeatKey failed: ${it.message}") }
        }

        // 用户房间状态
        viewModelScope.launch {
            runCatching { repository.getUserRoomInfo(roomId) }
                .onFailure { AppLog.w(TAG, "getUserRoomInfo failed: ${it.message}") }
        }

        // 历史弹幕
        viewModelScope.launch {
            runCatching { repository.getDanmuHistory(roomId) }
                .onFailure { AppLog.w(TAG, "getDanmuHistory failed: ${it.message}") }
        }

        // 启动心跳定时器
        startHeartbeat(roomId)
    }

    private fun startHeartbeat(roomId: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = viewModelScope.launch {
            while (true) {
                delay(HEARTBEAT_INTERVAL_MS)
                runCatching { repository.sendLiveHeartbeat(roomId) }
                    .onFailure { AppLog.w(TAG, "sendLiveHeartbeat failed: ${it.message}") }
            }
        }
    }

    private fun startLiveDuration(liveTime: String) {
        durationJob?.cancel()
        val startMs = liveTime.toLongOrNull()?.let { it * 1000L }
        if (startMs == null) {
            AppLog.w(TAG, "startLiveDuration: failed to parse liveTime=$liveTime")
            return
        }
        AppLog.d(TAG, "startLiveDuration: liveTime=$liveTime startMs=$startMs")
        durationJob = viewModelScope.launch {
            while (true) {
                val elapsed = (System.currentTimeMillis() - startMs) / 1000
                val h = elapsed / 3600
                val m = (elapsed % 3600) / 60
                val s = elapsed % 60
                _liveDuration.value = if (h > 0) {
                    String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
                } else {
                    String.format(Locale.getDefault(), "%02d:%02d", m, s)
                }
                delay(1000)
            }
        }
    }

    fun switchQuality(qn: Int) {
        if (currentRoomId > 0) {
            viewModelScope.launch {
                repository.getLivePlayInfo(currentRoomId, qn).fold(
                    onSuccess = { data ->
                        _selectedQuality.value = _qualities.value.firstOrNull { it.qn == qn }
                        data.durl?.firstOrNull()?.let { durl ->
                            _playUrl.value = durl.url
                        }
                    },
                    onFailure = { e ->
                        _error.value = e.message ?: "切换画质失败"
                    }
                )
            }
        }
    }

    fun refreshLiveStream(roomId: Long) {
        viewModelScope.launch {
            _error.value = null
            repository.getLivePlayInfo(roomId).fold(
                onSuccess = { data ->
                    data.durl?.firstOrNull()?.let { durl ->
                        _playUrl.value = durl.url
                    }
                    _refreshEvent.trySend("刷新成功")
                },
                onFailure = { e ->
                    _error.value = e.message ?: "刷新直播失败"
                }
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        heartbeatJob?.cancel()
        heartbeatJob = null
        durationJob?.cancel()
        durationJob = null
    }
}

data class LiveQualityInfo(
    val qn: Int,
    val desc: String
)

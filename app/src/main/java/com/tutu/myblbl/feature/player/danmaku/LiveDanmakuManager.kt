package com.tutu.myblbl.feature.player.danmaku

import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.model.dm.DmModel
import com.tutu.myblbl.model.live.ChatRoomWrapper
import com.tutu.myblbl.network.WbiGenerator
import com.tutu.myblbl.network.api.ApiService
import com.tutu.myblbl.network.session.NetworkSessionGateway
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient

class LiveDanmakuManager(
    private val apiService: ApiService,
    private val okHttpClient: OkHttpClient,
    private val sessionGateway: NetworkSessionGateway,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    companion object {
        private const val TAG = "LiveDanmakuManager"
        private const val RECONNECT_DELAY_MS = 3_000L
        private const val MAX_RECONNECT_ATTEMPTS = 10
        private const val WEB_LOCATION_LIVE_ROOM = "444.8"
    }

    private val scope = CoroutineScope(ioDispatcher + SupervisorJob())
    private var connectJob: Job? = null
    private var currentRoomId: Long = 0L
    private var danmakuIdCounter = 0L

    private val _danmakuFlow = MutableSharedFlow<DmModel>(extraBufferCapacity = 128)
    val danmakuFlow: SharedFlow<DmModel> = _danmakuFlow.asSharedFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    fun start(roomId: Long) {
        stop()
        currentRoomId = roomId
        connectJob = scope.launch {
            var attempt = 0
            while (attempt < MAX_RECONNECT_ATTEMPTS && scope.isActive) {
                try {
                    connectOnce(roomId)
                    // connectOnce 正常返回说明连接断开了，重置计数后重试
                    attempt = 0
                    AppLog.w(TAG, "WebSocket disconnected, reconnecting in ${RECONNECT_DELAY_MS}ms")
                    delay(RECONNECT_DELAY_MS)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    attempt++
                    AppLog.w(TAG, "connect failed (attempt $attempt/$MAX_RECONNECT_ATTEMPTS): ${e.message}")
                    if (attempt < MAX_RECONNECT_ATTEMPTS) {
                        delay(RECONNECT_DELAY_MS)
                    }
                }
            }
            AppLog.e(TAG, "reconnect exhausted for roomId=$roomId")
        }
    }

    fun stop() {
        connectJob?.cancel()
        connectJob = null
        currentRoomId = 0L
        _isConnected.value = false
    }

    fun release() {
        stop()
        scope.cancel()
    }

    private suspend fun connectOnce(roomId: Long) {
        val danmuConfig = getDanmuConfig(roomId)
        val hostList = danmuConfig.hostList.orEmpty()
        AppLog.d(TAG, "connectOnce: token=${danmuConfig.token.take(10)}... hosts=${hostList.size}")

        val uid = try {
            sessionGateway.getUserInfo()?.mid ?: 0L
        } catch (_: Exception) { 0L }

        val client = LiveDanmakuClient(okHttpClient, ioDispatcher)

        val messageJob = scope.launch {
            client.danmakuMessages.collect { message ->
                if (message.type == LiveDanmakuMessage.Type.DANMU) {
                    val dmModel = DmModel(
                        id = ++danmakuIdCounter,
                        content = message.content,
                        color = message.color,
                        mode = 1,
                        fontSize = 25,
                        progress = 0
                    )
                    _danmakuFlow.tryEmit(dmModel)
                }
            }
        }

        // 监听连接状态，断开时取消 messageJob 让 connectOnce 结束
        val stateJob = scope.launch {
            client.connectionState.collect { state ->
                _isConnected.value = state == LiveDanmakuClient.ConnectionState.CONNECTED
                if (state == LiveDanmakuClient.ConnectionState.FAILED ||
                    state == LiveDanmakuClient.ConnectionState.DISCONNECTED
                ) {
                    messageJob.cancel()
                }
            }
        }

        try {
            client.connect(roomId, danmuConfig.token, uid, hostList)
            // 等待 messageJob 结束（连接断开时会 cancel）
            messageJob.join()
        } finally {
            client.release()
            stateJob.cancel()
        }
    }

    private suspend fun getDanmuConfig(roomId: Long): ChatRoomWrapper {
        runCatching { sessionGateway.ensureWbiKeys() }
            .onFailure { AppLog.w(TAG, "ensureWbiKeys failed: ${it.message}") }

        val (imgKey, subKey) = sessionGateway.getWbiKeys()
        val unsignedParams = mapOf(
            "id" to roomId.toString(),
            "type" to "0",
            "web_location" to WEB_LOCATION_LIVE_ROOM
        )
        val signedParams = WbiGenerator.generateWbiParams(unsignedParams, imgKey, subKey)

        val wbiResponse = apiService.getLiveDanmuInfoSigned(signedParams)
        if (wbiResponse.code == 0 && wbiResponse.data != null) {
            val data = wbiResponse.data
            if (data.token.isNotBlank()) return data
        }
        AppLog.w(TAG, "getDanmuConfig: WBI call failed code=${wbiResponse.code}, trying unsigned")

        val response = apiService.getLiveChatRoomUrl(roomId)
        if (response.code == 0 && response.data != null) {
            val data = response.data
            if (data.token.isNotBlank()) return data
        }
        AppLog.e(TAG, "getDanmuConfig: unsigned call also failed code=${response.code} msg=${response.message}")

        throw IllegalStateException("获取弹幕服务器配置失败")
    }
}

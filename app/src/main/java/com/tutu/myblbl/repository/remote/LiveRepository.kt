package com.tutu.myblbl.repository.remote

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.tutu.myblbl.model.live.LiveDUrlModel
import com.tutu.myblbl.model.live.LivePlayUrlDataModel
import com.tutu.myblbl.model.live.LiveQualityModel
import com.tutu.myblbl.model.live.LiveRoomItem
import com.tutu.myblbl.model.live.LiveAreaCategoryParent
import com.tutu.myblbl.model.live.LiveListWrapper
import com.tutu.myblbl.network.api.ApiService
import com.tutu.myblbl.network.session.NetworkSessionGateway
import com.tutu.myblbl.core.common.log.AppLog

data class LiveRoomPage(
    val rooms: List<LiveRoomItem> = emptyList(),
    val hasMore: Boolean = false
)

class LiveRepository(
    private val apiService: ApiService,
    private val sessionGateway: NetworkSessionGateway
) {

    companion object {
        private const val TAG = "LiveRepository"
        private const val DEFAULT_LIVE_QN = 10000
    }

    suspend fun getLivePlayInfo(roomId: Long, quality: Int = DEFAULT_LIVE_QN): Result<LivePlayUrlDataModel> {
        return runCatching {
            val roomInfo = resolveRoomInfo(roomId)
            if (roomInfo == null) {
                throw IllegalStateException("直播间信息获取失败")
            }
            if (roomInfo.liveStatus != 1) {
                throw IllegalStateException("当前直播间未开播")
            }

            val v2Response = apiService.getLiveRoomPlayInfoV2(
                mapOf(
                    "room_id" to roomInfo.realRoomId.toString(),
                    "protocol" to "0,1",
                    "format" to "0,1,2",
                    "codec" to "0,1",
                    "qn" to quality.toString(),
                    "platform" to "web",
                    "ptype" to "16"
                )
            )
            if (v2Response.code == 0 && v2Response.data != null) {
                parseV2PlayInfo(v2Response.data)?.let { playInfo ->
                    return@runCatching playInfo
                }
                AppLog.e(
                    TAG,
                    "getLivePlayInfo v2 parse failed: roomId=$roomId, realRoomId=${roomInfo.realRoomId}"
                )
            } else {
                AppLog.e(
                    TAG,
                    "getLivePlayInfo v2 failure: roomId=$roomId, realRoomId=${roomInfo.realRoomId}, code=${v2Response.code}, message=${v2Response.errorMessage}"
                )
            }

            val legacyResponse = apiService.getLivePlayInfo(roomInfo.realRoomId, quality)
            if (legacyResponse.code == 0 && legacyResponse.data != null) {
                legacyResponse.data
            } else {
                AppLog.e(
                    TAG,
                    "getLivePlayInfo legacy failure: roomId=$roomId, realRoomId=${roomInfo.realRoomId}, code=${legacyResponse.code}, message=${legacyResponse.errorMessage}"
                )
                throw IllegalStateException(legacyResponse.errorMessage.ifBlank { "无法获取直播流地址" })
            }
        }
    }

    suspend fun getRecommendLive(@Suppress("UNUSED_PARAMETER") page: Int, pageSize: Int): Result<List<LiveRoomItem>> {
        return runCatching {
            val response = apiService.getLiveHomeList()
            val items = response.data?.recommendRoomList
                ?.takeIf { it.isNotEmpty() }
                ?: response.data?.roomList.orEmpty().flatMap { it.list.orEmpty() }
            if (response.code == 0 && items.isNotEmpty()) {
                items.take(pageSize)
            } else {
                throw IllegalStateException(response.message)
            }
        }
    }

    suspend fun getLiveRecommend(): Result<LiveListWrapper> {
        return runCatching {
            val response = apiService.getLiveHomeList()
            if (response.code == 0 && response.data != null) {
                response.data
            } else {
                throw IllegalStateException(response.message)
            }
        }
    }

    suspend fun getAreaLive(
        parentAreaId: Long,
        areaId: Long,
        page: Int
    ): Result<LiveRoomPage> {
        return runCatching {
            val response = apiService.getAreaRoomList(
                parentAreaId = parentAreaId,
                areaId = areaId,
                page = page,
                pageSize = 30,
                sortType = "online"
            )
            if (response.code == 0 && response.data != null) {
                val rooms = response.data
                val hasMore = rooms.size >= 30
                LiveRoomPage(rooms = rooms, hasMore = hasMore)
            } else {
                throw IllegalStateException(response.message)
            }
        }
    }

    suspend fun getLiveAreas(): Result<List<LiveAreaCategoryParent>> {
        return runCatching {
            val response = apiService.getLiveArea()
            if (response.code == 0 && response.data != null) {
                response.data
            } else {
                throw IllegalStateException(response.message)
            }
        }
    }

    private suspend fun resolveRoomInfo(roomId: Long): ResolvedRoomInfo? {
        val response = apiService.getLiveRoomPlayInfo(roomId)
        if (response.code != 0 || response.data == null) {
            AppLog.e(
                TAG,
                "resolveRoomInfo failure: roomId=$roomId, code=${response.code}, message=${response.errorMessage}"
            )
            return null
        }
        val data = response.data
        val directRoomId = data.long("room_id")
        val nestedRoomId = data.objectOrNull("room_info")?.long("room_id")
        val realRoomId = directRoomId?.takeIf { it > 0L }
            ?: nestedRoomId?.takeIf { it > 0L }
            ?: roomId
        val liveStatus = data.int("live_status")
            ?: data.objectOrNull("room_info")?.int("live_status")
            ?: 0
        return ResolvedRoomInfo(realRoomId, liveStatus)
    }

    private fun parseV2PlayInfo(data: JsonObject): LivePlayUrlDataModel? {
        val playUrl = data.objectOrNull("playurl_info")
            ?.objectOrNull("playurl")
            ?: return null
        val qualityDescription = playUrl.arrayOrNull("g_qn_desc")
            ?.mapNotNull { element ->
                element.asJsonObjectOrNull()?.let { obj ->
                    LiveQualityModel(
                        qn = obj.int("qn") ?: 0,
                        desc = obj.string("desc")
                    )
                }
            }
            .orEmpty()
        val candidates = buildStreamCandidates(playUrl.arrayOrNull("stream")).sortedWith(
            compareByDescending<LiveStreamCandidate> { it.priority }
                .thenByDescending { it.currentQn }
                .thenBy { it.index }
        )
        val urls = candidates
            .mapIndexed { index, candidate ->
                LiveDUrlModel(url = candidate.url, order = index + 1)
            }
            .distinctBy { it.url }
        val currentQn = candidates.firstOrNull()?.currentQn
            ?: qualityDescription.maxByOrNull { it.qn }?.qn
            ?: 0
        return if (urls.isEmpty()) {
            null
        } else {
            LivePlayUrlDataModel(
                currentQuality = currentQn,
                currentQn = currentQn,
                qualityDescription = qualityDescription,
                durl = urls
            )
        }
    }

    private fun buildStreamCandidates(streams: JsonArray?): List<LiveStreamCandidate> {
        if (streams == null) {
            return emptyList()
        }
        val candidates = mutableListOf<LiveStreamCandidate>()
        streams.forEachIndexed streamLoop@{ streamIndex, streamElement ->
            val stream = streamElement.asJsonObjectOrNull() ?: return@streamLoop
            val protocolName = stream.string("protocol_name")
            stream.arrayOrNull("format").orEmpty().forEachIndexed formatLoop@{ formatIndex, formatElement ->
                val format = formatElement.asJsonObjectOrNull() ?: return@formatLoop
                val formatName = format.string("format_name")
                format.arrayOrNull("codec").orEmpty().forEachIndexed codecLoop@{ codecIndex, codecElement ->
                    val codec = codecElement.asJsonObjectOrNull() ?: return@codecLoop
                    val codecName = codec.string("codec_name")
                    val baseUrl = codec.string("base_url")
                    if (baseUrl.isBlank()) {
                        return@codecLoop
                    }
                    val currentQn = codec.int("current_qn") ?: 0
                    codec.arrayOrNull("url_info").orEmpty().forEachIndexed urlLoop@{ urlIndex, urlElement ->
                        val urlInfo = urlElement.asJsonObjectOrNull() ?: return@urlLoop
                        val host = urlInfo.string("host")
                        val extra = urlInfo.string("extra")
                        val url = buildLiveUrl(host, baseUrl, extra)
                        if (url.isBlank()) {
                            return@urlLoop
                        }
                        candidates += LiveStreamCandidate(
                            url = url,
                            currentQn = currentQn,
                            priority = streamPriority(protocolName, formatName, codecName),
                            index = (((streamIndex * 10) + formatIndex) * 10 + codecIndex) * 10 + urlIndex
                        )
                    }
                }
            }
        }
        return candidates
    }

    private fun streamPriority(protocolName: String, formatName: String, codecName: String): Int {
        var score = 0
        if (protocolName.contains("http_stream", ignoreCase = true)) {
            score += 100
        }
        if (formatName.equals("flv", ignoreCase = true)) {
            score += 40
        } else if (formatName.equals("ts", ignoreCase = true)) {
            score += 20
        } else if (formatName.equals("fmp4", ignoreCase = true)) {
            score += 10
        }
        if (codecName.equals("avc", ignoreCase = true)) {
            score += 8
        } else if (codecName.equals("hevc", ignoreCase = true)) {
            score += 4
        }
        return score
    }

    private fun buildLiveUrl(host: String, baseUrl: String, extra: String): String {
        if (host.isBlank() || baseUrl.isBlank()) {
            return ""
        }
        return host.trimEnd('/') + ensureLeadingSlash(baseUrl) + extra
    }

    private fun ensureLeadingSlash(value: String): String {
        return if (value.startsWith("/")) value else "/$value"
    }

    private fun JsonObject.objectOrNull(key: String): JsonObject? {
        val value = get(key) ?: return null
        return if (value.isJsonObject) value.asJsonObject else null
    }

    private fun JsonObject.arrayOrNull(key: String): JsonArray? {
        val value = get(key) ?: return null
        return if (value.isJsonArray) value.asJsonArray else null
    }

    private fun JsonObject.string(key: String): String {
        val value = get(key) ?: return ""
        return runCatching { value.asString }.getOrDefault("")
    }

    private fun JsonObject.int(key: String): Int? {
        val value = get(key) ?: return null
        return runCatching { value.asInt }.getOrNull()
    }

    private fun JsonObject.long(key: String): Long? {
        val value = get(key) ?: return null
        return runCatching { value.asLong }.getOrNull()
    }

    private fun JsonArray?.orEmpty(): List<JsonElement> {
        return this?.toList().orEmpty()
    }

    private fun JsonElement.asJsonObjectOrNull(): JsonObject? {
        return if (isJsonObject) asJsonObject else null
    }

    private data class ResolvedRoomInfo(
        val realRoomId: Long,
        val liveStatus: Int
    )

    private data class LiveStreamCandidate(
        val url: String,
        val currentQn: Int,
        val priority: Int,
        val index: Int
    )

    private fun buildWbiParams(params: Map<String, String>): Map<String, String> {
        val (imgKey, subKey) = sessionGateway.getWbiKeys()
        return com.tutu.myblbl.network.WbiGenerator.generateWbiParams(params, imgKey, subKey)
    }
}

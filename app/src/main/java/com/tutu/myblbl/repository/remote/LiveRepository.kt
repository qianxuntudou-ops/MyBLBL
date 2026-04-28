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
import com.tutu.myblbl.network.WbiGenerator
import com.tutu.myblbl.core.common.log.AppLog
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

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
        private const val DEFAULT_LIVE_PARENT_AREA_ID = 1L
        private const val DEFAULT_LIVE_AREA_ID = 1013L
        private const val WEB_LOCATION_LIVE_HOME = "444.7"
        private const val WEB_LOCATION_LIVE_ROOM = "444.8"
        private const val LIVE_WEB_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36 Edg/147.0.0.0"
    }

    private var cachedIpInfo: Pair<String, String>? = null
    private val liveDeviceId = "AUTO" + UUID.randomUUID().toString().filter { it.isDigit() }.padEnd(16, '0').take(16)
    private val liveDeviceUuid = UUID.randomUUID().toString()
    private var lastLiveHeartbeat: LiveHeartbeatState? = null

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
                buildWbiParams(
                    mapOf(
                        "room_id" to roomInfo.realRoomId.toString(),
                        "protocol" to "0,1",
                        "format" to "0,1,2",
                        "codec" to "0,1,2",
                        "qn" to quality.toString(),
                        "platform" to "web",
                        "ptype" to "8",
                        "dolby" to "5",
                        "panorama" to "1",
                        "hdr_type" to "0,1,6",
                        "req_reason" to "0",
                        "supported_drms" to "0,1,2,3",
                        "special_scenario" to "2",
                        "web_location" to WEB_LOCATION_LIVE_HOME
                    )
                )
            )
            if (v2Response.code == 0 && v2Response.data != null) {
                val liveTime = v2Response.data.long("live_time")?.toString()
                    ?: roomInfo.liveTime
                parseV2PlayInfo(v2Response.data, liveTime, roomInfo.roomTitle, roomInfo.anchorName, quality)?.let { playInfo ->
                    return@runCatching playInfo
                }
            }
            throw IllegalStateException(v2Response.errorMessage.ifBlank { "无法获取直播流地址" })
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
            val params = buildWbiParams(
                mapOf(
                    "platform" to "web",
                    "parent_area_id" to parentAreaId.toString(),
                    "area_id" to areaId.toString(),
                    "page" to page.toString()
                )
            )
            val response = apiService.getLiveCategoryDetailListSigned(params)
            if (response.code == 0 && response.data != null) {
                val rooms = response.data.list.orEmpty()
                val hasMore = response.data.hasMore != 0
                LiveRoomPage(rooms = rooms, hasMore = hasMore)
            } else {
                throw IllegalStateException(response.message)
            }
        }
    }

    suspend fun getLiveAreas(): Result<List<LiveAreaCategoryParent>> {
        return runCatching {
            val response = apiService.getWebAreaList()
            if (response.code == 0 && response.data?.data != null) {
                response.data.data
            } else {
                throw IllegalStateException(response.message)
            }
        }
    }

    suspend fun getIpInfo(): Result<JsonObject> {
        return runCatching {
            val response = apiService.getLiveIpInfo()
            if (response.code == 0 && response.data != null) {
                val data = response.data
                val province = data.string("province")
                val isp = data.string("isp")
                if (province.isNotBlank() && isp.isNotBlank()) {
                    cachedIpInfo = province to isp
                    AppLog.d(TAG, "getIpInfo: province=$province, isp=$isp")
                }
                data
            } else {
                throw IllegalStateException(response.errorMessage.ifBlank { "获取IP信息失败" })
            }
        }
    }

    suspend fun reportRoomEntry(roomId: Long): Result<Unit> {
        return runCatching {
            val csrf = sessionGateway.getCsrfToken()
            if (csrf.isBlank()) return@runCatching
            val response = apiService.liveRoomEntryAction(
                queryParams = buildWbiParams(
                    mapOf(
                        "csrf" to csrf,
                        "web_location" to WEB_LOCATION_LIVE_ROOM
                    )
                ),
                roomId = roomId.toString(),
                platform = "pc"
            )
            AppLog.d(TAG, "reportRoomEntry: roomId=$roomId, code=${response.code}")
        }
    }

    suspend fun getHeartbeatKey(roomId: Long): Result<JsonObject> {
        return runCatching {
            val roomInfo = resolveRoomInfo(roomId)
            val realRoomId = roomInfo?.realRoomId ?: roomId
            val ruid = roomInfo?.ruid ?: 0L
            val heartbeatRoomInfo = roomInfo ?: ResolvedRoomInfo(realRoomId, 0, ruid = ruid)
            val response = apiService.getLiveHeartbeatKey(buildLiveHeartbeatInitParams(heartbeatRoomInfo))
            if (response.code == 0 && response.data != null) {
                updateLiveHeartbeatState(heartbeatRoomInfo, response.data, sequence = 0)
                response.data
            } else {
                throw IllegalStateException(response.errorMessage.ifBlank { "获取心跳密钥失败" })
            }
        }
    }

    suspend fun getUserRoomInfo(roomId: Long): Result<JsonObject> {
        return runCatching {
            val response = apiService.getLiveInfoByUser(roomId)
            if (response.code == 0 && response.data != null) {
                response.data
            } else {
                throw IllegalStateException(response.errorMessage.ifBlank { "获取房间用户状态失败" })
            }
        }
    }

    suspend fun getDanmuHistory(roomId: Long): Result<JsonObject> {
        return runCatching {
            val response = apiService.getLiveDanmuHistory(roomId)
            if (response.code == 0 && response.data != null) {
                response.data
            } else {
                throw IllegalStateException(response.errorMessage.ifBlank { "获取历史弹幕失败" })
            }
        }
    }

    suspend fun sendLiveHeartbeat(roomId: Long): Result<Unit> {
        return runCatching {
            val state = lastLiveHeartbeat
            if (state == null || state.realRoomId != roomId && state.shortOrRealRoomId != roomId) {
                getHeartbeatKey(roomId).getOrThrow()
                return@runCatching
            }
            val sequence = state.sequence + 1
            val nowMs = System.currentTimeMillis()
            val response = apiService.sendLiveHeartbeatX(buildLiveHeartbeatXParams(state, sequence, nowMs))
            if (response.code == 0 && response.data != null) {
                val signature = buildLiveHeartbeatSignature(state, sequence, nowMs)
                updateLiveHeartbeatState(
                    roomInfo = state.toResolvedRoomInfo(),
                    data = response.data,
                    sequence = sequence,
                    lastSignature = signature,
                    lastReportTsMs = nowMs
                )
            } else {
                throw IllegalStateException(response.errorMessage.ifBlank { "直播心跳失败" })
            }
            AppLog.d(TAG, "sendLiveHeartbeatX: roomId=$roomId, sequence=$sequence")
        }
    }

    private suspend fun resolveRoomInfo(roomId: Long): ResolvedRoomInfo? {
        var result: ResolvedRoomInfo? = null
        runCatching { apiService.getLiveRoomInfo(roomId) }
            .onSuccess { response ->
                if (response.code == 0 && response.data != null) {
                    val data = response.data
                    val realRoomId = data.long("room_id")?.takeIf { it > 0L } ?: roomId
                    val ruid = data.long("uid") ?: 0L
                    val liveStatus = data.int("live_status") ?: 0
                    val liveTime = data.string("live_time").takeIf { it.isNotBlank() }
                    val roomTitle = data.string("title").takeIf { it.isNotBlank() }
                    val anchorName = data.string("uname").takeIf { it.isNotBlank() }
                    val parentAreaId = data.long("parent_area_id") ?: DEFAULT_LIVE_PARENT_AREA_ID
                    val areaId = data.long("area_id") ?: DEFAULT_LIVE_AREA_ID
                    AppLog.d(
                        TAG,
                        "resolveRoomInfo room/get_info: roomId=$roomId, realRoomId=$realRoomId, parentAreaId=$parentAreaId, areaId=$areaId"
                    )
                    if (anchorName != null) {
                        return ResolvedRoomInfo(
                            realRoomId = realRoomId,
                            liveStatus = liveStatus,
                            liveTime = liveTime,
                            roomTitle = roomTitle,
                            anchorName = anchorName,
                            ruid = ruid,
                            parentAreaId = parentAreaId,
                            areaId = areaId,
                            shortOrRealRoomId = roomId
                        )
                    }
                    result = ResolvedRoomInfo(
                        realRoomId = realRoomId,
                        liveStatus = liveStatus,
                        liveTime = liveTime,
                        roomTitle = roomTitle,
                        anchorName = null,
                        ruid = ruid,
                        parentAreaId = parentAreaId,
                        areaId = areaId,
                        shortOrRealRoomId = roomId
                    )
                }
            }
            .onFailure { AppLog.w(TAG, "resolveRoomInfo getLiveRoomInfo failed: ${it.message}") }

        val ruid = result?.ruid ?: 0L
        if (ruid > 0 && result?.anchorName.isNullOrBlank()) {
            runCatching {
                val wbiParams = buildWbiParams(mapOf("mid" to ruid.toString()))
                apiService.getUserSpace(wbiParams)
            }
                .onSuccess { response ->
                    if (response.code == 0 && response.data != null && result != null) {
                        val name = response.data.name.takeIf { it.isNotBlank() }
                        if (name != null) {
                            result = result!!.copy(anchorName = name)
                        }
                    }
                }
                .onFailure { AppLog.w(TAG, "resolveRoomInfo getUserSpace failed: ${it.message}") }
        }

        return result
    }

    private fun parseV2PlayInfo(
        data: JsonObject,
        liveTime: String?,
        roomTitle: String?,
        anchorName: String?,
        preferredQuality: Int
    ): LivePlayUrlDataModel? {
        val playUrl = data.objectOrNull("playurl_info")
            ?.objectOrNull("playurl")
            ?: return null
        val acceptQn = extractAcceptQn(playUrl.arrayOrNull("stream"))
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
            .filter { acceptQn.isEmpty() || it.qn in acceptQn }
        val candidates = buildStreamCandidates(playUrl.arrayOrNull("stream")).sortedWith(
            compareByDescending<LiveStreamCandidate> { if (it.currentQn == preferredQuality) 1 else 0 }
                .thenByDescending { it.priority }
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
                durl = urls,
                liveTime = liveTime,
                roomTitle = roomTitle,
                anchorName = anchorName
            )
        }
    }

    private fun buildStreamCandidates(streams: JsonArray?): List<LiveStreamCandidate> {
        if (streams == null) {
            return emptyList()
        }
        val candidates = mutableListOf<LiveStreamCandidate>()
        val flvHevcFallback = mutableListOf<LiveStreamCandidate>()
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
                    val isFlvHevc = formatName.equals("flv", ignoreCase = true) &&
                        codecName.equals("hevc", ignoreCase = true)
                    codec.arrayOrNull("url_info").orEmpty().forEachIndexed urlLoop@{ urlIndex, urlElement ->
                        val urlInfo = urlElement.asJsonObjectOrNull() ?: return@urlLoop
                        val host = urlInfo.string("host")
                        val extra = urlInfo.string("extra")
                        val url = buildLiveUrl(host, baseUrl, extra)
                        if (url.isBlank()) {
                            return@urlLoop
                        }
                        val candidate = LiveStreamCandidate(
                            url = url,
                            currentQn = currentQn,
                            priority = streamPriority(protocolName, formatName, codecName, extra),
                            index = (((streamIndex * 10) + formatIndex) * 10 + codecIndex) * 10 + urlIndex
                        )
                        if (isFlvHevc) {
                            flvHevcFallback += candidate
                        } else {
                            candidates += candidate
                        }
                    }
                }
            }
        }
        if (candidates.isEmpty()) {
            return flvHevcFallback
        }
        return candidates
    }

    private fun streamPriority(protocolName: String, formatName: String, codecName: String, urlExtra: String): Int {
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
        cachedIpInfo?.let { (userProvince, userIsp) ->
            val extraParams = parseExtraParams(urlExtra)
            val cdnIsp = extraParams["isp"]
            val cdnProvince = extraParams["pv"]
            if (cdnIsp.equals(userIsp, ignoreCase = true)) {
                score += if (cdnProvince.equals(userProvince, ignoreCase = true)) {
                    200
                } else {
                    100
                }
            }
        }
        return score
    }

    private fun extractAcceptQn(streams: JsonArray?): Set<Int> {
        val codec = streams?.firstOrNull()
            ?.asJsonObjectOrNull()
            ?.arrayOrNull("format")?.firstOrNull()
            ?.asJsonObjectOrNull()
            ?.arrayOrNull("codec")?.firstOrNull()
            ?.asJsonObjectOrNull()
            ?: return emptySet()
        return codec.arrayOrNull("accept_qn")
            ?.mapNotNull { runCatching { it.asInt }.getOrNull() }
            ?.toSet()
            .orEmpty()
    }

    private suspend fun buildWbiParams(params: Map<String, String>): Map<String, String> {
        if (sessionGateway.areWbiKeysStale()) {
            runCatching { sessionGateway.ensureWbiKeys() }
                .onFailure { AppLog.w(TAG, "buildWbiParams ensureWbiKeys failed: ${it.message}") }
        }
        val (imgKey, subKey) = sessionGateway.getWbiKeys()
        return WbiGenerator.generateWbiParams(params, imgKey, subKey)
    }

    private suspend fun buildLiveHeartbeatInitParams(roomInfo: ResolvedRoomInfo): Map<String, String> {
        val csrf = sessionGateway.getCsrfToken()
        val nowMs = System.currentTimeMillis()
        val params = linkedMapOf(
            "id" to liveHeartbeatId(roomInfo.parentAreaId, roomInfo.areaId, 0, roomInfo.realRoomId),
            "device" to """["$liveDeviceId","$liveDeviceUuid"]""",
            "ruid" to roomInfo.ruid.toString(),
            "ts" to nowMs.toString(),
            "is_patch" to "1",
            "ua" to LIVE_WEB_UA,
            "web_location" to WEB_LOCATION_LIVE_ROOM
        )
        csrf.takeIf { it.isNotBlank() }?.let { params["csrf"] = it }
        lastLiveHeartbeat?.takeIf { it.lastSignature.isNotBlank() }?.let { state ->
            params["heart_beat"] = state.toPatchHeartbeat()
        }
        return buildWbiParams(params)
    }

    private suspend fun buildLiveHeartbeatXParams(
        state: LiveHeartbeatState,
        sequence: Int,
        nowMs: Long
    ): Map<String, String> {
        val csrf = sessionGateway.getCsrfToken()
        val signature = buildLiveHeartbeatSignature(state, sequence, nowMs)
        val params = linkedMapOf(
            "s" to signature,
            "id" to liveHeartbeatId(state.parentAreaId, state.areaId, sequence, state.realRoomId),
            "device" to """["$liveDeviceId","$liveDeviceUuid"]""",
            "ruid" to state.ruid.toString(),
            "ets" to state.timestamp.toString(),
            "benchmark" to state.secretKey,
            "time" to state.intervalSec.toString(),
            "ts" to nowMs.toString(),
            "ua" to LIVE_WEB_UA,
            "trackid" to state.trackId,
            "web_location" to WEB_LOCATION_LIVE_ROOM
        )
        csrf.takeIf { it.isNotBlank() }?.let { params["csrf"] = it }
        return buildWbiParams(params)
    }

    private fun updateLiveHeartbeatState(
        roomInfo: ResolvedRoomInfo,
        data: JsonObject,
        sequence: Int,
        lastSignature: String = "",
        lastReportTsMs: Long = 0L
    ) {
        val previous = lastLiveHeartbeat
        lastLiveHeartbeat = LiveHeartbeatState(
            realRoomId = roomInfo.realRoomId,
            shortOrRealRoomId = roomInfo.shortOrRealRoomId,
            ruid = roomInfo.ruid,
            parentAreaId = roomInfo.parentAreaId,
            areaId = roomInfo.areaId,
            sequence = sequence,
            timestamp = data.long("timestamp") ?: (System.currentTimeMillis() / 1000L),
            intervalSec = data.int("heartbeat_interval") ?: 60,
            secretKey = data.string("secret_key"),
            secretRule = data.arrayOrNull("secret_rule")
                ?.mapNotNull { runCatching { it.asInt }.getOrNull() }
                ?.takeIf { it.isNotEmpty() }
                ?: previous?.secretRule
                ?: listOf(2, 5, 1, 4),
            lastSignature = lastSignature,
            lastReportTsMs = lastReportTsMs,
            trackId = previous?.takeIf { it.realRoomId == roomInfo.realRoomId }?.trackId ?: "-99998",
            updatedAtMs = System.currentTimeMillis()
        )
    }

    private fun buildLiveHeartbeatSignature(
        state: LiveHeartbeatState,
        sequence: Int,
        nowMs: Long
    ): String {
        var payload = buildLiveHeartbeatSignaturePayload(state, sequence, nowMs)
        state.secretRule.forEach { rule ->
            payload = hmacHex(payload, state.secretKey, liveHeartbeatDigestName(rule))
        }
        return payload
    }

    private fun buildLiveHeartbeatSignaturePayload(
        state: LiveHeartbeatState,
        sequence: Int,
        nowMs: Long
    ): String {
        return buildString {
            append("{\"platform\":\"web\"")
            append(",\"parent_id\":").append(state.parentAreaId)
            append(",\"area_id\":").append(state.areaId)
            append(",\"seq_id\":").append(sequence)
            append(",\"room_id\":").append(state.realRoomId)
            append(",\"buvid\":\"").append(jsonEscape(liveDeviceId)).append("\"")
            append(",\"uuid\":\"").append(jsonEscape(liveDeviceUuid)).append("\"")
            append(",\"ets\":").append(state.timestamp)
            append(",\"time\":").append(state.intervalSec)
            append(",\"ts\":").append(nowMs)
            append("}")
        }
    }

    private fun hmacHex(payload: String, key: String, algorithm: String): String {
        val mac = Mac.getInstance("Hmac$algorithm")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "Hmac$algorithm"))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8)).joinToString("") { byte ->
            "%02x".format(byte)
        }
    }

    private fun liveHeartbeatDigestName(rule: Int): String {
        return when (rule) {
            0 -> "MD5"
            1 -> "SHA1"
            2 -> "SHA256"
            3 -> "SHA224"
            4 -> "SHA512"
            5 -> "SHA384"
            else -> "SHA256"
        }
    }

    private fun liveHeartbeatId(parentAreaId: Long, areaId: Long, sequence: Int, roomId: Long): String {
        return "[$parentAreaId,$areaId,$sequence,$roomId]"
    }

    private fun LiveHeartbeatState.toPatchHeartbeat(): String {
        val id = liveHeartbeatId(parentAreaId, areaId, sequence, realRoomId)
        val device = """["$liveDeviceId","$liveDeviceUuid"]"""
        val ts = lastReportTsMs.takeIf { it > 0L } ?: System.currentTimeMillis()
        return buildString {
            append("[{\"s\":\"").append(jsonEscape(lastSignature)).append("\"")
            append(",\"id\":\"").append(jsonEscape(id)).append("\"")
            append(",\"device\":\"").append(jsonEscape(device)).append("\"")
            append(",\"ruid\":").append(ruid)
            append(",\"ets\":").append(timestamp)
            append(",\"benchmark\":\"").append(jsonEscape(secretKey)).append("\"")
            append(",\"time\":").append(elapsedSeconds())
            append(",\"ts\":").append(ts)
            append(",\"ua\":\"").append(jsonEscape(LIVE_WEB_UA)).append("\"")
            append(",\"trackid\":\"").append(jsonEscape(trackId)).append("\"")
            append("}]")
        }
    }

    private fun LiveHeartbeatState.toResolvedRoomInfo(): ResolvedRoomInfo {
        return ResolvedRoomInfo(
            realRoomId = realRoomId,
            liveStatus = 1,
            ruid = ruid,
            parentAreaId = parentAreaId,
            areaId = areaId,
            shortOrRealRoomId = shortOrRealRoomId
        )
    }

    private fun jsonEscape(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\b", "\\b")
            .replace("\u000C", "\\f")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private fun parseExtraParams(extra: String): Map<String, String> {
        if (extra.isBlank()) return emptyMap()
        val query = extra.substringAfter('?', "")
        if (query.isBlank()) return emptyMap()
        return query.split("&").mapNotNull { pair ->
            val parts = pair.split("=", limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else null
        }.toMap()
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
        val liveStatus: Int,
        val liveTime: String? = null,
        val roomTitle: String? = null,
        val anchorName: String? = null,
        val ruid: Long = 0L,
        val parentAreaId: Long = DEFAULT_LIVE_PARENT_AREA_ID,
        val areaId: Long = DEFAULT_LIVE_AREA_ID,
        val shortOrRealRoomId: Long = realRoomId
    )

    private data class LiveHeartbeatState(
        val realRoomId: Long,
        val shortOrRealRoomId: Long,
        val ruid: Long,
        val parentAreaId: Long,
        val areaId: Long,
        val sequence: Int,
        val timestamp: Long,
        val intervalSec: Int,
        val secretKey: String,
        val secretRule: List<Int>,
        val lastSignature: String,
        val lastReportTsMs: Long,
        val trackId: String,
        val updatedAtMs: Long
    ) {
        fun elapsedSeconds(): Long {
            return ((System.currentTimeMillis() - updatedAtMs) / 1000L).coerceAtLeast(0L)
        }
    }

    private data class LiveStreamCandidate(
        val url: String,
        val currentQn: Int,
        val priority: Int,
        val index: Int
    )

}

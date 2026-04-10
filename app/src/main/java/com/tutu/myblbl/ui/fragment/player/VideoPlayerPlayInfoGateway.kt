package com.tutu.myblbl.ui.fragment.player

import com.tutu.myblbl.model.player.PlayInfoModel
import com.tutu.myblbl.model.player.VideoSnapshotData
import com.tutu.myblbl.network.NetworkManager
import com.tutu.myblbl.network.WbiGenerator
import com.tutu.myblbl.network.api.ApiService
import com.tutu.myblbl.network.response.Base2Response
import com.tutu.myblbl.network.response.PlayerInfoDataWrapper
import com.tutu.myblbl.utils.AppLog
import okhttp3.Request

/**
 * Centralizes play-info request fallbacks so the ViewModel only decides when to request playback.
 */
class VideoPlayerPlayInfoGateway(
    private val apiService: ApiService,
    private val logTag: String
) {

    data class PlayInfoResult(
        val code: Int,
        val message: String,
        val data: PlayInfoModel?
    ) {
        val isSuccess: Boolean
            get() = code == 0 && data != null
    }

    suspend fun requestPlayInfo(
        aid: Long?,
        bvid: String?,
        cid: Long,
        epId: Long?,
        qualityId: Int,
        fnval: Int,
        fourk: Int
    ): PlayInfoResult? {
        if (epId != null && epId > 0L) {
            return requestPgcPlayInfo(
                aid = aid,
                bvid = bvid,
                cid = cid,
                epId = epId,
                qualityId = qualityId,
                fnval = fnval,
                fourk = fourk
            )
        }

        val resolvedBvid = bvid?.takeIf { it.isNotBlank() } ?: return null
        ensureWbiKeys()

        if (hasWbiKeys()) {
            val params = mutableMapOf(
                "bvid" to resolvedBvid,
                "cid" to cid.toString(),
                "qn" to qualityId.toString(),
                "fnver" to "0",
                "fnval" to fnval.toString(),
                "fourk" to fourk.toString()
            )
            aid?.takeIf { it > 0L }?.let { params["avid"] = it.toString() }
            NetworkManager.getCookieManager().getCookieValue("SESSDATA")
                ?.takeIf { it.isNotBlank() }
                ?.let { params[""] = it }

            val wbiResponse = runCatching {
                apiService.getVideoPlayInfoWbi(buildWbiParams(params))
            }.onFailure { throwable ->
                AppLog.e(logTag, "requestPlayInfo wbi exception: ${throwable.message}", throwable)
            }.getOrNull()
            if (wbiResponse != null) {
                AppLog.d(
                    logTag,
                    "requestPlayInfo wbi response: code=${wbiResponse.code}, success=${wbiResponse.isSuccess}"
                )
                if (wbiResponse.isSuccess && wbiResponse.data != null) {
                    return PlayInfoResult(
                        code = wbiResponse.code,
                        message = wbiResponse.message,
                        data = wbiResponse.data
                    )
                }
            }
        }

        val normalResponse = runCatching {
            apiService.getVideoPlayInfo(
                avid = aid,
                bvid = resolvedBvid,
                cid = cid,
                qn = qualityId,
                fnval = fnval,
                fourk = fourk,
                fnver = 0
            )
        }.onFailure { throwable ->
            AppLog.e(logTag, "requestPlayInfo normal exception: ${throwable.message}", throwable)
        }.getOrNull()
        if (normalResponse != null) {
            AppLog.d(
                logTag,
                "requestPlayInfo normal response: code=${normalResponse.code}, success=${normalResponse.isSuccess}"
            )
        }
        return normalResponse?.let {
            PlayInfoResult(
                code = it.code,
                message = it.message,
                data = it.data
            )
        }
    }

    suspend fun requestPlayerInfoData(
        aid: Long?,
        bvid: String?,
        cid: Long
    ): PlayerInfoDataWrapper? {
        ensureWbiKeys()
        if (hasWbiKeys()) {
            val params = mutableMapOf("cid" to cid.toString())
            aid?.let { params["avid"] = it.toString() }
            bvid?.takeIf { it.isNotBlank() }?.let { params["bvid"] = it }
            val wbiResponse = runCatching {
                apiService.getPlayerInfoWbi(buildWbiParams(params))
            }.onFailure { throwable ->
                AppLog.e(logTag, "loadPlayerInfoData wbi exception: ${throwable.message}", throwable)
            }.getOrNull()
            if (wbiResponse != null) {
                AppLog.d(
                    logTag,
                    "loadPlayerInfoData wbi response: code=${wbiResponse.code}, success=${wbiResponse.isSuccess}"
                )
                wbiResponse.data?.takeIf { wbiResponse.isSuccess }?.let { return it }
            }
        }

        val normalResponse = runCatching {
            apiService.getPlayerInfo(
                aid = aid,
                bvid = bvid,
                cid = cid
            )
        }.onFailure { throwable ->
            AppLog.e(logTag, "loadPlayerInfoData normal exception: ${throwable.message}", throwable)
        }.getOrNull()
        if (normalResponse != null) {
            AppLog.d(
                logTag,
                "loadPlayerInfoData normal response: code=${normalResponse.code}, success=${normalResponse.isSuccess}"
            )
        }
        return normalResponse?.data?.takeIf { normalResponse.isSuccess }
    }

    suspend fun requestVideoSnapshot(
        aid: Long?,
        bvid: String?,
        cid: Long
    ): VideoSnapshotData? {
        if ((aid == null || aid <= 0L) && bvid.isNullOrBlank()) {
            return null
        }
        if (cid <= 0L) {
            return null
        }

        val response = runCatching {
            apiService.getVideoSnapshot(
                aid = aid,
                bvid = bvid,
                cid = cid
            )
        }.onFailure { throwable ->
            AppLog.e(logTag, "requestVideoSnapshot exception: ${throwable.message}", throwable)
        }.getOrNull()

        if (response != null) {
            AppLog.d(
                logTag,
                "requestVideoSnapshot response: code=${response.code}, success=${response.isSuccess}, images=${response.data?.images?.size ?: 0}, indexSize=${response.data?.index?.size ?: 0}, cid=$cid"
            )
        }

        return response?.data?.takeIf { response.isSuccess }
    }

    suspend fun requestDanmakuSegmentBytes(
        cid: Long,
        aid: Long,
        segmentIndex: Int
    ): ByteArray? {
        val normalBytes = runCatching {
            apiService.getVideoComment(
                oid = cid,
                pid = aid,
                segmentIndex = segmentIndex
            ).use { responseBody ->
                responseBody.bytes()
            }
        }.getOrNull()
        if (normalBytes != null) {
            return normalBytes
        }

        if (!hasWbiKeys()) {
            return null
        }

        val params = buildWbiParams(
            mapOf(
                "type" to "1",
                "oid" to cid.toString(),
                "pid" to aid.toString(),
                "segment_index" to segmentIndex.toString()
            )
        )
        return runCatching {
            apiService.getVideoCommentWbi(params).use { responseBody ->
                responseBody.bytes()
            }
        }.getOrNull()
    }

    suspend fun requestDanmakuViewBytes(
        cid: Long,
        aid: Long
    ): ByteArray? {
        return runCatching {
            apiService.getVideoCommentView(
                oid = cid,
                pid = aid
            ).use { responseBody ->
                responseBody.bytes()
            }
        }.getOrNull()
    }

    suspend fun requestAbsoluteBytes(url: String): ByteArray? {
        val normalizedUrl = when {
            url.startsWith("http://") -> "https://${url.removePrefix("http://")}"
            url.startsWith("//") -> "https:$url"
            else -> url
        }
        return runCatching {
            val request = Request.Builder()
                .url(normalizedUrl)
                .build()
            NetworkManager.getOkHttpClient().newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return null
                }
                response.body?.bytes()
            }
        }.getOrNull()
    }

    fun hasWbiKeys(): Boolean {
        val (imgKey, subKey) = NetworkManager.getWbiKeys()
        return imgKey.isNotBlank() && subKey.isNotBlank()
    }

    suspend fun warmupWbiKeys() {
        ensureWbiKeys()
    }

    private suspend fun requestPgcPlayInfo(
        aid: Long?,
        bvid: String?,
        cid: Long,
        epId: Long,
        qualityId: Int,
        fnval: Int,
        fourk: Int
    ): PlayInfoResult? {
        NetworkManager.prewarmWebSession()

        val attempts = listOf(
            "primary-drm2" to buildPgcPlayParams(
                aid = aid,
                bvid = bvid,
                cid = cid,
                epId = epId,
                qualityId = qualityId,
                fnval = fnval,
                fourk = fourk,
                includeCid = true,
                includeVideoIds = true,
                drmTechType = 2
            ),
            "primary-drm0" to buildPgcPlayParams(
                aid = aid,
                bvid = bvid,
                cid = cid,
                epId = epId,
                qualityId = qualityId,
                fnval = fnval,
                fourk = fourk,
                includeCid = true,
                includeVideoIds = true,
                drmTechType = 0
            ),
            "ep-only-drm2" to buildPgcPlayParams(
                aid = aid,
                bvid = bvid,
                cid = cid,
                epId = epId,
                qualityId = qualityId,
                fnval = fnval,
                fourk = fourk,
                includeCid = false,
                includeVideoIds = false,
                drmTechType = 2
            ),
            "ep-only-drm0" to buildPgcPlayParams(
                aid = aid,
                bvid = bvid,
                cid = cid,
                epId = epId,
                qualityId = qualityId,
                fnval = fnval,
                fourk = fourk,
                includeCid = false,
                includeVideoIds = false,
                drmTechType = 0
            )
        )

        var lastResponse: Base2Response<PlayInfoModel>? = null
        attempts.forEachIndexed { index, (label, params) ->
            if (index > 0) {
                NetworkManager.prewarmWebSession(forceUaRefresh = true)
            }
            val response = runCatching {
                apiService.getVideoPlayPgcInfo(params)
            }.onFailure { throwable ->
                AppLog.e(
                    logTag,
                    "requestPgcPlayInfo[$label] exception: ${throwable.message}",
                    throwable
                )
            }.getOrNull()
            lastResponse = response
            if (response != null) {
                AppLog.d(
                    logTag,
                    "requestPgcPlayInfo[$label] response: code=${response.code}, success=${response.isSuccess}, message=${response.message}, epId=$epId, cid=$cid, hasResult=${response.result != null}"
                )
                if (response.isSuccess && response.result != null) {
                    return response.toPlayInfoResult()
                }
                if (!shouldRetryPgcPlayInfo(response)) {
                    return response.toPlayInfoResult()
                }
            }
        }

        if (lastResponse != null) {
            AppLog.d(
                logTag,
                "requestPgcPlayInfo final failure: code=${lastResponse?.code}, message=${lastResponse?.message}, epId=$epId, cid=$cid"
            )
        }
        return lastResponse?.toPlayInfoResult()
    }

    private fun buildPgcPlayParams(
        aid: Long?,
        bvid: String?,
        cid: Long,
        epId: Long,
        qualityId: Int,
        fnval: Int,
        fourk: Int,
        includeCid: Boolean,
        includeVideoIds: Boolean,
        drmTechType: Int
    ): Map<String, String> {
        val params = linkedMapOf(
            "ep_id" to epId.toString(),
            "qn" to qualityId.toString(),
            "fnver" to "0",
            "fnval" to fnval.toString(),
            "fourk" to fourk.toString(),
            "from_client" to "BROWSER",
            "drm_tech_type" to drmTechType.toString()
        )
        if (includeCid && cid > 0L) {
            params["cid"] = cid.toString()
        }
        if (includeVideoIds) {
            aid?.takeIf { it > 0L }?.let { params["avid"] = it.toString() }
            bvid?.takeIf { it.isNotBlank() }?.let { params["bvid"] = it }
        }
        return params
    }

    private fun shouldRetryPgcPlayInfo(response: Base2Response<PlayInfoModel>): Boolean {
        if (response.isSuccess && response.result != null) {
            return false
        }
        val message = response.message.trim()
        return response.code != 0 ||
            response.result == null ||
            message.contains("啥都没有") ||
            message.contains("nothing", ignoreCase = true)
    }

    private suspend fun ensureWbiKeys() {
        if (hasWbiKeys()) {
            return
        }
        if (!NetworkManager.isLoggedIn()) {
            AppLog.d(logTag, "ensureWbiKeys skipped: user not logged in")
            return
        }
        val response = runCatching {
            apiService.getUserDetailInfo()
        }.onFailure { throwable ->
            AppLog.e(logTag, "ensureWbiKeys exception: ${throwable.message}", throwable)
        }.getOrNull() ?: return
        if (NetworkManager.syncUserSession(response, source = "ensureWbiKeys") != null) {
            AppLog.d(logTag, "ensureWbiKeys success")
        } else {
            AppLog.e(logTag, "ensureWbiKeys failed: code=${response.code}, message=${response.errorMessage}")
        }
    }

    private fun buildWbiParams(params: Map<String, String>): Map<String, String> {
        val (imgKey, subKey) = NetworkManager.getWbiKeys()
        return WbiGenerator.generateWbiParams(params, imgKey, subKey)
    }

    private fun Base2Response<PlayInfoModel>.toPlayInfoResult(): PlayInfoResult {
        return PlayInfoResult(
            code = code,
            message = message,
            data = result
        )
    }
}

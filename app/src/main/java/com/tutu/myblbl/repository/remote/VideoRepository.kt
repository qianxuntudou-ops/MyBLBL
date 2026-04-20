package com.tutu.myblbl.repository.remote

import com.tutu.myblbl.model.BaseResponse
import com.tutu.myblbl.model.common.CheckGiveCoinModel
import com.tutu.myblbl.model.common.GiveCoinResultModel
import com.tutu.myblbl.model.common.TripleActionResultModel
import com.tutu.myblbl.model.recommend.RecommendListDataModel
import com.tutu.myblbl.model.video.GetVideoByChannelWrapper
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.model.video.detail.VideoDetailModel
import com.tutu.myblbl.network.api.ApiService
import com.tutu.myblbl.network.WbiGenerator
import com.tutu.myblbl.network.response.BaseBaseResponse
import com.tutu.myblbl.network.security.NetworkSecurityGateway
import com.tutu.myblbl.network.session.NetworkSessionGateway
import com.tutu.myblbl.network.response.ListDataModel
import com.tutu.myblbl.core.common.log.AppLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class VideoRepository(
    private val apiService: ApiService,
    private val sessionGateway: NetworkSessionGateway,
    private val securityGateway: NetworkSecurityGateway
) {

    @Volatile
    private var watchLaterCache: List<VideoModel>? = null
    @Volatile
    private var watchLaterCacheTimeMs: Long = 0L
    private val watchLaterCacheTtlMs = 5L * 60L * 1000L
    private val watchLaterMutex = Mutex()

    private fun invalidateWatchLaterCache() {
        watchLaterCache = null
        watchLaterCacheTimeMs = 0L
    }

    private suspend fun getWatchLaterList(): List<VideoModel> = watchLaterMutex.withLock {
        val now = System.currentTimeMillis()
        val cached = watchLaterCache
        if (cached != null && now - watchLaterCacheTimeMs < watchLaterCacheTtlMs) {
            return@withLock cached
        }
        val response = sessionGateway.syncAuthState(
            apiService.getLaterWatch(),
            source = "video.getWatchLaterList"
        )
        val list = if (response.isSuccess) response.data?.list.orEmpty() else emptyList()
        watchLaterCache = list
        watchLaterCacheTimeMs = now
        return@withLock list
    }

    companion object {
        private const val TAG = "VideoRepository"
        private const val FEEDBACK_APP_ID = "100"
        private const val FEEDBACK_PLATFORM = "5"
        private const val FEEDBACK_SPMID = "333.1007.0.0"
        private const val FEEDBACK_PAGE = "1"
        private val FEEDBACK_RETRY_CODES = setOf(-352, -401)
    }

    suspend fun getRecommendList(
        freshIdx: Int,
        pageSize: Int
    ): Result<BaseResponse<RecommendListDataModel<VideoModel>>> =
        runCatching {
            apiService.getRecommendList(
                freshIdx = freshIdx.coerceAtLeast(1),
                ps = pageSize
            )
        }

    suspend fun getHotList(page: Int, pageSize: Int): Result<BaseResponse<List<VideoModel>>> =
        runCatching {
            apiService.getHotList(page, pageSize).mapListData()
        }

    suspend fun getRanking(rid: Int): Result<BaseResponse<List<VideoModel>>> =
        runCatching {
            val firstResponse = apiService.getRanking(rid)
            if (firstResponse.code == -352) {
                AppLog.e(TAG, "getRanking first attempt hit risk control: rid=$rid")
                delay(1500L)
                securityGateway.prewarmWebSession(forceUaRefresh = true)
                apiService.getRanking(rid).mapListData()
            } else {
                firstResponse.mapListData()
            }
        }

    suspend fun getVideoByChannel(
        channelId: Long,
        offset: String,
        pageSize: Int = 30
    ): Result<BaseResponse<GetVideoByChannelWrapper>> =
        runCatching {
            apiService.getVideoByChannel(
                channelId = channelId,
                offset = offset,
                pageSize = pageSize
            )
        }

    suspend fun getVideoDetail(aid: Long?, bvid: String?): Result<BaseResponse<VideoDetailModel>> =
        runCatching {
            apiService.getVideoDetail(aid, bvid)
        }

    suspend fun like(avid: Long?, bvid: String?, like: Int, csrf: String): Result<BaseResponse<Int>> =
        runCatching {
            sessionGateway.syncAuthState(
                apiService.like(avid, bvid, like, csrf),
                source = "video.like"
            )
        }

    suspend fun hasLike(avid: Long?, bvid: String?): Result<BaseResponse<Int>> =
        runCatching {
            sessionGateway.syncAuthState(
                apiService.hasLike(avid, bvid),
                source = "video.hasLike"
            )
        }

    suspend fun giveCoin(avid: Long?, bvid: String?, multiply: Int, selectLike: Int, csrf: String): Result<BaseResponse<GiveCoinResultModel>> =
        runCatching {
            sessionGateway.syncAuthState(
                apiService.giveCoin(avid, bvid, multiply, selectLike, csrf),
                source = "video.giveCoin"
            )
        }

    suspend fun hasGiveCoin(avid: Long?, bvid: String?): Result<BaseResponse<CheckGiveCoinModel>> =
        runCatching {
            sessionGateway.syncAuthState(
                apiService.hasGiveCoin(avid, bvid),
                source = "video.hasGiveCoin"
            )
        }

    suspend fun tripleAction(avid: Long?, bvid: String?, csrf: String): Result<BaseResponse<TripleActionResultModel>> =
        runCatching {
            securityGateway.prewarmWebSession()
            val firstResponse = sessionGateway.syncAuthState(
                apiService.tripleAction(avid, bvid, csrf),
                source = "video.tripleAction.first"
            )
            if (firstResponse.code == -352 || firstResponse.code == -401) {
                AppLog.e(TAG, "tripleAction first attempt hit risk control/401: aid=$avid, bvid=$bvid, code=${firstResponse.code}")
                delay(1500L)
                securityGateway.prewarmWebSession(forceUaRefresh = true)
                val freshCsrf = sessionGateway.getCsrfToken()
                sessionGateway.syncAuthState(
                    apiService.tripleAction(avid, bvid, freshCsrf),
                    source = "video.tripleAction.second"
                )
            } else {
                firstResponse
            }
        }

    suspend fun addWatchLater(aid: Long?, bvid: String?, csrf: String): Result<BaseBaseResponse> =
        runCatching {
            val result = sessionGateway.syncAuthState(
                apiService.addWatchLater(aid, bvid, csrf),
                source = "video.addWatchLater"
            )
            if (result.isSuccess) invalidateWatchLaterCache()
            result
        }

    suspend fun removeWatchLater(aid: Long?, bvid: String?, csrf: String): Result<BaseBaseResponse> =
        runCatching {
            val resolvedAid = resolveWatchLaterAid(aid, bvid)
                ?: error("缺少稍后再看视频标识")
            val result = sessionGateway.syncAuthState(
                apiService.removeWatchLater(resolvedAid, csrf),
                source = "video.removeWatchLater"
            )
            if (result.isSuccess) invalidateWatchLaterCache()
            result
        }

    suspend fun checkWatchLater(aid: Long?, bvid: String?): Result<Boolean> =
        runCatching {
            val list = getWatchLaterList()
            list.any { item ->
                matchesWatchLaterItem(item, aid, bvid)
            }
        }

    private suspend fun resolveWatchLaterAid(aid: Long?, bvid: String?): Long? {
        if ((aid ?: 0L) > 0L) {
            return aid
        }
        if (bvid.isNullOrBlank()) {
            return null
        }
        val list = getWatchLaterList()
        return list
            .firstOrNull { matchesWatchLaterItem(it, aid, bvid) }
            ?.aid
            ?.takeIf { it > 0L }
    }

    private fun matchesWatchLaterItem(item: VideoModel, aid: Long?, bvid: String?): Boolean {
        val targetAid = aid ?: 0L
        return when {
            targetAid > 0L && item.aid == targetAid -> true
            !bvid.isNullOrBlank() && item.bvid.equals(bvid, ignoreCase = true) -> true
            else -> false
        }
    }

    suspend fun dislikeFeed(video: VideoModel, reasonId: Int, csrf: String): Result<BaseBaseResponse> =
        runCatching {
            securityGateway.prewarmWebSession()
            val firstParams = buildFeedbackWbiParams()
            val firstForm = buildDislikeForm(video = video, reasonId = reasonId, csrf = csrf)
            val firstResponse = sessionGateway.syncAuthState(
                apiService.dislikeFeed(
                    params = firstParams,
                    form = firstForm
                ),
                source = "video.dislikeFeed.first"
            )
            if (firstResponse.code in FEEDBACK_RETRY_CODES) {
                AppLog.e(
                    TAG,
                    "dislikeFeed first attempt hit risk control/auth: aid=${video.aid}, bvid=${video.bvid}, code=${firstResponse.code}"
                )
                delay(1500L)
                securityGateway.prewarmWebSession(forceUaRefresh = true)
                val secondParams = buildFeedbackWbiParams()
                val secondForm = buildDislikeForm(video = video, reasonId = reasonId, csrf = csrf)
                sessionGateway.syncAuthState(
                    apiService.dislikeFeed(
                        params = secondParams,
                        form = secondForm
                    ),
                    source = "video.dislikeFeed.second"
                )
            } else {
                firstResponse
            }
        }

    private fun BaseResponse<ListDataModel<VideoModel>>.mapListData(): BaseResponse<List<VideoModel>> {
        return BaseResponse(
            code = code,
            message = message,
            msg = msg,
            data = data?.list.orEmpty()
        )
    }

    private fun buildFeedbackWbiParams(): Map<String, String> {
        val (imgKey, subKey) = sessionGateway.getWbiKeys()
        return WbiGenerator.generateWbiParams(emptyMap(), imgKey, subKey)
    }

    private fun buildDislikeForm(
        video: VideoModel,
        reasonId: Int,
        csrf: String
    ): Map<String, String> {
        return linkedMapOf(
            "app_id" to FEEDBACK_APP_ID,
            "platform" to FEEDBACK_PLATFORM,
            "from_spmid" to "",
            "spmid" to FEEDBACK_SPMID,
            "goto" to video.goto.ifBlank { "av" },
            "id" to resolveFeedbackTargetId(video).toString(),
            "mid" to (video.owner?.mid ?: 0L).toString(),
            "track_id" to video.trackId,
            "feedback_page" to FEEDBACK_PAGE,
            "reason_id" to reasonId.toString(),
            "csrf" to csrf
        )
    }

    private fun resolveFeedbackTargetId(video: VideoModel): Long {
        return when {
            video.aid > 0L -> video.aid
            video.roomId > 0L -> video.roomId
            else -> 0L
        }
    }
}

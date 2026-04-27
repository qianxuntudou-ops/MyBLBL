package com.tutu.myblbl.repository

import com.tutu.myblbl.model.BaseResponse
import com.tutu.myblbl.model.user.UserStatModel
import com.tutu.myblbl.model.user.UserSpaceInfo
import com.tutu.myblbl.model.user.UserDetailInfoModel
import com.tutu.myblbl.model.user.CheckRelationModel
import com.tutu.myblbl.model.user.GetFollowUserWrapper
import com.tutu.myblbl.model.video.HistoryListResponse
import com.tutu.myblbl.model.video.LaterWatchWrapper
import com.tutu.myblbl.model.video.UserDynamicResponse
import com.tutu.myblbl.model.video.AllDynamicResponse
import com.tutu.myblbl.network.WbiGenerator
import com.tutu.myblbl.network.api.ApiService
import com.tutu.myblbl.network.security.NetworkSecurityGateway
import com.tutu.myblbl.network.session.NetworkSessionGateway
import com.tutu.myblbl.network.response.BaseBaseResponse
import com.tutu.myblbl.core.common.log.AppLog
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class UserRepository(
    private val apiService: ApiService,
    private val sessionGateway: NetworkSessionGateway,
    private val securityGateway: NetworkSecurityGateway
) {

    private val detailCache = mutableMapOf<String, Any>()
    private val detailSemaphore = Semaphore(3)

    suspend fun getUserDetailInfo(): BaseResponse<UserDetailInfoModel> {
        return apiService.getUserDetailInfo().also { response ->
            sessionGateway.syncUserSession(response, source = "getUserDetailInfo")
        }
    }
    
    suspend fun getUserStat(): BaseResponse<UserStatModel> {
        return apiService.getUserStat().also { response ->
            sessionGateway.handleAuthFailureCode(response.code, source = "getUserStat")
        }
    }

    suspend fun getRelationStat(mid: Long): BaseResponse<UserStatModel> {
        return sessionGateway.syncAuthState(
            apiService.getRelationStat(mid),
            source = "getRelationStat"
        )
    }
    
    suspend fun getUserSpace(mid: Long): BaseResponse<UserSpaceInfo> {
        if (!isLoggedIn()) {
            return apiService.getUserSpaceNoWbi(mid)
        }
        val params = mutableMapOf(
            "mid" to mid.toString(),
            "token" to "",
            "platform" to "web",
            "web_location" to "1550101"
        )
        params.putAll(WbiGenerator.getSpaceDmParams())
        val response = doWbiRequest(params, "getUserSpace") { signedParams ->
            sessionGateway.syncAuthState(
                apiService.getUserSpace(signedParams),
                source = "getUserSpace"
            )
        }
        return response
    }
    
    fun isLoggedIn(): Boolean {
        return sessionGateway.isLoggedIn()
    }
    
    suspend fun getHistory(viewAt: Long, pageSize: Int): Result<BaseResponse<HistoryListResponse>> =
        runCatching {
            sessionGateway.syncAuthState(
                apiService.getHistory(viewAt, pageSize),
                source = "getHistory"
            )
        }

    suspend fun getLaterWatch(): Result<BaseResponse<LaterWatchWrapper>> =
        runCatching {
            val response = sessionGateway.syncAuthState(
                apiService.getLaterWatch(),
                source = "getLaterWatch"
            )
            if (response.isSuccess && response.data != null) {
                enrichPgcDanmakuCount(response.data.list)
            }
            response
        }

    suspend fun getFollowing(
        mid: Long,
        page: Int = 1,
        pageSize: Int = 50
    ): Result<BaseResponse<GetFollowUserWrapper>> =
        runCatching {
            sessionGateway.syncAuthState(
                apiService.getFollowing(mid, page, pageSize),
                source = "getFollowing"
            )
        }

    suspend fun getFollower(
        mid: Long,
        page: Int = 1,
        pageSize: Int = 50
    ): Result<BaseResponse<GetFollowUserWrapper>> =
        runCatching {
            sessionGateway.syncAuthState(
                apiService.getFollower(mid, page, pageSize),
                source = "getFollower"
            )
        }

    suspend fun checkUserRelation(
        mid: Long
    ): Result<BaseResponse<CheckRelationModel>> =
        runCatching {
            sessionGateway.syncAuthState(
                apiService.checkUserRelation(mid.toString()),
                source = "checkUserRelation"
            )
        }

    suspend fun modifyRelation(
        fid: Long,
        action: Int
    ): Result<BaseBaseResponse> =
        runCatching {
            securityGateway.ensureHealthyForPlay()
            val csrf = sessionGateway.requireCsrfToken()
                ?: return Result.success(BaseBaseResponse(code = -101, message = "csrf token is blank"))
            val params = mapOf(
                "fid" to fid.toString(),
                "act" to action.toString(),
                "csrf" to csrf
            )
            sessionGateway.syncAuthState(
                apiService.userRelationModify(params),
                source = "modifyRelation"
            )
        }

    suspend fun getUserDynamic(
        mid: Long,
        page: Int,
        pageSize: Int = 20
    ): Result<BaseResponse<UserDynamicResponse>> =
        runCatching {
            if (isLoggedIn()) {
                val params = mutableMapOf(
                    "mid" to mid.toString(),
                    "pn" to page.toString(),
                    "ps" to pageSize.toString(),
                    "order" to "pubdate",
                    "platform" to "web",
                    "web_location" to "333.1387",
                    "order_avoided" to "true",
                    "index" to "1"
                )
                params.putAll(WbiGenerator.getSpaceDmParams())
                doWbiRequest(params, "getUserDynamic") { signedParams ->
                    sessionGateway.syncAuthState(
                        apiService.getUserArcSearch(signedParams),
                        source = "getUserDynamic"
                    )
                }
            } else {
                apiService.getUserDynamic(mid, page, pageSize)
            }
        }

    suspend fun getAllDynamic(
        page: Int,
        offset: Long? = null
    ): Result<BaseResponse<AllDynamicResponse>> =
        runCatching {
            sessionGateway.syncAuthState(
                apiService.getAllDynamic(page, offset),
                source = "getAllDynamic"
            )
        }

    suspend fun refreshCurrentUserInfo(): Result<UserDetailInfoModel> =
        runCatching {
            val response = apiService.getUserDetailInfo()
            val info = sessionGateway.syncUserSession(
                response,
                source = "refreshCurrentUserInfo"
            )
                ?: throw IllegalStateException(
                    response.errorMessage.ifEmpty { "加载用户信息失败" }
                )
            info
        }

    suspend fun resolveCurrentUserMid(): Result<Long> =
        runCatching {
            sessionGateway.getUserInfo()?.mid
                ?.takeIf { it > 0L }
                ?: refreshCurrentUserInfo().getOrThrow().mid.takeIf { it > 0L }
                ?: throw IllegalStateException("未获取到当前用户信息")
        }

    private suspend fun <T> doWbiRequest(
        params: Map<String, String>,
        source: String,
        block: suspend (Map<String, String>) -> BaseResponse<T>
    ): BaseResponse<T> {
        ensureWbiKeysIfNeeded()
        val wbiKeys = sessionGateway.getWbiKeys()
        val signedParams = WbiGenerator.generateWbiParams(params, wbiKeys.first, wbiKeys.second)
        val response = block(signedParams)
        if (response.code == -352) {
            AppLog.w("UserRepository", "$source hit -352 risk control, retrying with prewarm")
            delay(1500L)
            sessionGateway.prewarmWebSession(forceUaRefresh = true)
            ensureWbiKeysIfNeeded()
            val newKeys = sessionGateway.getWbiKeys()
            val retryParams = WbiGenerator.generateWbiParams(params, newKeys.first, newKeys.second)
            return block(retryParams)
        }
        return response
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun enrichPgcDanmakuCount(list: List<com.tutu.myblbl.model.video.VideoModel>) {
        val candidates = list.filter { it.danmakuCount == 0L && it.viewCount > 0L }
        if (candidates.isEmpty()) return
        try {
            supervisorScope {
                candidates.map { video ->
                    async {
                        val cacheKey = if (video.bvid.isNotBlank()) "bvid_${video.bvid}" else "aid_${video.aid}"
                        try {
                            // 先检查缓存
                            val cached = detailCache[cacheKey]
                            if (cached != null) {
                                val dm = cached as Long
                                if (dm > 0) {
                                    video.stat?.let { it.danmaku = dm }
                                }
                                return@async
                            }

                            detailSemaphore.withPermit {
                                val detailResponse = apiService.getVideoDetail(video.aid, video.bvid)
                                if (!detailResponse.isSuccess) {
                                    detailCache[cacheKey] = 0L
                                    return@async
                                }
                                val redirectUrl = detailResponse.data?.view?.redirectUrl.orEmpty()
                                val epId = parseEpIdFromBangumiUrl(redirectUrl)
                                if (epId <= 0L) {
                                    detailCache[cacheKey] = 0L
                                    return@async
                                }
                                val pgcResponse = apiService.getPgcEpisodeInfo(epId)
                                if (pgcResponse.isSuccess) {
                                    val dm = pgcResponse.data
                                        ?.getAsJsonObject("stat")
                                        ?.get("dm")?.asLong ?: 0L
                                    detailCache[cacheKey] = dm
                                    if (dm > 0) {
                                        video.stat?.let { it.danmaku = dm }
                                    }
                                } else {
                                    detailCache[cacheKey] = 0L
                                }
                            }
                        } catch (e: Exception) {
                            AppLog.w("UserRepository", "enrichPgcDanmakuCount failed for aid=${video.aid}: ${e.message}")
                        }
                    }
                }.forEach { it.await() }
            }
        } catch (e: Exception) {
            AppLog.w("UserRepository", "enrichPgcDanmakuCount scope failed: ${e.message}")
        }
    }

    private fun parseEpIdFromBangumiUrl(url: String): Long {
        if (!url.contains("/bangumi/play/ep")) return 0L
        return url.substringAfter("/bangumi/play/ep", "")
            .takeWhile { it.isDigit() }
            .toLongOrNull() ?: 0L
    }

    private suspend fun ensureWbiKeysIfNeeded() {
        val keys = sessionGateway.getWbiKeys()
        if (keys.first.isBlank() || keys.second.isBlank() || sessionGateway.areWbiKeysStale()) {
            runCatching { sessionGateway.ensureWbiKeys() }
        }
    }
}

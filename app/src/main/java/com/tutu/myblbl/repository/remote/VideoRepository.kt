package com.tutu.myblbl.repository.remote

import com.tutu.myblbl.model.BaseResponse
import com.tutu.myblbl.model.common.CheckGiveCoinModel
import com.tutu.myblbl.model.common.GiveCoinResultModel
import com.tutu.myblbl.model.common.TripleActionResultModel
import com.tutu.myblbl.model.recommend.RecommendListDataModel
import com.tutu.myblbl.model.video.GetVideoByChannelWrapper
import com.tutu.myblbl.model.video.RegionVideoListWrapper
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.model.video.detail.VideoDetailModel
import com.tutu.myblbl.model.video.VideoPvModel
import com.tutu.myblbl.model.player.PlayInfoModel
import com.tutu.myblbl.network.api.ApiService
import com.tutu.myblbl.network.security.NetworkSecurityGateway
import com.tutu.myblbl.network.session.NetworkSessionGateway
import com.tutu.myblbl.network.response.ListDataModel
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.network.cookie.CookieManager

class VideoRepository(
    private val apiService: ApiService,
    private val sessionGateway: NetworkSessionGateway,
    private val securityGateway: NetworkSecurityGateway,
    private val cookieManager: CookieManager
) {

    companion object {
        private const val TAG = "VideoRepository"
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
                securityGateway.prewarmWebSession(forceUaRefresh = true)
                apiService.getRanking(rid).mapListData()
            } else {
                firstResponse.mapListData()
            }
        }

    suspend fun getRegionLatestVideos(
        rid: Int,
        page: Int,
        pageSize: Int
    ): Result<BaseResponse<RegionVideoListWrapper>> =
        runCatching {
            apiService.getRegionLatestVideos(rid = rid, page = page, pageSize = pageSize)
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

    suspend fun getVideoPv(aid: Long?, bvid: String?): Result<BaseResponse<List<VideoPvModel>>> =
        runCatching {
            apiService.getVideoPv(aid, bvid)
        }

    suspend fun getVideoPlayInfo(aid: Long?, bvid: String?, cid: Long): Result<BaseResponse<PlayInfoModel>> =
        runCatching {
            apiService.getVideoPlayInfo(aid, bvid, cid)
        }

    suspend fun getRelated(aid: Long?, bvid: String?): Result<BaseResponse<List<VideoModel>>> =
        runCatching {
            apiService.getRelated(aid, bvid)
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
            val hasSessdata = cookieManager.getCookieValue("SESSDATA")?.isNotBlank() ?: false
            val hasBuvid3 = cookieManager.getCookieValue("buvid3")?.isNotBlank() ?: false
            val hasBuvid4 = cookieManager.getCookieValue("buvid4")?.isNotBlank() ?: false
            AppLog.d(TAG, "tripleAction called: aid=$avid, bvid=$bvid, csrf=${if (csrf.isBlank()) "EMPTY" else "HAS_VALUE"}")
            AppLog.d(TAG, "Cookie check: SESSDATA=$hasSessdata, buvid3=$hasBuvid3, buvid4=$hasBuvid4")
            
            securityGateway.prewarmWebSession()
            val firstResponse = sessionGateway.syncAuthState(
                apiService.tripleAction(avid, bvid, csrf),
                source = "video.tripleAction.first"
            )
            AppLog.d(TAG, "tripleAction first response: code=${firstResponse.code}, message=${firstResponse.message}, msg=${firstResponse.msg}")
            if (firstResponse.code == -352 || firstResponse.code == -401) {
                AppLog.e(TAG, "tripleAction first attempt hit risk control/401: aid=$avid, bvid=$bvid, code=${firstResponse.code}")
                securityGateway.prewarmWebSession(forceUaRefresh = true)
                val secondResponse = sessionGateway.syncAuthState(
                    apiService.tripleAction(avid, bvid, csrf),
                    source = "video.tripleAction.second"
                )
                AppLog.d(TAG, "tripleAction second response: code=${secondResponse.code}, message=${secondResponse.message}, msg=${secondResponse.msg}")
                secondResponse
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
}

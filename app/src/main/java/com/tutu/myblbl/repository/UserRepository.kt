package com.tutu.myblbl.repository

import com.tutu.myblbl.model.BaseResponse
import com.tutu.myblbl.model.user.UserInfoModel
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
import com.tutu.myblbl.network.session.NetworkSessionGateway
import com.tutu.myblbl.network.response.BaseBaseResponse

class UserRepository(
    private val apiService: ApiService,
    private val sessionGateway: NetworkSessionGateway
) {
    
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
        return apiService.getRelationStat(mid)
    }
    
    suspend fun getUserSpace(mid: Long): BaseResponse<UserSpaceInfo> {
        val params = mapOf("mid" to mid.toString())
        val wbiKeys = sessionGateway.getWbiKeys()
        val signedParams = WbiGenerator.generateWbiParams(params, wbiKeys.first, wbiKeys.second)
        return sessionGateway.syncAuthState(
            apiService.getUserSpace(signedParams),
            source = "getUserSpace"
        )
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
            sessionGateway.syncAuthState(
                apiService.getLaterWatch(),
                source = "getLaterWatch"
            )
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
            val params = mapOf(
                "fid" to fid.toString(),
                "act" to action.toString(),
                "csrf" to sessionGateway.getCsrfToken()
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
            sessionGateway.syncAuthState(
                apiService.getUserDynamic(mid, page, pageSize),
                source = "getUserDynamic"
            )
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
}

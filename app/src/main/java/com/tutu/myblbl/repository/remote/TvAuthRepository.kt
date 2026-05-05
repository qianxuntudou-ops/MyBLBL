package com.tutu.myblbl.repository.remote

import com.tutu.myblbl.model.user.TvPollData
import com.tutu.myblbl.model.user.TvQrCodeData
import com.tutu.myblbl.model.BaseResponse
import com.tutu.myblbl.network.api.ApiService
import com.tutu.myblbl.network.security.AppSignUtils

class TvAuthRepository(
    private val apiService: ApiService
) {
    suspend fun generateTvQrCode(): BaseResponse<TvQrCodeData> {
        val params = mutableMapOf(
            "appkey" to AppSignUtils.TV_APP_KEY,
            "local_id" to "0",
            "ts" to AppSignUtils.getTimestamp().toString()
        )
        val signed = AppSignUtils.signForTvLogin(params)
        return apiService.generateTvQrCode(signed)
    }

    suspend fun pollTvQrCode(authCode: String): BaseResponse<TvPollData> {
        val params = mutableMapOf(
            "appkey" to AppSignUtils.TV_APP_KEY,
            "auth_code" to authCode,
            "local_id" to "0",
            "ts" to AppSignUtils.getTimestamp().toString()
        )
        val signed = AppSignUtils.signForTvLogin(params)
        return apiService.pollTvQrCode(signed)
    }

    suspend fun refreshTvToken(accessToken: String, refreshToken: String): BaseResponse<TvPollData> {
        val params = mutableMapOf(
            "access_key" to accessToken,
            "refresh_token" to refreshToken,
            "appkey" to AppSignUtils.TV_APP_KEY,
            "ts" to AppSignUtils.getTimestamp().toString()
        )
        val signed = AppSignUtils.signForTvLogin(params)
        return apiService.refreshTvToken(signed)
    }
}

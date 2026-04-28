package com.tutu.myblbl.repository.remote

import com.tutu.myblbl.model.BaseResponse
import com.tutu.myblbl.model.user.ScanQrModel
import com.tutu.myblbl.model.user.SignInResultModel
import com.tutu.myblbl.model.user.SsoListModel
import com.tutu.myblbl.network.api.ApiService
import com.tutu.myblbl.network.session.NetworkSessionGateway

class AuthRepository(
    private val apiService: ApiService,
    private val sessionGateway: NetworkSessionGateway
) {

    suspend fun getQrCode(): Result<BaseResponse<ScanQrModel>> =
        runCatching {
            apiService.getSignInQrCode()
        }

    suspend fun checkSignInResult(qrcodeKey: String, bRet: String): Result<BaseResponse<SignInResultModel>> =
        runCatching {
            apiService.checkSignInResult(qrcodeKey, bRet = bRet)
        }

    suspend fun getSsoList(csrf: String): Result<BaseResponse<SsoListModel>> =
        runCatching {
            apiService.getSsoList(csrf)
        }

    suspend fun setSso(url: String, bRet: String): Result<BaseResponse<Any>> =
        runCatching {
            apiService.setSso(url, bRet = bRet)
        }
}

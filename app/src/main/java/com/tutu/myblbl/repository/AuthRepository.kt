package com.tutu.myblbl.repository

import com.tutu.myblbl.model.BaseResponse
import com.tutu.myblbl.model.user.ScanQrModel
import com.tutu.myblbl.model.user.SignInResultModel
import com.tutu.myblbl.model.user.SsoListModel
import com.tutu.myblbl.repository.remote.AuthRepository as NetworkAuthRepository

class AuthRepository(
    private val delegate: NetworkAuthRepository
) {
    suspend fun getQrCode(): Result<BaseResponse<ScanQrModel>> = delegate.getQrCode()

    suspend fun checkSignInResult(qrcodeKey: String, bRet: String): Result<BaseResponse<SignInResultModel>> =
        delegate.checkSignInResult(qrcodeKey, bRet)

    suspend fun getSsoList(csrf: String): Result<BaseResponse<SsoListModel>> =
        delegate.getSsoList(csrf)

    suspend fun setSso(url: String, bRet: String): Result<BaseResponse<Any>> =
        delegate.setSso(url, bRet)
}

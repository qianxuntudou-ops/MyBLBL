package com.tutu.myblbl.network.session

import com.tutu.myblbl.model.BaseResponse
import com.tutu.myblbl.model.user.UserDetailInfoModel
import com.tutu.myblbl.network.NetworkManager
import com.tutu.myblbl.network.response.Base2Response
import com.tutu.myblbl.network.response.BaseBaseResponse

interface NetworkSessionGateway {
    fun getCsrfToken(): String

    fun isLoggedIn(): Boolean

    fun getUserInfo(): UserDetailInfoModel?

    fun getWbiKeys(): Pair<String, String>

    fun areWbiKeysStale(): Boolean

    suspend fun ensureWbiKeys()

    fun clearUserSession(reason: String)

    fun handleAuthFailureCode(code: Int, source: String)

    fun syncUserSession(
        response: BaseResponse<UserDetailInfoModel>,
        source: String
    ): UserDetailInfoModel?

    fun <T> syncAuthState(
        response: BaseResponse<T>,
        source: String
    ): BaseResponse<T>

    fun syncAuthState(
        response: BaseBaseResponse,
        source: String
    ): BaseBaseResponse

    fun <T> syncAuthState(
        response: Base2Response<T>,
        source: String
    ): Base2Response<T>
}

class NetworkManagerSessionGateway : NetworkSessionGateway {
    override fun getCsrfToken(): String = NetworkManager.getCsrfToken()

    override fun isLoggedIn(): Boolean = NetworkManager.isLoggedIn()

    override fun getUserInfo(): UserDetailInfoModel? = NetworkManager.getUserInfo()

    override fun getWbiKeys(): Pair<String, String> = NetworkManager.getWbiKeys()

    override fun areWbiKeysStale(): Boolean = NetworkManager.areWbiKeysStale()

    override suspend fun ensureWbiKeys() = NetworkManager.ensureWbiKeys()

    override fun clearUserSession(reason: String) {
        NetworkManager.clearUserSession(reason = reason)
    }

    override fun handleAuthFailureCode(code: Int, source: String) {
        NetworkManager.handleAuthFailureCode(code, source)
    }

    override fun syncUserSession(
        response: BaseResponse<UserDetailInfoModel>,
        source: String
    ): UserDetailInfoModel? {
        return NetworkManager.syncUserSession(response, source)
    }

    override fun <T> syncAuthState(
        response: BaseResponse<T>,
        source: String
    ): BaseResponse<T> {
        return NetworkManager.syncAuthState(response, source)
    }

    override fun syncAuthState(
        response: BaseBaseResponse,
        source: String
    ): BaseBaseResponse {
        return NetworkManager.syncAuthState(response, source)
    }

    override fun <T> syncAuthState(
        response: Base2Response<T>,
        source: String
    ): Base2Response<T> {
        return NetworkManager.syncAuthState(response, source)
    }
}

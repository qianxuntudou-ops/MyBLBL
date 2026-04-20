package com.tutu.myblbl.network.session

import com.tutu.myblbl.event.AppEventHub
import com.tutu.myblbl.model.BaseResponse
import com.tutu.myblbl.model.user.UserDetailInfoModel
import com.tutu.myblbl.network.NetworkManager
import com.tutu.myblbl.network.response.Base2Response
import com.tutu.myblbl.network.response.BaseBaseResponse
import org.koin.mp.KoinPlatform

interface NetworkSessionGateway {
    fun getCsrfToken(): String

    fun isLoggedIn(): Boolean

    fun getUserInfo(): UserDetailInfoModel?

    fun getWbiKeys(): Pair<String, String>

    fun areWbiKeysStale(): Boolean

    suspend fun ensureWbiKeys()

    fun clearUserSession(reason: String)

    fun handleAuthFailureCode(code: Int, source: String)

    suspend fun prewarmWebSession(forceUaRefresh: Boolean = false): Boolean

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

    fun isCsrfError(code: Int, message: String?): Boolean

    fun handleResponseAuthError(code: Int, message: String?): Boolean
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

    override suspend fun prewarmWebSession(forceUaRefresh: Boolean): Boolean {
        return NetworkManager.prewarmWebSession(forceUaRefresh)
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

    override fun isCsrfError(code: Int, message: String?): Boolean {
        if (code == -101 || code == -111) return true
        return message.orEmpty().contains("csrf", ignoreCase = true)
    }

    override fun handleResponseAuthError(code: Int, message: String?): Boolean {
        if (!isCsrfError(code, message)) return false
        val wasLoggedIn = NetworkManager.isLoggedIn()
        NetworkManager.clearUserSession(reason = "csrf_error")
        if (wasLoggedIn) {
            runCatching { KoinPlatform.getKoin().get<AppEventHub>() }
                .getOrNull()
                ?.dispatch(AppEventHub.Event.UserSessionChanged)
        }
        return true
    }
}

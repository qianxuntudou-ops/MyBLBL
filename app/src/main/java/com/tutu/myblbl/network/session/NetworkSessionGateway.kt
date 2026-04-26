package com.tutu.myblbl.network.session

import com.tutu.myblbl.core.common.log.AppLog
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

    @Deprecated("Use classifyActionError instead", ReplaceWith("classifyActionError(code, message)"))
    fun handleResponseAuthError(code: Int, message: String?): Boolean

    // ========== 新增：Context-aware 方法 ==========

    fun syncUserSession(
        response: BaseResponse<UserDetailInfoModel>,
        source: String,
        context: AuthContext
    ): UserDetailInfoModel?

    fun <T> syncAuthState(
        response: BaseResponse<T>,
        source: String,
        context: AuthContext
    ): BaseResponse<T>

    fun syncAuthState(
        response: BaseBaseResponse,
        source: String,
        context: AuthContext
    ): BaseBaseResponse

    fun <T> syncAuthState(
        response: Base2Response<T>,
        source: String,
        context: AuthContext
    ): Base2Response<T>

    /** 返回 csrf token，为空则返回 null（表示 session 不完整） */
    fun requireCsrfToken(): String?

    /** 统一风控检测 */
    fun isRiskControl(code: Int, message: String? = null): Boolean

    /** 统一错误分类，给 UI 层使用 */
    fun classifyActionError(code: Int, message: String?): ActionError

    // ========== 错误分类 ==========

    sealed interface ActionError {
        data class SessionExpired(val message: String) : ActionError
        data class CsrfMismatch(val message: String) : ActionError
        data class CsrfMissing(val message: String) : ActionError
        data class RiskControl(val message: String) : ActionError
        data class Other(val message: String) : ActionError
    }
}

class NetworkManagerSessionGateway : NetworkSessionGateway {

    private val riskControlCodes = setOf(-352, -412, -351)
    private val riskControlKeywords = listOf("风控", "拦截", "异常", "非法", "风险", "神秘力量", "risk", "blocked")

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

    @Deprecated("Use classifyActionError instead", ReplaceWith("classifyActionError(code, message)"))
    override fun handleResponseAuthError(code: Int, message: String?): Boolean {
        return classifyActionError(code, message) is NetworkSessionGateway.ActionError.SessionExpired
    }

    // ========== Context-aware 实现 ==========

    override fun syncUserSession(
        response: BaseResponse<UserDetailInfoModel>,
        source: String,
        context: AuthContext
    ): UserDetailInfoModel? {
        return NetworkManager.syncUserSession(response, source, context)
    }

    override fun <T> syncAuthState(
        response: BaseResponse<T>,
        source: String,
        context: AuthContext
    ): BaseResponse<T> {
        return NetworkManager.syncAuthState(response, source, context)
    }

    override fun syncAuthState(
        response: BaseBaseResponse,
        source: String,
        context: AuthContext
    ): BaseBaseResponse {
        return NetworkManager.syncAuthState(response, source, context)
    }

    override fun <T> syncAuthState(
        response: Base2Response<T>,
        source: String,
        context: AuthContext
    ): Base2Response<T> {
        return NetworkManager.syncAuthState(response, source, context)
    }

    override fun requireCsrfToken(): String? {
        val csrf = NetworkManager.getCsrfToken()
        if (csrf.isBlank()) {
            AppLog.w("SessionGateway", "requireCsrfToken: csrf token is blank")
        }
        return csrf.takeIf { it.isNotBlank() }
    }

    override fun isRiskControl(code: Int, message: String?): Boolean {
        if (code in riskControlCodes) return true
        val msg = message.orEmpty()
        return riskControlKeywords.any { msg.contains(it, ignoreCase = true) }
    }

    override fun classifyActionError(code: Int, message: String?): NetworkSessionGateway.ActionError {
        val msg = message ?: ""
        // -111 / csrf 不匹配：不清 session
        if (code == -111 || (msg.contains("csrf", ignoreCase = true) && code != -101)) {
            return NetworkSessionGateway.ActionError.CsrfMismatch(msg.ifEmpty { "csrf 校验失败" })
        }
        // -101：真的过期
        if (code == -101) {
            NetworkManager.clearUserSession(reason = "session_expired")
            dispatchSessionChanged()
            return NetworkSessionGateway.ActionError.SessionExpired(msg.ifEmpty { "登录已过期" })
        }
        // 风控
        if (isRiskControl(code, message)) {
            return NetworkSessionGateway.ActionError.RiskControl(msg.ifEmpty { "账号被风控" })
        }
        return NetworkSessionGateway.ActionError.Other(msg.ifEmpty { "操作失败" })
    }

    private fun dispatchSessionChanged() {
        runCatching { KoinPlatform.getKoin().get<AppEventHub>() }
            .getOrNull()
            ?.dispatch(AppEventHub.Event.UserSessionChanged)
    }
}

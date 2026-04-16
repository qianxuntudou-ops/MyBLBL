package com.tutu.myblbl.network.session

import com.tutu.myblbl.model.BaseResponse
import com.tutu.myblbl.model.user.UserDetailInfoModel
import com.tutu.myblbl.network.WbiGenerator
import com.tutu.myblbl.network.response.Base2Response
import com.tutu.myblbl.network.response.BaseBaseResponse

class NetworkSessionStore(
    private val authInvalidCode: Int
) {

    companion object {
        private const val WBI_KEYS_STALE_MS = 24 * 60 * 60 * 1000L
    }

    private var wbiImageKey: String = ""
    private var wbiSubKey: String = ""
    private var wbiKeysUpdatedAt: Long = 0L
    private var userInfo: UserDetailInfoModel? = null

    fun setWbiInfo(imgKey: String, subKey: String) {
        wbiImageKey = imgKey
        wbiSubKey = subKey
        if (imgKey.isNotBlank() && subKey.isNotBlank()) {
            wbiKeysUpdatedAt = System.currentTimeMillis()
        }
    }

    fun getWbiKeys(): Pair<String, String> {
        return Pair(wbiImageKey, wbiSubKey)
    }

    fun areWbiKeysStale(): Boolean {
        if (wbiImageKey.isBlank() || wbiSubKey.isBlank()) return true
        return System.currentTimeMillis() - wbiKeysUpdatedAt > WBI_KEYS_STALE_MS
    }

    fun getUserInfo(): UserDetailInfoModel? {
        return userInfo
    }

    fun clearUserSession() {
        userInfo = null
        wbiKeysUpdatedAt = 0L
        setWbiInfo("", "")
    }

    fun updateUserSession(info: UserDetailInfoModel?) {
        userInfo = info?.let {
            it.copy(face = normalizeAvatarUrl(it.face))
        }
        if (userInfo == null) {
            setWbiInfo("", "")
            return
        }
        val normalizedUser = requireNotNull(userInfo)
        val imgKey = normalizedUser.wbiImg?.imgUrl?.let(WbiGenerator::extractKeyFromUrl).orEmpty()
        val subKey = normalizedUser.wbiImg?.subUrl?.let(WbiGenerator::extractKeyFromUrl).orEmpty()
        setWbiInfo(imgKey, subKey)
    }

    fun syncUserSession(
        response: BaseResponse<UserDetailInfoModel>,
        onAuthInvalid: (() -> Unit)? = null
    ): UserDetailInfoModel? {
        val info = response.data?.takeIf { response.isSuccess }
        if (info != null) {
            updateUserSession(info)
            return userInfo
        }
        if (isAuthInvalid(response.code)) {
            clearUserSession()
            onAuthInvalid?.invoke()
        }
        return null
    }

    fun handleAuthFailureCode(
        code: Int,
        onAuthInvalid: (() -> Unit)? = null
    ) {
        if (isAuthInvalid(code)) {
            clearUserSession()
            onAuthInvalid?.invoke()
        }
    }

    fun <T> syncAuthState(
        response: BaseResponse<T>,
        onAuthInvalid: (() -> Unit)? = null
    ): BaseResponse<T> {
        handleAuthFailureCode(response.code, onAuthInvalid)
        return response
    }

    fun syncAuthState(
        response: BaseBaseResponse,
        onAuthInvalid: (() -> Unit)? = null
    ): BaseBaseResponse {
        handleAuthFailureCode(response.code, onAuthInvalid)
        return response
    }

    fun <T> syncAuthState(
        response: Base2Response<T>,
        onAuthInvalid: (() -> Unit)? = null
    ): Base2Response<T> {
        handleAuthFailureCode(response.code, onAuthInvalid)
        return response
    }

    private fun normalizeAvatarUrl(url: String): String = when {
        url.startsWith("http://") -> "https://${url.substring(7)}"
        url.startsWith("//") -> "https:$url"
        else -> url
    }

    private fun isAuthInvalid(code: Int): Boolean {
        return code == authInvalidCode
    }
}

package com.tutu.myblbl.network.session

import android.content.SharedPreferences
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
        private const val PREFS_KEY_WBI_IMG = "wbi_img_key"
        private const val PREFS_KEY_WBI_SUB = "wbi_sub_key"
    }

    private var wbiImageKey: String = ""
    private var wbiSubKey: String = ""
    private var wbiKeysUpdatedAt: Long = 0L
    private var userInfo: UserDetailInfoModel? = null
    private var sharedPrefs: SharedPreferences? = null

    fun initPersistence(prefs: SharedPreferences) {
        sharedPrefs = prefs
        restorePersistedWbiKeys()
    }

    private fun restorePersistedWbiKeys() {
        val img = sharedPrefs?.getString(PREFS_KEY_WBI_IMG, "").orEmpty()
        val sub = sharedPrefs?.getString(PREFS_KEY_WBI_SUB, "").orEmpty()
        if (img.isNotBlank() && sub.isNotBlank()) {
            wbiImageKey = img
            wbiSubKey = sub
            wbiKeysUpdatedAt = System.currentTimeMillis()
        }
    }

    fun setWbiInfo(imgKey: String, subKey: String) {
        wbiImageKey = imgKey
        wbiSubKey = subKey
        if (imgKey.isNotBlank() && subKey.isNotBlank()) {
            wbiKeysUpdatedAt = System.currentTimeMillis()
        }
        sharedPrefs?.edit()?.apply {
            putString(PREFS_KEY_WBI_IMG, imgKey)
            putString(PREFS_KEY_WBI_SUB, subKey)
            apply()
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
            val wasLoggedIn = userInfo != null
            clearUserSession()
            // Extract WBI keys from -101 response if available (nav returns wbi_img even for unauthenticated users)
            extractWbiKeysFromData(response.data)
            if (wasLoggedIn) {
                onAuthInvalid?.invoke()
            }
        }
        return null
    }

    fun handleAuthFailureCode(
        code: Int,
        onAuthInvalid: (() -> Unit)? = null
    ) {
        if (isAuthInvalid(code)) {
            val wasLoggedIn = userInfo != null
            clearUserSession()
            if (wasLoggedIn) {
                onAuthInvalid?.invoke()
            }
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

    private fun extractWbiKeysFromData(data: UserDetailInfoModel?) {
        data?.wbiImg?.let { wbiImg ->
            val imgKey = WbiGenerator.extractKeyFromUrl(wbiImg.imgUrl)
            val subKey = WbiGenerator.extractKeyFromUrl(wbiImg.subUrl)
            if (imgKey.isNotBlank() && subKey.isNotBlank()) {
                setWbiInfo(imgKey, subKey)
            }
        }
    }
}

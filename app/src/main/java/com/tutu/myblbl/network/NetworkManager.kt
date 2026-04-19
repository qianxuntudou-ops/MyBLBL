@file:Suppress("SpellCheckingInspection")

package com.tutu.myblbl.network

import android.content.Context
import com.tutu.myblbl.model.BaseResponse
import com.tutu.myblbl.model.user.UserDetailInfoModel
import com.tutu.myblbl.network.api.ApiService
import com.tutu.myblbl.network.http.NetworkClientFactory
import com.tutu.myblbl.network.response.Base2Response
import com.tutu.myblbl.network.response.BaseBaseResponse
import com.tutu.myblbl.network.security.BiliSecurityCoordinator
import com.tutu.myblbl.network.session.NetworkSessionStore
import com.tutu.myblbl.network.ua.DesktopUserAgentStore
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.core.common.settings.AppSettingsDataStore
import com.tutu.myblbl.network.cookie.CookieManager
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.koin.mp.KoinPlatform
import retrofit2.Retrofit

object NetworkManager {

    private const val TAG = "NetworkManager"
    private const val API_BASE = "https://api.bilibili.com/"
    private const val DEFAULT_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"
    private const val PREF_NAME = "app_settings"
    private const val KEY_CURRENT_UA = "currentUA"
    private const val AUTH_INVALID_CODE = -101
    private const val KEY_REFRESH_TOKEN = "bili_refresh_token"

    private var appContext: Context? = null

    private val userAgentStore = DesktopUserAgentStore(
        defaultUserAgent = DEFAULT_UA,
        preferenceName = PREF_NAME,
        preferenceKey = KEY_CURRENT_UA
    )
    private val sessionStore = NetworkSessionStore(authInvalidCode = AUTH_INVALID_CODE)

    private val currentUserAgentValue: String
        get() = userAgentStore.getCurrentUserAgent()

    private val internalCookieManager: CookieManager by lazy { CookieManager() }

    private val internalOkHttpClient: OkHttpClient by lazy {
        val settings: AppSettingsDataStore? = runCatching {
            KoinPlatform.getKoin().get<AppSettingsDataStore>()
        }.getOrNull()
        NetworkClientFactory.createOkHttpClient(
            cookieManager = internalCookieManager,
            userAgentProvider = { currentUserAgentValue },
            acceptLanguageProvider = { getAcceptLanguage() },
            cacheDir = appContext?.cacheDir,
            ipv4OnlyEnabled = { settings?.getCachedString("ipv4_only") != "关" }
        )
    }

    private val noCookieOkHttpClient: OkHttpClient by lazy {
        internalOkHttpClient.newBuilder()
            .cookieJar(okhttp3.CookieJar.NO_COOKIES)
            .build()
    }

    private val gson by lazy {
        NetworkClientFactory.createGson()
    }

    private val retrofit: Retrofit by lazy {
        NetworkClientFactory.createRetrofit(
            baseUrl = API_BASE,
            client = internalOkHttpClient,
            gson = gson
        )
    }

    private val noCookieRetrofit: Retrofit by lazy {
        NetworkClientFactory.createRetrofit(
            baseUrl = API_BASE,
            client = noCookieOkHttpClient,
            gson = gson
        )
    }

    val apiService: ApiService by lazy {
        retrofit.create(ApiService::class.java)
    }

    val noCookieApiService: ApiService by lazy {
        noCookieRetrofit.create(ApiService::class.java)
    }

    private val securityCoordinator: BiliSecurityCoordinator by lazy {
        BiliSecurityCoordinator(
            tag = TAG,
            apiService = apiService,
            okHttpClient = internalOkHttpClient,
            cookieManager = internalCookieManager,
            userAgentProvider = { currentUserAgentValue },
            refreshUserAgent = ::refreshUserAgent,
            syncUserSession = ::syncUserSession,
            refreshTokenProvider = { getRefreshToken() },
            refreshTokenSaver = { token -> saveRefreshToken(token) },
            updateWbiKeys = { img, sub -> sessionStore.setWbiInfo(img, sub) }
        )
    }

    fun init(context: Context) {
        val applicationContext = context.applicationContext
        appContext = applicationContext
        internalCookieManager.init(applicationContext)
        userAgentStore.init(applicationContext)
    }

    fun setWbiInfo(imgKey: String, subKey: String) {
        sessionStore.setWbiInfo(imgKey, subKey)
    }

    fun getWbiKeys(): Pair<String, String> {
        return sessionStore.getWbiKeys()
    }

    fun areWbiKeysStale(): Boolean {
        return sessionStore.areWbiKeysStale()
    }

    fun getCookieManager(): CookieManager = internalCookieManager

    fun getCsrfToken(): String {
        return internalCookieManager.getCsrfToken()
    }

    fun isLoggedIn(): Boolean {
        return internalCookieManager.hasSessionCookie()
    }

    fun clearUserSession(clearCookies: Boolean = true, reason: String = "unknown") {
        sessionStore.clearUserSession()
        resetSessionLifecycleState(clearCookies = clearCookies, reason = reason)
    }

    private fun resetSessionLifecycleState(clearCookies: Boolean, reason: String) {
        if (clearCookies) {
            internalCookieManager.clearCookies()
            clearRefreshToken()
        }
        securityCoordinator.resetRuntimeState()
    }

    private fun getRefreshToken(): String? {
        return runCatching {
            KoinPlatform.getKoin().get<AppSettingsDataStore>().getCachedString(KEY_REFRESH_TOKEN)
        }.getOrNull()
    }

    private fun saveRefreshToken(token: String) {
        runCatching {
            KoinPlatform.getKoin().get<AppSettingsDataStore>().putStringAsync(KEY_REFRESH_TOKEN, token)
        }.onFailure {
            AppLog.e(TAG, "saveRefreshToken failed: ${it.message}")
        }
    }

    private fun clearRefreshToken() {
        runCatching {
            KoinPlatform.getKoin().get<AppSettingsDataStore>().putStringAsync(KEY_REFRESH_TOKEN, null)
        }
    }

    fun saveLoginRefreshToken(token: String) {
        saveRefreshToken(token)
    }

    fun getOkHttpClient(): OkHttpClient = internalOkHttpClient

    fun getCurrentUserAgent(): String = currentUserAgentValue

    fun getAcceptLanguage(): String {
        return userAgentStore.getAcceptLanguage()
    }

    fun refreshUserAgent(): String {
        val newUserAgent = userAgentStore.refreshUserAgent(appContext)
        return newUserAgent
    }

    suspend fun ensureHealthyForPlay() {
        securityCoordinator.ensureHealthyForPlay()
    }

    suspend fun prewarmWebSession(forceUaRefresh: Boolean = false): Boolean {
        return securityCoordinator.prewarmWebSession(forceUaRefresh)
    }

    fun buildPiliWebHeaders(targetUrl: String, includeCookie: Boolean = true): Map<String, String> {
        return securityCoordinator.buildPiliWebHeaders(targetUrl, includeCookie)
    }

    suspend fun ensureWbiKeys() {
        securityCoordinator.ensureWbiKeys()
    }

    fun getUserInfo(): UserDetailInfoModel? {
        return sessionStore.getUserInfo()
    }

    fun updateUserSession(info: UserDetailInfoModel?) {
        sessionStore.updateUserSession(info)
    }

    fun syncUserSession(
        response: BaseResponse<UserDetailInfoModel>,
        source: String
    ): UserDetailInfoModel? {
        val info = sessionStore.syncUserSession(response) {
            resetSessionLifecycleState(
                clearCookies = true,
                reason = "$source code=${response.code}"
            )
        }
        if (info != null) {
            return info
        }
        return null
    }

    fun handleAuthFailureCode(code: Int, source: String) {
        sessionStore.handleAuthFailureCode(code) {
            resetSessionLifecycleState(
                clearCookies = true,
                reason = "$source code=$code"
            )
        }
    }

    fun <T> syncAuthState(
        response: BaseResponse<T>,
        source: String
    ): BaseResponse<T> {
        return sessionStore.syncAuthState(response) {
            resetSessionLifecycleState(
                clearCookies = true,
                reason = "$source code=${response.code}"
            )
        }
    }

    fun syncAuthState(
        response: BaseBaseResponse,
        source: String
    ): BaseBaseResponse {
        return sessionStore.syncAuthState(response) {
            resetSessionLifecycleState(
                clearCookies = true,
                reason = "$source code=${response.code}"
            )
        }
    }

    fun <T> syncAuthState(
        response: Base2Response<T>,
        source: String
    ): Base2Response<T> {
        return sessionStore.syncAuthState(response) {
            resetSessionLifecycleState(
                clearCookies = true,
                reason = "$source code=${response.code}"
            )
        }
    }

    suspend fun postFormJson(
        url: String,
        form: Map<String, String>,
        extraHeaders: Map<String, String>? = null
    ): JSONObject {
        return securityCoordinator.postFormJson(url, form, extraHeaders)
    }

    suspend fun requestJson(
        url: String,
        extraHeaders: Map<String, String>? = null
    ): JSONObject {
        return securityCoordinator.requestJson(url, extraHeaders)
    }
}

package com.tutu.myblbl.model.user

import com.google.gson.annotations.SerializedName

data class TvQrCodeData(
    @SerializedName("url")
    val url: String = "",
    @SerializedName("auth_code")
    val authCode: String = ""
)

data class TvPollData(
    @SerializedName("mid")
    val mid: Long = 0,
    @SerializedName("token_info")
    val tokenInfo: TvTokenInfo? = null,
    @SerializedName("cookie_info")
    val cookieInfo: TvCookieInfo? = null
)

data class TvTokenInfo(
    @SerializedName("access_token")
    val accessToken: String = "",
    @SerializedName("refresh_token")
    val refreshToken: String = "",
    @SerializedName("mid")
    val mid: Long = 0
)

data class TvCookieInfo(
    @SerializedName("cookies")
    val cookies: List<TvCookie>? = null,
    @SerializedName("domains")
    val domains: List<String>? = null
)

data class TvCookie(
    @SerializedName("name")
    val name: String = "",
    @SerializedName("value")
    val value: String = ""
)

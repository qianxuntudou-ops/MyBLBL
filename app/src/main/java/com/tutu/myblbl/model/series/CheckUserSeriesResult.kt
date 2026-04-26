package com.tutu.myblbl.model.series

import com.google.gson.annotations.SerializedName
import java.io.Serializable

data class CheckUserSeriesResult(
    @SerializedName("follow")
    val follow: Int = 0,
    @SerializedName("follow_status")
    val followStatus: Int = 0,
    @SerializedName("login")
    val login: Int = 0,
    @SerializedName("pay")
    val pay: Int = 0,
    @SerializedName("area_limit")
    val areaLimit: Int = 0,
    @SerializedName("ban_area_show")
    val banAreaShow: Int = 0
) : Serializable {
    val isFollowed: Boolean
        get() = follow == 1

    val isLoggedIn: Boolean
        get() = login == 1
}

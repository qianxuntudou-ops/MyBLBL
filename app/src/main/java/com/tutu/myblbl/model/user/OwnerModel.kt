package com.tutu.myblbl.model.user

import com.google.gson.annotations.SerializedName
import java.io.Serializable

data class OwnerModel(
    @SerializedName("mid")
    var mid: Long = 0,
    @SerializedName("name")
    var name: String = "",
    @SerializedName("face")
    var face: String = "",
    @SerializedName("sign")
    var sign: String = "",
    @SerializedName("level")
    var level: Int = 0,
    @SerializedName("sex")
    var sex: String = "",
    @SerializedName("official_verify")
    var officialVerify: OfficialVerifySimple? = null,
    @SerializedName("vip")
    var vip: VipSimple? = null
) : Serializable

data class OfficialVerifySimple(
    @SerializedName("type")
    val type: Int = -1,
    @SerializedName("desc")
    val desc: String = ""
)

data class VipSimple(
    @SerializedName("vipType")
    val vipType: Int = 0,
    @SerializedName("vipStatus")
    val vipStatus: Int = 0,
    @SerializedName("avatar_subscript")
    val avatarSubscript: Int = 0
)

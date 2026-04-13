package com.tutu.myblbl.model.user

import com.google.gson.annotations.SerializedName

data class DynamicFollowingResponse(
    @SerializedName("button_statement")
    val buttonStatement: String = "",
    @SerializedName("items")
    val items: List<DynamicFollowingItem>? = null
)

data class DynamicFollowingItem(
    @SerializedName("user_profile")
    val userProfile: DynamicFollowingProfile? = null
)

data class DynamicFollowingProfile(
    @SerializedName("info")
    val info: DynamicFollowingUserInfo? = null
)

data class DynamicFollowingUserInfo(
    @SerializedName("uid")
    val uid: String = "",
    @SerializedName("uname")
    val uname: String = "",
    @SerializedName("face")
    val face: String = "",

    @SerializedName("official_verify")
    val officialVerify: OfficialVerifySimple? = null
)

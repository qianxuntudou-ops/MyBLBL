package com.tutu.myblbl.model.series

import com.google.gson.annotations.SerializedName
import java.io.Serializable

data class SeriesUserState(
    @SerializedName("follow")
    val follow: Int = 0,
    @SerializedName("follow_status")
    val followStatus: Int = 0,
    @SerializedName("progress")
    val progress: EpisodeProgressModel? = null,
    @SerializedName("follow_info")
    val followInfo: FollowInfo? = null
) : Serializable {
    val isFollowed: Boolean
        get() = follow == 1 || followInfo?.follow == 1
}

data class FollowInfo(
    @SerializedName("follow")
    val follow: Int = 0,
    @SerializedName("follow_status")
    val followStatus: Int = 0
) : Serializable

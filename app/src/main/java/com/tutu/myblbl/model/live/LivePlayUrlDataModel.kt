package com.tutu.myblbl.model.live

import com.google.gson.annotations.SerializedName
import java.io.Serializable

data class LivePlayUrlDataModel(
    @SerializedName("current_quality")
    val currentQuality: Int = 0,
    @SerializedName("accept_quality")
    val acceptQuality: List<String>? = null,
    @SerializedName("current_qn")
    val currentQn: Int = 0,
    @SerializedName("quality_description")
    val qualityDescription: List<LiveQualityModel>? = null,
    @SerializedName("durl")
    val durl: List<LiveDUrlModel>? = null,
    @SerializedName("live_time")
    val liveTime: String? = null,
    val roomTitle: String? = null,
    val anchorName: String? = null
) : Serializable

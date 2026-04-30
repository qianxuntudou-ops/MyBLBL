package com.tutu.myblbl.model.dm

import com.google.gson.annotations.SerializedName

data class DmMaskInfo(
    @SerializedName("cid")
    val cid: Long,
    @SerializedName("plat")
    val plat: Int,
    @SerializedName("fps")
    val fps: Int,
    @SerializedName("time")
    val time: Long,
    @SerializedName("mask_url")
    val maskUrl: String
)
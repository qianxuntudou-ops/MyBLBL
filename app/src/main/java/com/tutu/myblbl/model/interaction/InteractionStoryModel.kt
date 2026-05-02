package com.tutu.myblbl.model.interaction

import com.google.gson.annotations.SerializedName
import java.io.Serializable

data class InteractionStoryModel(
    @SerializedName("cid")
    val cid: Long = 0,
    @SerializedName("edge_id")
    val edgeId: Long = 0,
    @SerializedName("start_pos")
    val startPos: Long = 0,
    @SerializedName("duration")
    val duration: Long = 0,
    @SerializedName("is_current")
    val isCurrent: Int = 0
) : Serializable

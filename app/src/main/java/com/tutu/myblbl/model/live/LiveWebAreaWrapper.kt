package com.tutu.myblbl.model.live

import com.google.gson.annotations.SerializedName

data class LiveWebAreaWrapper(
    @SerializedName("data")
    val data: List<LiveAreaCategoryParent>? = null
)

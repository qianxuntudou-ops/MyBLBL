package com.tutu.myblbl.model.user

import com.google.gson.annotations.SerializedName

data class SsoListModel(
    @SerializedName("sso")
    val sso: List<String> = emptyList()
)

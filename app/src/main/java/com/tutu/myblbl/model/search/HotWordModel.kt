package com.tutu.myblbl.model.search

import com.google.gson.annotations.SerializedName
import java.io.Serializable

data class HotWordModel(
    @SerializedName("keyword", alternate = ["word"])
    val keyword: String = "",
    @SerializedName("show_name")
    val showName: String = "",
    @SerializedName("pos")
    val pos: Int = 0,
    @SerializedName("hot_id")
    val hotId: String = "0",
    @SerializedName("id")
    val id: Long = 0,
    @SerializedName("icon")
    val icon: String = "",
    @SerializedName("score")
    val score: Long = 0
) : Serializable {

    companion object {
        const val HOT_ID_HISTORY = "-1"
        const val HOT_ID_SUGGEST = "0"
        const val HOT_ID_CLEAR_HISTORY = "-2"

        fun createHistory(keyword: String, pos: Int = 0): HotWordModel {
            return HotWordModel(
                keyword = keyword,
                showName = keyword,
                pos = pos,
                hotId = HOT_ID_HISTORY
            )
        }

        fun createSuggest(value: String, name: String = value): HotWordModel {
            return HotWordModel(
                keyword = value,
                showName = name,
                pos = 0,
                hotId = HOT_ID_SUGGEST
            )
        }

        fun createClearHistory(): HotWordModel {
            return HotWordModel(
                keyword = "",
                showName = "",
                pos = 0,
                hotId = HOT_ID_CLEAR_HISTORY
            )
        }

        fun createHot(keyword: String, showName: String, pos: Int): HotWordModel {
            return HotWordModel(
                keyword = keyword,
                showName = showName,
                pos = pos,
                hotId = pos.toString()
            )
        }
    }

    val isHistory: Boolean
        get() = hotId == HOT_ID_HISTORY

    val isSuggest: Boolean
        get() = hotId == HOT_ID_SUGGEST && pos == 0

    val isClearHistory: Boolean
        get() = hotId == HOT_ID_CLEAR_HISTORY

    val isHot: Boolean
        get() = hotId != HOT_ID_HISTORY && hotId != HOT_ID_SUGGEST
}

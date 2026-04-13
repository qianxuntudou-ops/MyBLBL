package com.tutu.myblbl.model.video

import com.google.gson.annotations.SerializedName
import com.tutu.myblbl.model.favorite.FolderStatModel
import java.io.Serializable

data class HistoryVideoModel(
    @SerializedName("title")
    val title: String = "",
    @SerializedName("long_title")
    val longTitle: String = "",
    @SerializedName("bvid")
    val bvid: String = "",
    @SerializedName("cover")
    val cover: String = "",
    @SerializedName("covers")
    val covers: List<String>? = null,
    @SerializedName("uri")
    val uri: String = "",
    @SerializedName("history")
    val history: HistoryModel? = null,
    @SerializedName("videos")
    val videos: Int = 0,
    @SerializedName("author_name")
    val authorName: String = "",
    @SerializedName("author_face")
    val authorFace: String = "",
    @SerializedName("author_mid")
    val authorMid: String = "",
    @SerializedName("view_at")
    val viewAt: Long = 0,
    @SerializedName("progress")
    val progress: Long = 0,
    @SerializedName("badge")
    val badge: String = "",
    @SerializedName("show_title")
    val showTitle: String = "",
    @SerializedName("duration")
    val duration: Long = 0,
    @SerializedName("total")
    val total: Int = 0,
    @SerializedName("new_desc")
    val newDesc: String = "",
    @SerializedName("is_finish")
    val isFinish: Int = 0,
    @SerializedName("is_fav")
    val isFav: Int = 0,
    @SerializedName("kid")
    val kid: Long = 0,
    @SerializedName("tag_name")
    val tagName: String = "",
    @SerializedName("fav_time")
    val favTime: Long = 0,
    @SerializedName("live_status")
    val liveStatus: Int = 0,
    @SerializedName("cnt_info")
    val cntInfo: FolderStatModel? = null,

    @SerializedName("is_upower_exclusive")
    val isUpowerExclusive: Boolean = false,

    @SerializedName("elec_arc_type")
    val elecArcType: Int = 0,

    @SerializedName("rights")
    val rights: HistoryVideoRights? = null
) : Serializable {
    val isChargingExclusive: Boolean
        get() = isUpowerExclusive || elecArcType == 1 || rights?.autoplay == 0
    fun toVideoModel(): VideoModel {
        val historyInfo = history
        val aid = historyInfo?.oid ?: 0L
        val cid = historyInfo?.cid ?: 0L
        val mappedBvid = historyInfo?.bvid?.ifEmpty { bvid } ?: bvid
        return VideoModel(
            aid = aid,
            bvid = mappedBvid,
            title = title.ifEmpty { showTitle },
            pic = cover,
            duration = duration,
            cid = cid,
            epid = historyInfo?.epid ?: 0L,
            redirectUrl = uri,
            owner = Owner(
                mid = authorMid.toLongOrNull() ?: 0L,
                name = authorName,
                face = authorFace
            ),
            historyProgress = progress,
            historyViewAt = viewAt,
            historyBadge = badge,
            historyBusiness = historyInfo?.business.orEmpty()
        )
    }
}

data class HistoryVideoRights(
    @SerializedName("autoplay")
    val autoplay: Int = 1,
    @SerializedName("elec")
    val elec: Int = 0
)

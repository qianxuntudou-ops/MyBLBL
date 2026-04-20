@file:Suppress("SpellCheckingInspection")

package com.tutu.myblbl.model.video

import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.SerializedName
import com.tutu.myblbl.model.adapter.FlexibleBooleanAdapter
import com.tutu.myblbl.model.series.UgcSeriesModel
import com.tutu.myblbl.model.user.OfficialVerifySimple
import java.io.Serializable

data class VideoModel(
    @SerializedName(value = "aid", alternate = ["id"])
    val aid: Long = 0,
    
    @SerializedName("bvid")
    val bvid: String = "",
    
    @SerializedName("title")
    val title: String = "",
    
    @SerializedName("pic")
    val pic: String = "",
    
    @SerializedName("cover")
    val cover: String = "",
    
    @SerializedName(value = "desc", alternate = ["description"])
    val desc: String = "",
    
    @SerializedName("duration")
    val duration: Long = 0,

    @SerializedName("length")
    val length: String = "",

    @SerializedName(value = "pubdate", alternate = ["created"])
    val pubDate: Long = 0,
    
    @SerializedName("ctime")
    val createTime: Long = 0,
    
    @SerializedName("owner")
    val owner: Owner? = null,
    
    @SerializedName("stat")
    val stat: Stat? = null,

    @SerializedName("bangumi")
    val bangumi: Bangumi? = null,

    @SerializedName("pages")
    val pages: ArrayList<VideoPvModel>? = null,

    @SerializedName("ugc_season")
    val ugcSeason: UgcSeriesModel? = null,

    @SerializedName("play")
    val play: Long = 0,

    @SerializedName("video_review")
    val videoReview: Long = 0,
    
    @SerializedName("cid")
    val cid: Long = 0,

    @SerializedName("goto")
    val goto: String = "av",

    @SerializedName("track_id")
    val trackId: String = "",
    
    @SerializedName("tname")
    val typeName: String = "",
    
    @SerializedName("tid")
    val typeId: Int = 0,
    
    @SerializedName("dynamic")
    val dynamicText: String = "",
    
    @SerializedName("dimension")
    val dimension: Dimension? = null,
    
    @SerializedName("is_live")
    @JsonAdapter(FlexibleBooleanAdapter::class)
    val isLive: Boolean = false,
    
    @SerializedName("room_id")
    val roomId: Long = 0,
    
    @SerializedName("is_followed")
    @JsonAdapter(FlexibleBooleanAdapter::class)
    val isFollowed: Boolean = false,
    
    @SerializedName("is_like")
    @JsonAdapter(FlexibleBooleanAdapter::class)
    val isLike: Boolean = false,
    
    @SerializedName("is_coin")
    @JsonAdapter(FlexibleBooleanAdapter::class)
    val isCoin: Boolean = false,
    
    @SerializedName("is_favorite")
    @JsonAdapter(FlexibleBooleanAdapter::class)
    val isFavorite: Boolean = false,

    @SerializedName(value = "epid", alternate = ["ep_id", "episode_id"])
    val epid: Long = 0,

    @SerializedName(value = "sid", alternate = ["season_id", "pgc_season_id"])
    val sid: Long = 0,

    @SerializedName("season_type")
    val seasonType: Int = 0,

    @SerializedName("is_ogv")
    @JsonAdapter(FlexibleBooleanAdapter::class)
    val isOgv: Boolean = false,

    @SerializedName(value = "redirect_url", alternate = ["uri", "link", "share_url"])
    val redirectUrl: String = "",

    @SerializedName("teenage_mode")
    val teenageMode: Int = 0,

    @SerializedName("progress")
    val historyProgress: Long = 0,
    val historyViewAt: Long = 0,
    val historyBadge: String = "",
    val historyBusiness: String = "",

    @SerializedName("rights")
    val rights: VideoRights? = null,

    @SerializedName("is_upower_exclusive")
    @JsonAdapter(FlexibleBooleanAdapter::class)
    val isUpowerExclusive: Boolean = false,

    @SerializedName("is_chargeable_season")
    @JsonAdapter(FlexibleBooleanAdapter::class)
    val isChargeableSeason: Boolean = false,

    @SerializedName("elec_arc_type")
    val elecArcType: Int = 0,

    @SerializedName("is_charging_arc")
    @JsonAdapter(FlexibleBooleanAdapter::class)
    val isChargingArc: Boolean = false,

    @SerializedName("privilege_type")
    val privilegeType: Int = 0,

    @SerializedName("elec_arc_badge")
    val elecArcBadge: String = ""
) : Serializable {
    private val _cachedIsChargingExclusive: Boolean by lazy {
        isUpowerExclusive || privilegeType > 0 || isChargingArc
                || elecArcType == 1 || elecArcBadge == "充电专属"
    }
    val isChargingExclusive: Boolean get() = _cachedIsChargingExclusive

    val isPortrait: Boolean
        get() = dimension?.isPortrait == true
    val coverUrl: String
        get() = pic.ifEmpty { cover }

    val durationValue: Long
        get() = when {
            duration > 0 -> duration
            length.isNotBlank() -> parseDuration(length)
            else -> 0
        }

    val viewCount: Long
        get() = stat?.view ?: play

    val danmakuCount: Long
        get() = stat?.danmaku ?: videoReview

    val authorName: String
        get() = owner?.name ?: ""

    private val _cachedPlaybackSeasonId: Long by lazy { computePlaybackSeasonId() }
    val playbackSeasonId: Long get() = _cachedPlaybackSeasonId

    private val _cachedPlaybackEpId: Long by lazy { computePlaybackEpId() }
    val playbackEpId: Long get() = _cachedPlaybackEpId

    private val _cachedIsPgc: Boolean by lazy { playbackEpId > 0L || playbackSeasonId > 0L }
    val isPgc: Boolean get() = _cachedIsPgc

    private fun computePlaybackSeasonId(): Long = when {
        sid <= 0L -> parseBangumiSeasonIdFromRedirectUrl()
        epid > 0L -> sid
        isOgv -> sid
        seasonType > 0 -> sid
        redirectUrl.contains("/bangumi/play/") -> sid
        else -> parseBangumiSeasonIdFromRedirectUrl()
    }

    private fun computePlaybackEpId(): Long = when {
        epid > 0L -> epid
        else -> parseBangumiEpIdFromRedirectUrl()
    }

    private fun parseDuration(value: String): Long {
        val parts = value.split(":").mapNotNull { it.toLongOrNull() }
        if (parts.isEmpty()) {
            return 0
        }
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            else -> parts[0]
        }
    }

    private fun parseBangumiSeasonIdFromRedirectUrl(): Long {
        val url = redirectUrl
        if (!url.contains("/bangumi/play/ss")) {
            return 0L
        }
        val seasonId = url.substringAfter("/bangumi/play/ss", "")
            .takeWhile { it.isDigit() }
        return seasonId.toLongOrNull() ?: 0L
    }

    private fun parseBangumiEpIdFromRedirectUrl(): Long {
        val url = redirectUrl
        if (!url.contains("/bangumi/play/ep")) {
            return 0L
        }
        val epId = url.substringAfter("/bangumi/play/ep", "")
            .takeWhile { it.isDigit() }
        return epId.toLongOrNull() ?: 0L
    }
}

data class Owner(
    @SerializedName("mid")
    val mid: Long = 0,
    
    @SerializedName("name")
    val name: String = "",
    
    @SerializedName("face")
    val face: String = "",

    @SerializedName("official_verify")
    val officialVerify: OfficialVerifySimple? = null
) : Serializable

data class Bangumi(
    @SerializedName("long_title")
    val longTitle: String = "",

    @SerializedName("cover")
    val cover: String = ""
) : Serializable

data class Stat(
    @SerializedName("aid")
    val aid: Long = 0,
    
    @SerializedName("view")
    val view: Long = 0,
    
    @SerializedName(value = "danmaku", alternate = ["dm", "danmakus"])
    var danmaku: Long = 0,
    
    @SerializedName("reply")
    val reply: Long = 0,
    
    @SerializedName("favorite")
    val favorite: Long = 0,
    
    @SerializedName("coin")
    val coin: Long = 0,
    
    @SerializedName("share")
    val share: Long = 0,
    
    @SerializedName("now_rank")
    val nowRank: Long = 0,
    
    @SerializedName("his_rank")
    val hisRank: Long = 0,
    
    @SerializedName("like")
    val like: Long = 0,
    
    @SerializedName("dislike")
    val dislike: Long = 0
) : Serializable

data class VideoRights(
    @SerializedName("elec")
    val elec: Int = 0,
    @SerializedName("autoplay")
    val autoplay: Int = 1,
    @SerializedName("pay")
    val pay: Int = 0,
    @SerializedName("ugc_pay")
    val ugcPay: Int = 0,
    @SerializedName("arc_pay")
    val arcPay: Int = 0
) : Serializable

data class Dimension(
    @SerializedName("width")
    val width: Int = 0,
    
    @SerializedName("height")
    val height: Int = 0,
    
    @SerializedName("rotate")
    val rotate: Int = 0
) : Serializable {
    val isPortrait: Boolean
        get() {
            if (width == 0 || height == 0) return false
            return when (rotate) {
                90, 270 -> width > height
                else -> height > width
            }
        }
}

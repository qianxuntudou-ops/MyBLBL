package com.tutu.myblbl.model.video.detail

import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.SerializedName
import com.tutu.myblbl.model.adapter.FlexibleBooleanAdapter
import com.tutu.myblbl.model.video.Owner
import com.tutu.myblbl.model.video.Stat
import com.tutu.myblbl.model.video.Dimension
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.model.video.VideoPvModel

data class VideoDetailModel(
    @SerializedName("View")
    val view: VideoView? = null,
    
    @SerializedName("Related")
    val related: List<VideoModel>? = null,
    
    @SerializedName(value = "tags", alternate = ["Tags"])
    val tags: List<Tag>? = null
)

data class VideoView(
    @SerializedName("aid")
    val aid: Long = 0,
    
    @SerializedName("bvid")
    val bvid: String = "",
    
    @SerializedName("videos")
    val videos: Int = 0,
    
    @SerializedName("tid")
    val tid: Int = 0,
    
    @SerializedName("tname")
    val tname: String = "",
    
    @SerializedName("copyright")
    val copyright: Int = 0,
    
    @SerializedName("pic")
    val pic: String = "",
    
    @SerializedName("title")
    val title: String = "",
    
    @SerializedName("pubdate")
    val pubDate: Long = 0,
    
    @SerializedName("ctime")
    val createTime: Long = 0,
    
    @SerializedName("desc")
    val desc: String = "",
    
    @SerializedName("state")
    val state: Int = 0,
    
    @SerializedName("duration")
    val duration: Long = 0,
    
    @SerializedName("owner")
    val owner: Owner? = null,
    
    @SerializedName("stat")
    val stat: Stat? = null,
    
    @SerializedName("dynamic")
    val dynamicText: String = "",
    
    @SerializedName("cid")
    val cid: Long = 0,
    
    @SerializedName("dimension")
    val dimension: Dimension? = null,
    
    @SerializedName("pages")
    val pages: List<VideoPvModel>? = null,
    
    @SerializedName("subtitle")
    val subtitle: Subtitle? = null,
    
    @SerializedName("staff")
    val staff: List<Staff>? = null,
    
    @SerializedName("redirect_url")
    val redirectUrl: String = "",
    
    @SerializedName("is_season_display")
    val isSeasonDisplay: Boolean = false,

    @SerializedName("ugc_season")
    val ugcSeason: UgcSeason? = null,

    @SerializedName("is_upower_exclusive")
    @JsonAdapter(FlexibleBooleanAdapter::class)
    val isUpowerExclusive: Boolean = false,

    @SerializedName("is_upower_play")
    @JsonAdapter(FlexibleBooleanAdapter::class)
    val isUpowerPlay: Boolean = false,

    @SerializedName("is_upower_preview")
    @JsonAdapter(FlexibleBooleanAdapter::class)
    val isUpowerPreview: Boolean = false,

    @SerializedName("is_chargeable_season")
    @JsonAdapter(FlexibleBooleanAdapter::class)
    val isChargeableSeason: Boolean = false,

    @SerializedName("is_charging_arc")
    @JsonAdapter(FlexibleBooleanAdapter::class)
    val isChargingArc: Boolean = false,

    @SerializedName("elec_arc_type")
    val elecArcType: Int = 0,

    @SerializedName("elec_arc_badge")
    val elecArcBadge: String = "",

    @SerializedName("privilege_type")
    val privilegeType: Int = 0,

    @SerializedName("steins_gate")
    @JsonAdapter(FlexibleBooleanAdapter::class)
    val steinsGate: Boolean = false
) {
    val isChargingExclusive: Boolean
        get() = isUpowerExclusive || privilegeType > 0 || isChargingArc
                || elecArcType == 1 || elecArcBadge == "充电专属"
}

data class Tag(
    @SerializedName("id")
    val id: Long = 0,
    @SerializedName("tag_id")
    val tagId: Long = 0,
    @SerializedName(value = "tag_name", alternate = ["name"])
    val tagName: String = "",

    @SerializedName("cover")
    val cover: String = "",

    @SerializedName("type")
    val type: Int = 0,

    @SerializedName("tag_type")
    val tagType: String = ""
)

data class Subtitle(
    @SerializedName("allow_submit")
    val allowSubmit: Boolean = false,
    
    @SerializedName("list")
    val list: List<SubtitleItem>? = null
)

data class SubtitleItem(
    @SerializedName("id")
    val id: Long = 0,
    
    @SerializedName("lan")
    val lan: String = "",
    
    @SerializedName("lan_doc")
    val lanDoc: String = "",
    
    @SerializedName("is_lock")
    val isLock: Boolean = false,
    
    @SerializedName("subtitle_url")
    val subtitleUrl: String = ""
)

data class Staff(
    @SerializedName("mid")
    val mid: Long = 0,
    
    @SerializedName("title")
    val title: String = "",
    
    @SerializedName("name")
    val name: String = "",
    
    @SerializedName("face")
    val face: String = "",
    
    @SerializedName("follower")
    val follower: Long = 0,
    
    @SerializedName("official")
    val official: Official? = null
)

data class Official(
    @SerializedName("role")
    val role: Int = 0,
    
    @SerializedName("title")
    val title: String = "",
    
    @SerializedName("desc")
    val desc: String = ""
)

data class UgcSeason(
    @SerializedName("id")
    val id: Long = 0,
    
    @SerializedName("title")
    val title: String = "",
    
    @SerializedName("cover")
    val cover: String = "",
    
    @SerializedName("mid")
    val mid: Long = 0,
    
    @SerializedName("intro")
    val intro: String = "",
    
    @SerializedName("sign_state")
    val signState: Int = 0,

    @SerializedName("attribute")
    val attribute: Long = 0,

    @SerializedName("ep_count")
    val epCount: Int = 0,

    @SerializedName("season_type")
    val seasonType: Int = 0,

    @SerializedName("is_pay_season")
    val isPaySeason: Boolean = false,

    @SerializedName("enable_vt")
    val enableVt: Int = 0,

    @SerializedName("sections")
    val sections: List<UgcSection>? = null
)

data class UgcSection(
    @SerializedName("season_id")
    val seasonId: Long = 0,

    @SerializedName(value = "section_id", alternate = ["id"])
    val sectionId: Long = 0,
    
    @SerializedName("title")
    val title: String = "",
    
    @SerializedName("type")
    val type: Int = 0,
    
    @SerializedName("episodes")
    val episodes: List<UgcEpisode>? = null
)

data class UgcEpisode(
    @SerializedName("season_id")
    val seasonId: Long = 0,
    
    @SerializedName("section_id")
    val sectionId: Long = 0,
    
    @SerializedName("id")
    val id: Long = 0,
    
    @SerializedName("aid")
    val aid: Long = 0,
    
    @SerializedName("cid")
    val cid: Long = 0,
    
    @SerializedName("title")
    val title: String = "",

    @SerializedName("arc")
    val arc: VideoModel? = null,
    
    @SerializedName("page")
    val pageInfo: VideoPvModel? = null,

    @SerializedName("pages")
    val pages: List<VideoPvModel>? = null,
    
    @SerializedName("bvid")
    val bvid: String = "",
    
    @SerializedName("cover")
    val cover: String = "",
    
    @SerializedName("duration")
    val duration: Long = 0
) {
    val displayAid: Long
        get() = aid.takeIf { it > 0L } ?: arc?.aid ?: 0L

    val displayBvid: String
        get() = bvid.ifBlank { arc?.bvid.orEmpty() }

    val displayCid: Long
        get() = cid.takeIf { it > 0L }
            ?: pageInfo?.cid
            ?: pages.orEmpty().firstOrNull()?.cid
            ?: arc?.cid
            ?: 0L

    val displayTitle: String
        get() = title.ifBlank { arc?.title.orEmpty() }

    val displayPage: Int
        get() = pageInfo?.page
            ?: pages.orEmpty().firstOrNull()?.page
            ?: 0

    val displayCover: String
        get() = cover.takeIf { it.isNotBlank() }
            ?: arc?.coverUrl
            ?: ""

    val displayDuration: Long
        get() = duration.takeIf { it > 0L }
            ?: pageInfo?.duration
            ?: arc?.durationValue
            ?: 0L
}

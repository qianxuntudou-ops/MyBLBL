package com.tutu.myblbl.model.recommend

import com.google.gson.annotations.SerializedName
import com.tutu.myblbl.model.video.Owner
import com.tutu.myblbl.model.video.Stat
import com.tutu.myblbl.model.video.VideoModel

data class AppFeedDataModel(
    @SerializedName("items")
    val items: List<AppFeedItem>? = null
)

data class AppFeedItem(
    @SerializedName("idx")
    val idx: Long = 0,
    @SerializedName("param")
    val param: String = "",
    @SerializedName("goto")
    val goto: String = "",
    @SerializedName("uri")
    val uri: String = "",
    @SerializedName("cover")
    val cover: String = "",
    @SerializedName("title")
    val title: String = "",
    @SerializedName("duration")
    val duration: Long = 0,
    @SerializedName("cover_left_text_1")
    val coverLeftText1: String = "",
    @SerializedName("cover_left_text_2")
    val coverLeftText2: String = "",
    @SerializedName("cover_left_text_3")
    val coverLeftText3: String = "",
    @SerializedName("desc")
    val desc: String = "",
    @SerializedName("ctime")
    val ctime: Long = 0,
    @SerializedName("pubdate")
    val pubdate: Long = 0,
    @SerializedName("args")
    val args: AppFeedArgs? = null,
    @SerializedName("player_args")
    val playerArgs: AppFeedPlayerArgs? = null,
    @SerializedName("three_point")
    val threePoint: Any? = null,
    @SerializedName("can_play")
    val canPlay: Int = 0
) {
    fun toVideoModel(): VideoModel? {
        if (goto != "av") return null

        // bvid: 优先从 uri 提取 (bilibili://video/BV1xxx)，其次 param 以 BV 开头
        val bvid = uri.substringAfterLast("/").takeIf { it.startsWith("BV") }
            ?: param.takeIf { it.startsWith("BV") }
            ?: ""

        // aid: 优先 playerArgs.aid，其次 args.aid，最后解析 param
        val aid = playerArgs?.aid
            ?: args?.aid
            ?: param.toLongOrNull()
            ?: 0L

        if (aid <= 0L && bvid.isBlank()) return null

        val cid = playerArgs?.cid ?: 0L
        val videoDuration = if (duration > 0) duration else playerArgs?.duration ?: 0L

        return VideoModel(
            aid = aid,
            bvid = bvid,
            title = title,
            pic = cover,
            desc = desc,
            duration = videoDuration,
            cid = cid,
            goto = goto,
            pubDate = pubdate.takeIf { it > 0 } ?: ctime.takeIf { it > 0 } ?: 0L,
            createTime = ctime.takeIf { it > 0 } ?: 0L,
            owner = Owner(
                mid = args?.upId ?: 0L,
                name = args?.upName ?: "",
                face = args?.upFace ?: ""
            ),
            stat = Stat(
                view = parseCountText(coverLeftText1),
                danmaku = parseCountText(coverLeftText2)
            ),
            redirectUrl = uri,
            typeName = args?.typeName ?: "",
            typeId = args?.typeId ?: 0
        )
    }

    private fun parseCountText(text: String): Long {
        if (text.isBlank()) return 0L
        return try {
            val trimmed = text.trim()
            when {
                trimmed.endsWith("亿") -> (trimmed.dropLast(1).toDouble() * 1_0000_0000).toLong()
                trimmed.endsWith("万") -> (trimmed.dropLast(1).toDouble() * 10000).toLong()
                else -> trimmed.replace(",", "").toLongOrNull() ?: 0L
            }
        } catch (_: NumberFormatException) {
            0L
        }
    }
}

data class AppFeedArgs(
    @SerializedName("up_id")
    val upId: Long = 0,
    @SerializedName("up_name")
    val upName: String = "",
    @SerializedName("up_face")
    val upFace: String = "",
    @SerializedName("rid")
    val typeId: Int = 0,
    @SerializedName("rname")
    val typeName: String = "",
    @SerializedName("aid")
    val aid: Long = 0
)

data class AppFeedPlayerArgs(
    @SerializedName("aid")
    val aid: Long = 0,
    @SerializedName("cid")
    val cid: Long = 0,
    @SerializedName("duration")
    val duration: Long = 0
)

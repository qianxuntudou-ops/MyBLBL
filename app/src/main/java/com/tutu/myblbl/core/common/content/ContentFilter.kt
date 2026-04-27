package com.tutu.myblbl.core.common.content

import android.content.Context
import com.tutu.myblbl.core.common.settings.AppSettingsDataStore
import com.tutu.myblbl.model.live.LiveRoomItem
import com.tutu.myblbl.model.search.SearchItemModel
import com.tutu.myblbl.model.video.VideoModel
import org.koin.mp.KoinPlatform

object ContentFilter {

    private const val KEY_MINOR_PROTECTION = "minor_protection"
    private const val KEY_BLOCKED_UP_NAMES = "blocked_up_names"
    private const val KEY_BLOCKED_VIDEO_KEYS = "blocked_video_keys"

    private val appSettings: AppSettingsDataStore get() = KoinPlatform.getKoin().get()

    private val VIDEO_BLOCKED_TYPE_NAMES = setOf(
        "ASMR",
        "asmr",
        "助眠",
        "擦边",
        "福利",
        "软色情",
        "丝袜",
        "黑丝",
        "白丝",
        "灰丝",
        "肉丝",
        "过膝袜",
        "连裤袜",
        "足控",
        "恋足",
        "美足",
        "恐怖",
        "惊悚",
        "灵异",
        "鬼故事",
        "SCP",
        "scp",
        "怪谈",
        "都市传说",
        "细思极恐",
        "血腥",
        "暴力",
        "猎奇",
        "ryona",
        "成人",
        "绅士",
        "里番",
        "黄游",
        "黄油",
        "工口",
        "媚宅",
        "卖肉",
        "百合",
        "耽美",
        "BL",
        "GL",
        "本子",
        "色系",
        "魅惑",
        "诱惑",
        "性感",
        "娇喘",
        "情趣",
        "酒店",
        "深夜食堂",
        "夜话",
        "瑜伽",
        "健身舞",
        "宅舞",
        "钢管舞",
        "椅子舞",
        "辣妹舞",
        "抖胸舞",
        "扭胯舞",
        "Twerking",
        "twerking",
        "韩舞",
        "女团舞",
        "女团",
        "轩子",
        "大摆锤",
        "翻跳",
        "热舞",
        "涩涩",
        "色色",
        "搞色",
        "搞黄色",
        "炼铜",
        "三年起步",
        "骨科",
        "R18",
        "r18",
        "R-18",
        "肉番",
        "里界",
        "NTR",
        "ntr",
        "触手",
        "后宫",
        "Galgame",
        "galgame",
        "恋爱模拟",
        "RPG",
        "黄油"
    )

    private val VIDEO_BLOCKED_TITLE_KEYWORDS = setOf(
        "ASMR",
        "asmr",
        "助眠",
        "擦边",
        "福利",
        "丝袜",
        "黑丝",
        "白丝",
        "灰丝",
        "肉丝",
        "过膝袜",
        "连裤袜",
        "吊带袜",
        "网袜",
        "厚黑",
        "丝足",
        "袜",
        "足控",
        "恋足",
        "美足",
        "舔耳",
        "娇喘",
        "诱惑",
        "魅惑",
        "性感",
        "卖肉",
        "本子",
        "工口",
        "里番",
        "绅士",
        "恐怖",
        "惊悚",
        "灵异",
        "鬼故事",
        "怪谈",
        "细思极恐",
        "血腥",
        "猎奇",
        "scp",
        "SCP",
        "ryona",
        "露骨",
        "情趣",
        "勾栏",
        "成人向",
        "少儿不宜",
        "未审核",
        "无删减",
        "未删减",
        "大尺度",
        "深夜",
        "撩人",
        "制服",
        "女仆装",
        "兔女郎",
        "旗袍",
        "短裙",
        "低胸",
        "深V",
        "爆衣",
        "乳摇",
        "福利图",
        "涩图",
        "搞黄",
        "开车",
        "飙车",
        "老司机",
        "自慰",
        "打胶",
        "手冲",
        "文爱",
        "裸足",
        "玉足",
        "白袜",
        " JK",
        "萝莉",
        "正太",
        "御姐",
        "少妇",
        "人妻",
        "熟女",
        "shake it",
        "shakeit",
        "touch me",
        "touchme",
        "body wave",
        "bodywave",
        "hip roll",
        "hiproll",
        "pole dance",
        "poledance",
        "lap dance",
        "lapdance",
        "booty shake",
        "bootyshake",
        "butt drop",
        "buttdrop",
        "twerk",
        "buss it",
        "bussit",
        "heels dance",
        "heelsdance",
        "chair dance",
        "chairdance",
        "floor work",
        "floorwork",
        "猫步轻俏",
        "猫步",
        "热舞",
        "辣舞",
        "艳舞",
        "钢管",
        "钢管舞",
        "椅子舞",
        "辣妹舞",
        "抖胸舞",
        "扭胯舞",
        "韩舞",
        "女团舞",
        "翻跳",
        "宅舞",
        "竖屏舞蹈",
        "竖屏热舞",
        "微胖",
        "丰满",
        "巨乳",
        "空姐",
        "美女",
        "穿搭",
        "包臀裙",
        "高跟鞋",
        "黑长直",
        "细高跟",
        "大长腿",
        "锁骨",
        "事业线",
        "蜜桃臀",
        "马甲线",
        "蚂蚁腰",
        "蝴蝶背",
        "比基尼",
        "bikini",
        "光腿",
        "泳装",
        "御萝",
        "涩涩",
        "色色",
        "搞色",
        "搞黄色",
        "炼铜",
        "三年起步",
        "骨科",
        "R18",
        "r18",
        "R-18",
        "18禁",
        "肉番",
        "里界",
        "NTR",
        "ntr",
        "触手",
        "后宫",
        "Galgame",
        "galgame",
        "黄油",
        "涩图",
        "搞色",
        "搞黄色",
        "搞涩",
        "搞涩涩",
        "涩涩的",
        "色色的",
        "色魔",
        "LSP",
        "lsp",
        "老色批",
        "搞颜色",
        "桃子",
        "水多",
        "奈子",
        "本子库",
        "次元社",
        "牛头人",
        "媚黑",
        "媚男",
        "媚女",
        "茶艺",
        "绿茶",
        "海王",
        "养鱼",
        "撩汉",
        "撩妹",
        "约吗",
        "面基",
        "磕CP",
        "磕到了",
        "虎狼之词",
        "打擦边",
        "擦边球",
        "懂的都懂",
        "我不说",
        "懂的来",
        "秒懂",
        "老粉都知道",
        "福利放送",
        "粉丝福利",
        "色情",
        "约炮",
        "援交",
        "潜规则",
        "一夜情",
        "偷拍",
        "走光",
        "无码",
        "泡妞",
        "彩礼",
        "出轨",
        "邪教",
        "降头",
        "巫术",
        "通灵",
        "法术",
        "抹胸",
        "舞蹈生",
        "眼镜妹",
        "合欢",
        "圣女",
        "瑜伽",
        "女神",
        "换丝",
        "穿丝"
    )

    private val LIVE_BLOCKED_AREAS = setOf(
        "娱乐",
        "交友",
        "电台",
        "ASMR",
        "聊天",
        "虚拟主播",
        "颜值",
        "舞见",
        "唱见",
        "助眠"
    )

    private val LIVE_BLOCKED_PARENT_AREAS = setOf(
        "娱乐"
    )

    private val BLOCKED_UP_NAMES = setOf(
        "布丁奶酱团子"
    )

    private val BLOCKED_TYPE_IDS = setOf(
        129  // 舞蹈
    )

    private val ALL_BLOCKED_KEYWORDS_LOWER = (VIDEO_BLOCKED_TYPE_NAMES + VIDEO_BLOCKED_TITLE_KEYWORDS)
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }

    private val BLOCKED_TYPE_NAMES_LOWER = VIDEO_BLOCKED_TYPE_NAMES
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .toSet()

    private val LIVE_BLOCKED_AREAS_LOWER = LIVE_BLOCKED_AREAS
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .toSet()

    private val LIVE_BLOCKED_PARENT_AREAS_LOWER = LIVE_BLOCKED_PARENT_AREAS
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .toSet()

    fun isMinorProtectionEnabled(context: Context): Boolean {
        return appSettings.getCachedString(KEY_MINOR_PROTECTION) != "关"
    }

    fun addBlockedUpName(context: Context, name: String) {
        val existing = appSettings.getCachedStringSet(KEY_BLOCKED_UP_NAMES).toMutableSet()
        existing.add(name)
        appSettings.putStringSetAsync(KEY_BLOCKED_UP_NAMES, existing)
    }

    fun addBlockedVideo(
        context: Context,
        aid: Long = 0,
        bvid: String = "",
        title: String = "",
        coverUrl: String = ""
    ) {
        val keys = buildVideoBlockKeys(aid, bvid, title, coverUrl)
        if (keys.isEmpty()) return
        val existing = appSettings.getCachedStringSet(KEY_BLOCKED_VIDEO_KEYS).toMutableSet()
        existing.addAll(keys)
        appSettings.putStringSetAsync(KEY_BLOCKED_VIDEO_KEYS, existing)
    }

    fun addBlockedVideo(context: Context, video: VideoModel) {
        addBlockedVideo(
            context = context,
            aid = video.aid,
            bvid = video.bvid,
            title = video.title,
            coverUrl = video.coverUrl
        )
    }

    fun removeBlockedUpName(context: Context, name: String) {
        val existing = appSettings.getCachedStringSet(KEY_BLOCKED_UP_NAMES).toMutableSet()
        existing.remove(name)
        appSettings.putStringSetAsync(KEY_BLOCKED_UP_NAMES, existing)
    }

    fun getBlockedUpNames(context: Context): Set<String> {
        return appSettings.getCachedStringSet(KEY_BLOCKED_UP_NAMES)
    }

    fun getBlockedVideoKeys(context: Context): Set<String> {
        return appSettings.getCachedStringSet(KEY_BLOCKED_VIDEO_KEYS)
    }

    private fun isUpNameBlocked(context: Context, name: String): Boolean {
        if (name.isEmpty()) return false
        val trimmed = name.trim()
        if (BLOCKED_UP_NAMES.any { it.equals(trimmed, ignoreCase = true) }) return true
        val dynamicList = getBlockedUpNames(context)
        return dynamicList.any { it.equals(trimmed, ignoreCase = true) }
    }

    private fun isVideoKeyBlocked(
        context: Context,
        aid: Long = 0,
        bvid: String = "",
        title: String = "",
        coverUrl: String = ""
    ): Boolean {
        val blockedKeys = getBlockedVideoKeys(context)
        if (blockedKeys.isEmpty()) return false
        return buildVideoBlockKeys(aid, bvid, title, coverUrl).any(blockedKeys::contains)
    }

    fun isVideoBlocked(
        context: Context,
        typeName: String?,
        title: String? = "",
        teenageMode: Int = 0,
        desc: String? = "",
        authorName: String? = "",
        aid: Long = 0,
        bvid: String = "",
        coverUrl: String = "",
        typeId: Int = 0
    ): Boolean {
        if (isVideoKeyBlocked(context, aid, bvid, title.orEmpty(), coverUrl)) {
            return true
        }
        if (teenageMode != 0) return true
        val safeAuthorName = authorName.orEmpty()
        if (safeAuthorName.isNotEmpty() && isUpNameBlocked(context, safeAuthorName)) {
            return true
        }
        if (!isMinorProtectionEnabled(context)) return false
        if (typeId in BLOCKED_TYPE_IDS) return true
        val trimmedTypeName = typeName.orEmpty().trim().lowercase()
        if (trimmedTypeName.isNotEmpty() && trimmedTypeName in BLOCKED_TYPE_NAMES_LOWER) {
            return true
        }
        val safeTitleLower = title.orEmpty().lowercase()
        val safeDescLower = desc.orEmpty().lowercase()
        if (safeTitleLower.isNotEmpty() && ALL_BLOCKED_KEYWORDS_LOWER.any { safeTitleLower.contains(it) }) {
            return true
        }
        if (safeDescLower.isNotEmpty() && ALL_BLOCKED_KEYWORDS_LOWER.any { safeDescLower.contains(it) }) {
            return true
        }
        return false
    }

    fun isLiveRoomBlocked(context: Context, areaName: String, parentAreaName: String, anchorName: String = "", title: String = ""): Boolean {
        if (anchorName.isNotEmpty() && isUpNameBlocked(context, anchorName)) {
            return true
        }
        if (!isMinorProtectionEnabled(context)) return false
        val areaLower = areaName.trim().lowercase()
        if (areaLower.isNotEmpty() && areaLower in LIVE_BLOCKED_AREAS_LOWER) {
            return true
        }
        val parentAreaLower = parentAreaName.trim().lowercase()
        if (parentAreaLower.isNotEmpty() && parentAreaLower in LIVE_BLOCKED_PARENT_AREAS_LOWER) {
            return true
        }
        if (title.isNotEmpty()) {
            val titleLower = title.lowercase()
            if (ALL_BLOCKED_KEYWORDS_LOWER.any { titleLower.contains(it) }) {
                return true
            }
        }
        return false
    }

    fun filterVideos(context: Context, videos: List<VideoModel>): List<VideoModel> {
        return videos.filter { video ->
            !isVideoBlocked(
                context = context,
                typeName = video.typeName,
                title = video.title,
                teenageMode = video.teenageMode,
                desc = video.desc,
                authorName = video.authorName,
                aid = video.aid,
                bvid = video.bvid,
                coverUrl = video.coverUrl,
                typeId = video.typeId
            )
        }
    }

    fun filterLiveRooms(context: Context, rooms: List<LiveRoomItem>): List<LiveRoomItem> {
        return rooms.filter { room ->
            !isLiveRoomBlocked(context, room.areaV2Name.ifEmpty { room.areaName }, room.parentAreaName, room.uname, room.title)
        }
    }

    fun isSearchItemBlocked(context: Context, item: SearchItemModel): Boolean {
        val authorName = item.author.ifBlank { item.uname }
        return isVideoBlocked(
            context = context,
            typeName = "",
            title = item.title,
            desc = item.desc,
            authorName = authorName,
            aid = item.aid,
            bvid = item.bvid,
            coverUrl = item.pic.ifBlank { item.cover }
        )
    }

    fun filterSearchItems(context: Context, items: List<SearchItemModel>): List<SearchItemModel> {
        return items.filter { !isSearchItemBlocked(context, it) }
    }

    private fun buildVideoBlockKeys(
        aid: Long = 0,
        bvid: String = "",
        title: String = "",
        coverUrl: String = ""
    ): Set<String> {
        val keys = linkedSetOf<String>()
        val normalizedBvid = normalizeVideoKeyPart(bvid)
        if (normalizedBvid.isNotEmpty()) {
            keys.add("bvid:$normalizedBvid")
        }
        if (aid > 0L) {
            keys.add("aid:$aid")
        }
        val normalizedTitle = normalizeVideoKeyPart(title)
        if (normalizedTitle.isNotEmpty()) {
            keys.add("title:$normalizedTitle|cover:${normalizeVideoKeyPart(coverUrl)}")
        }
        return keys
    }

    private fun normalizeVideoKeyPart(value: String): String {
        return value.trim().lowercase()
    }
}

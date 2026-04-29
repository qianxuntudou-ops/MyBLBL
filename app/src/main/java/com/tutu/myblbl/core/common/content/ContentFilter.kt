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
        "助眠",
        "擦边",
        "擦邊",
        "福利",
        "软色情",
        "軟色情",
        "成人",
        "色情",
        "里番",
        "裏番",
        "肉番",
        "黄游",
        "黃遊",
        "黄油",
        "黃油",
        "工口",
        "本子",
        "R18",
        "R-18",
        "18禁",
        "恐怖",
        "惊悚",
        "驚悚",
        "灵异",
        "靈異",
        "鬼故事",
        "怪谈",
        "怪談",
        "都市传说",
        "都市傳說",
        "细思极恐",
        "細思極恐",
        "血腥",
        "暴力",
        "猎奇",
        "獵奇",
        "ryona"
    )

    private val EXPLICIT_BLOCKED_KEYWORDS = setOf(
        "软色情",
        "軟色情",
        "色情",
        "成人向",
        "少儿不宜",
        "少兒不宜",
        "里番",
        "裏番",
        "肉番",
        "黄游",
        "黃遊",
        "黄油",
        "黃油",
        "工口",
        "本子",
        "本子库",
        "本子庫",
        "涩图",
        "澀圖",
        "福利图",
        "福利圖",
        "搞黄",
        "搞黃",
        "搞黄色",
        "搞黃色",
        "搞色",
        "搞涩",
        "搞澀",
        "搞涩涩",
        "搞澀澀",
        "搞颜色",
        "搞顏色",
        "涩涩",
        "澀澀",
        "色色",
        "涩涩的",
        "澀澀的",
        "色色的",
        "色魔",
        "LSP",
        "老色批",
        "约炮",
        "約炮",
        "援交",
        "一夜情",
        "文爱",
        "文愛",
        "自慰",
        "手冲",
        "手衝",
        "打胶",
        "打膠",
        "偷拍",
        "走光",
        "无码",
        "無碼",
        "潜规则",
        "潛規則",
        "炼铜",
        "煉銅",
        "三年起步",
        "JK",
        "萝莉",
        "蘿莉",
        "正太",
        "御姐",
        "少妇",
        "少婦",
        "人妻",
        "熟女",
        "制服",
        "女仆装",
        "女僕裝",
        "短裙",
        "低胸",
        "深V",
        "抹胸",
        "丝袜",
        "絲襪",
        "黑丝",
        "黑絲",
        "白丝",
        "白絲",
        "灰丝",
        "灰絲",
        "紫丝",
        "紫絲",
        "肉丝",
        "肉絲",
        "过膝袜",
        "過膝襪",
        "连裤袜",
        "連褲襪",
        "吊带袜",
        "吊帶襪",
        "网袜",
        "網襪",
        "白袜",
        "白襪",
        "油亮",
        "丝足",
        "絲足",
        "裸足",
        "玉足",
        "足控",
        "恋足",
        "戀足",
        "美足",
        "巨乳",
        "乳摇",
        "乳搖",
        "事业线",
        "事業線",
        "蜜桃臀",
        "翘臀",
        "翹臀",
        "比基尼",
        "瑜伽",
        "泳装",
        "泳裝",
        "包臀裙",
        "女团舞",
        "女團舞",
        "穿丝",
        "穿絲",
        "大摆锤",
        "大擺錘",
        "大长腿",
        "大長腿",
        "耽美",
        "合欢",
        "合歡",
        "换丝",
        "換絲",
        "尻",
        "奈子",
        "牛头人",
        "御萝",
        "御蘿",
        "绅士视频",
        "紳士視頻",
        "紳士影片",
        "过审",
        "過審",
        "R18",
        "R-18",
        "18禁",
        "血腥",
        "暴力",
        "猎奇",
        "獵奇",
        "ryona"
    )

    private val TITLE_BLOCKED_KEYWORDS = EXPLICIT_BLOCKED_KEYWORDS + setOf(
        "擦边",
        "擦邊",
        "擦边球",
        "擦邊球",
        "打擦边",
        "打擦邊",
        "福利放送",
        "粉丝福利",
        "粉絲福利",
        "虎狼之词",
        "虎狼之詞",
        "懂的都懂",
        "懂的来",
        "懂的來",
        "秒懂",
        "舔耳",
        "娇喘",
        "嬌喘",
        "大尺度",
        "露骨",
        "卖肉",
        "賣肉",
        "情趣",
        "诱惑",
        "誘惑",
        "魅惑",
        "撩人",
        "钢管舞",
        "鋼管舞",
        "椅子舞",
        "艳舞",
        "艷舞",
        "抖胸舞",
        "扭胯舞",
        "lap dance",
        "lapdance",
        "pole dance",
        "poledance",
        "chair dance",
        "chairdance",
        "booty shake",
        "bootyshake",
        "butt drop",
        "buttdrop",
        "twerk",
        "twerking",
        "恐怖",
        "惊悚",
        "驚悚",
        "灵异",
        "靈異",
        "鬼故事",
        "怪谈",
        "怪談",
        "细思极恐",
        "細思極恐",
        "SCP"
    )

    private val DESC_BLOCKED_KEYWORDS = EXPLICIT_BLOCKED_KEYWORDS + setOf(
        "擦边",
        "擦邊",
        "成人",
        "少儿不宜",
        "少兒不宜",
        "邪教"
    )

    private val TAG_BLOCKED_KEYWORDS = EXPLICIT_BLOCKED_KEYWORDS + setOf(
        "ASMR",
        "助眠",
        "擦边",
        "擦邊",
        "成人",
        "色情",
        "里番",
        "裏番",
        "肉番",
        "黄游",
        "黃遊",
        "黄油",
        "黃油",
        "工口",
        "血腥",
        "暴力",
        "猎奇",
        "獵奇",
        "恐怖",
        "惊悚",
        "驚悚",
        "性感",
        "诱惑",
        "誘惑"
    )

    private val CONTEXTUAL_SUBJECT_KEYWORDS = setOf(
        "兔女郎",
        "旗袍",
        "bikini",
        "舞蹈生",
        "竖屏舞蹈",
        "豎屏舞蹈",
        "竖屏热舞",
        "豎屏熱舞"
    )

    private val CONTEXTUAL_RISK_KEYWORDS = setOf(
        "擦边",
        "擦邊",
        "福利",
        "福利放送",
        "粉丝福利",
        "粉絲福利",
        "性感",
        "诱惑",
        "誘惑",
        "魅惑",
        "撩人",
        "大尺度",
        "露骨",
        "卖肉",
        "賣肉",
        "涩涩",
        "澀澀",
        "色色",
        "热舞",
        "熱舞",
        "辣舞",
        "艳舞",
        "艷舞",
        "钢管",
        "鋼管",
        "椅子舞",
        "抖胸舞",
        "扭胯舞",
        "懂的都懂",
        "秒懂",
        "虎狼之词",
        "虎狼之詞"
    )

    private val LIVE_BLOCKED_AREAS = setOf(
        "交友",
        "ASMR",
        "颜值",
        "顏值",
        "助眠"
    )

    private val LIVE_BLOCKED_PARENT_AREAS = emptySet<String>()

    private val BLOCKED_UP_NAMES = setOf(
        "布丁奶酱团子"
    )

    private val BLOCKED_TYPE_IDS = setOf(
        129  // 舞蹈
    )

    private val TITLE_BLOCKED_KEYWORDS_LOWER = normalizeKeywords(TITLE_BLOCKED_KEYWORDS)

    private val DESC_BLOCKED_KEYWORDS_LOWER = normalizeKeywords(DESC_BLOCKED_KEYWORDS)

    private val TAG_BLOCKED_KEYWORDS_LOWER = normalizeKeywords(TAG_BLOCKED_KEYWORDS)

    private val CONTEXTUAL_SUBJECT_KEYWORDS_LOWER = normalizeKeywords(CONTEXTUAL_SUBJECT_KEYWORDS)

    private val CONTEXTUAL_RISK_KEYWORDS_LOWER = normalizeKeywords(CONTEXTUAL_RISK_KEYWORDS)

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
        if (shouldBlockTitle(safeTitleLower)) {
            return true
        }
        if (shouldBlockDesc(safeDescLower)) {
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
            if (shouldBlockTitle(title.lowercase())) {
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

    fun isSearchKeywordBlocked(context: Context, keyword: String): Boolean {
        if (!isMinorProtectionEnabled(context)) return false
        val keywordLower = keyword.trim().lowercase()
        if (keywordLower.isEmpty()) return false
        return containsAny(keywordLower, TITLE_BLOCKED_KEYWORDS_LOWER)
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

    fun isBlockedByTags(context: Context, tags: List<com.tutu.myblbl.model.video.detail.Tag>?): Boolean {
        if (!isMinorProtectionEnabled(context)) return false
        if (tags.isNullOrEmpty()) return false
        return tags.any { tag ->
            val tagName = tag.tagName.trim().lowercase()
            tagName.isNotEmpty() && containsAny(tagName, TAG_BLOCKED_KEYWORDS_LOWER)
        }
    }

    private fun shouldBlockTitle(titleLower: String): Boolean {
        if (titleLower.isEmpty()) return false
        if (containsAny(titleLower, TITLE_BLOCKED_KEYWORDS_LOWER)) return true
        return containsContextualRisk(titleLower)
    }

    private fun shouldBlockDesc(descLower: String): Boolean {
        if (descLower.isEmpty()) return false
        if (containsAny(descLower, DESC_BLOCKED_KEYWORDS_LOWER)) return true
        return containsContextualRisk(descLower)
    }

    private fun containsContextualRisk(valueLower: String): Boolean {
        return containsAny(valueLower, CONTEXTUAL_SUBJECT_KEYWORDS_LOWER) &&
            containsAny(valueLower, CONTEXTUAL_RISK_KEYWORDS_LOWER)
    }

    private fun containsAny(valueLower: String, keywordsLower: Collection<String>): Boolean {
        return keywordsLower.any { valueLower.contains(it) }
    }

    private fun normalizeKeywords(keywords: Collection<String>): List<String> {
        return keywords
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()
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

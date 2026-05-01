package com.tutu.myblbl.feature.player.sponsor

data class SponsorSegment(
    val segment: List<Float> = emptyList(),
    val UUID: String = "",
    val category: String = "",
    val actionType: String = "skip",
    val locked: Int = 0,
    val votes: Int = 0,
    val videoDuration: Float = 0f
) {
    val startTimeMs: Long get() = ((segment.getOrNull(0) ?: 0f) * 1000).toLong()
    val endTimeMs: Long get() = ((segment.getOrNull(1) ?: 0f) * 1000).toLong()
    val isSkipType: Boolean get() = actionType == "skip"

    fun categoryName(): String = when (category) {
        CATEGORY_SPONSOR -> "恰饭片段"
        CATEGORY_INTRO -> "开场动画"
        CATEGORY_OUTRO -> "片尾动画"
        else -> category
    }

    fun categoryColor(): Long = when (category) {
        CATEGORY_SPONSOR -> 0xFFFFA500
        CATEGORY_INTRO -> 0xFF42A5F5
        CATEGORY_OUTRO -> 0xFFAB47BC
        else -> 0xFFFFA500
    }

    companion object {
        const val CATEGORY_SPONSOR = "sponsor"
        const val CATEGORY_INTRO = "intro"
        const val CATEGORY_OUTRO = "outro"
        val ALL_CATEGORIES = listOf(CATEGORY_SPONSOR, CATEGORY_INTRO, CATEGORY_OUTRO)
    }
}
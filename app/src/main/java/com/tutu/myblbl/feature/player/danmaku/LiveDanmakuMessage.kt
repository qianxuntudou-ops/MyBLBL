package com.tutu.myblbl.feature.player.danmaku

data class LiveDanmakuMessage(
    val type: Type,
    val content: String,
    val color: Int = 0xFFFFFF
) {
    enum class Type {
        DANMU,
        GIFT,
        SUPER_CHAT,
        WELCOME,
        GUARD_BUY
    }
}

package com.tutu.myblbl.model.proto

import java.io.Serializable

data class DmSmartFilterConfigProto(
    val cloudLevel: Int = 0,
    val cloudText: String = "",
    val cloudSwitch: Int = 0,
    val defaultLevel: Int = 0,
    val defaultEnabled: Boolean = false,
    val playerLevel: Int = 0,
    val playerEnabled: Boolean = false
) : Serializable {
    val resolvedLevel: Int
        get() = when {
            playerLevel > 0 -> playerLevel
            defaultLevel > 0 -> defaultLevel
            else -> cloudLevel
        }

    val resolvedEnabled: Boolean
        get() = when {
            playerLevel > 0 -> playerEnabled
            defaultLevel > 0 -> defaultEnabled
            cloudLevel > 0 -> cloudSwitch != 0
            else -> false
        }
}

data class DmWebViewReplyProto(
    val segmentDurationMs: Int = 0,
    val totalSegments: Int = 0,
    val totalCount: Long = 0L,
    val specialDanmakuUrls: List<String> = emptyList(),
    val smartFilterConfig: DmSmartFilterConfigProto = DmSmartFilterConfigProto()
) : Serializable

@file:Suppress("unused")

package com.tutu.myblbl.feature.player

import com.tutu.myblbl.model.video.quality.VideoCodecEnum

data class DashSegmentBase(
    val initialization: String,
    val indexRange: String
)

data class DashRepresentation(
    val id: Int,
    val mimeType: String,
    val codecs: String,
    val bandwidth: Long,
    val width: Int = 0,
    val height: Int = 0,
    val frameRate: String = "",
    val baseUrl: String,
    val backupUrls: List<String> = emptyList(),
    val segmentBase: DashSegmentBase? = null
)

data class DashRoute(
    val codec: VideoCodecEnum,
    val videoRepresentation: DashRepresentation,
    val audioRepresentation: DashRepresentation?,
    val videoUrls: List<String>,
    val audioUrls: List<String>,
    val durationMs: Long,
    val minBufferTimeMs: Long
)

data class DashRoutePlan(
    val qualityId: Int,
    val selectedAudioId: Int?,
    val routes: List<DashRoute>,
    val durationMs: Long,
    val minBufferTimeMs: Long
)

data class DashAudioSelection(
    val defaultAudioId: Int,
    val userLockedAudioId: Int?,
    val fallbackOrder: List<Int>
)

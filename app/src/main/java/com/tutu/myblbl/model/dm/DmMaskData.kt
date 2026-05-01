package com.tutu.myblbl.model.dm

import android.graphics.Path

data class DmMaskData(
    val fps: Int,
    val rawSegments: List<LazyMaskSegment>
)

data class LazyMaskSegment(
    val timeMs: Long,
    val startOffset: Int,
    val endOffset: Int,
    val rawData: ByteArray? = null
) {
    // 延迟解析的帧缓存
    @Volatile
    var cachedFrames: List<MaskFrame>? = null
}

data class MaskFrame(
    val paths: List<Path>
)

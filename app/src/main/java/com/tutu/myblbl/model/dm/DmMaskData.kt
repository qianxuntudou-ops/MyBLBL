package com.tutu.myblbl.model.dm

import android.graphics.Path

data class DmMaskData(
    val fps: Int,
    val segments: List<MaskSegment>
)

data class MaskSegment(
    val timeMs: Long,
    val frames: List<MaskFrame>
)

data class MaskFrame(
    val relativeTimeMs: Long,
    val paths: List<Path>
)
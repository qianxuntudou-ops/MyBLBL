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
    val paths: List<Path>,
    /**
     * 该帧对应 SVG 标定的宽度（像素）。webmask 协议下，横屏视频常见为 320，竖屏视频则可能
     * 是 180、240 等小一号尺寸。**0 表示未知**——回退到旧的 320×180 兼容路径。
     *
     * 渲染时需要按 `videoWidth / svgWidth, videoHeight / svgHeight` 缩放 path 坐标系，硬
     * 编码 320×180 会让竖屏视频严重错位。
     */
    val svgWidth: Int = 0,
    val svgHeight: Int = 0,
)

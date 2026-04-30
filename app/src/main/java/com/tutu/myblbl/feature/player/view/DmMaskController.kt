package com.tutu.myblbl.feature.player.view

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.kuaishou.akdanmaku.ui.DanmakuView
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.model.dm.DmMaskRepository
import com.tutu.myblbl.model.dm.MaskFrame

class DmMaskController(
    private val danmakuViewProvider: () -> DanmakuView?,
    private val specialOverlayProvider: () -> SpecialDanmakuOverlayView?,
    private var repository: DmMaskRepository
) {
    companion object {
        private const val TAG = "DmMaskController"
        private const val SVG_CANVAS_SIZE = 320
    }

    private var enabled = false
    private var currentCid: Long = 0L
    private var maskReady = false
    private var viewWidth: Int = 0
    private var viewHeight: Int = 0
    private var lastFrame: MaskFrame? = null
    private var maskBitmap: Bitmap? = null

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    fun setEnabled(enabled: Boolean) {
        if (this.enabled == enabled) return
        this.enabled = enabled
        if (!enabled) {
            clearMaskBitmap()
        } else if (maskReady) {
            lastFrame = null
        }
    }

    suspend fun loadMask(maskUrl: String, cid: Long, fps: Int): Boolean {
        currentCid = cid
        maskReady = false
        lastFrame = null

        val data = repository.downloadAndParse(maskUrl, cid, fps)
        maskReady = data != null
        if (!maskReady) {
            AppLog.d(TAG, "Mask load failed for cid=$cid")
        }
        return maskReady
    }

    fun onPositionChanged(positionMs: Long) {
        if (!enabled || !maskReady || currentCid <= 0L) return
        if (viewWidth <= 0 || viewHeight <= 0) return

        val frame = repository.queryFrame(currentCid, positionMs) ?: return

        if (frame === lastFrame) return
        lastFrame = frame

        updateMaskBitmap(frame.paths)
    }

    fun onViewSizeChanged(width: Int, height: Int) {
        if (viewWidth == width && viewHeight == height) return
        viewWidth = width
        viewHeight = height
        lastFrame = null
    }

    fun setRepository(repository: DmMaskRepository) {
        this.repository = repository
    }

    fun release() {
        currentCid = 0L
        maskReady = false
        lastFrame = null
        clearMaskBitmap()
    }

    private fun updateMaskBitmap(paths: List<Path>) {
        // SVG 原始坐标是 320x180，保持宽高比
        val svgWidth = SVG_CANVAS_SIZE
        val svgHeight = (SVG_CANVAS_SIZE * 9 / 16) // 320 * 9/16 = 180
        val canvas = Canvas()
        val smallBmp = Bitmap.createBitmap(svgWidth, svgHeight, Bitmap.Config.ARGB_8888)
        canvas.setBitmap(smallBmp)
        smallBmp.eraseColor(0)

        for (path in paths) {
            canvas.drawPath(path, fillPaint)
        }
        canvas.setBitmap(null)

        val scaled = Bitmap.createScaledBitmap(smallBmp, viewWidth, viewHeight, true)
        smallBmp.recycle()

        maskBitmap?.recycle()
        maskBitmap = scaled

        danmakuViewProvider()?.maskBitmap = scaled
        specialOverlayProvider()?.maskBitmap = scaled
    }

    private fun clearMaskBitmap() {
        maskBitmap?.recycle()
        maskBitmap = null
        danmakuViewProvider()?.maskBitmap = null
        specialOverlayProvider()?.maskBitmap = null
    }
}

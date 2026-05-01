package com.tutu.myblbl.feature.player.view

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import com.kuaishou.akdanmaku.ui.DanmakuView
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.model.dm.DmMaskRepository

class DmMaskController(
    private val danmakuViewProvider: () -> DanmakuView?,
    private val specialOverlayProvider: () -> SpecialDanmakuOverlayView?,
    private var repository: DmMaskRepository
) {
    companion object {
        private const val TAG = "DmMaskController"
        private const val SVG_W = 320
        private const val SVG_H = 180
    }

    private var enabled = false
    private var currentCid: Long = 0L
    private var maskReady = false
    private var viewWidth: Int = 0
    private var viewHeight: Int = 0

    // 帧跳过：用 (segIndex, frameIndex) 判断
    private var lastSegIndex: Int = -1
    private var lastFrameIndex: Int = -1
    private var diagCount = 0

    // 由外部注入播放器位置（精确到微秒级的 presentationTimeUs / 1000）
    var playerPositionProvider: (() -> Long)? = null

    // 复用渲染缓冲区
    private var renderBmp: Bitmap? = null
    private var renderCanvas: Canvas? = null

    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // Choreographer 帧回调，vsync 对齐
    private val frameCallback = Choreographer.FrameCallback {
        if (!enabled || !maskReady) return@FrameCallback
        val pos = playerPositionProvider?.invoke() ?: return@FrameCallback
        updateMask(pos)
    }

    // ---- 公开 API ----

    fun setEnabled(enabled: Boolean) {
        AppLog.d(TAG, "setEnabled: $enabled, maskReady=$maskReady")
        if (this.enabled == enabled) return
        this.enabled = enabled
        if (!enabled) {
            stopFrameCallback()
            clearMask()
        } else if (maskReady) {
            invalidate()
            startFrameCallback()
        }
    }

    suspend fun loadMask(maskUrl: String, cid: Long, fps: Int): Boolean {
        AppLog.d(TAG, "loadMask: cid=$cid, fps=$fps, enabled=$enabled")
        currentCid = cid
        maskReady = false
        invalidate()

        val data = repository.downloadAndParse(maskUrl, cid, fps)
        maskReady = data != null
        if (!maskReady) {
            AppLog.d(TAG, "Mask load failed for cid=$cid")
        } else {
            AppLog.d(TAG, "Mask loaded OK: segments=${data?.rawSegments?.size}")
            if (enabled) startFrameCallback()
        }
        return maskReady
    }

    /** 由 VideoPlayerProgressCoordinator 调用，更新 view 尺寸 */
    fun onViewSizeChanged(width: Int, height: Int) {
        if (viewWidth == width && viewHeight == height) return
        viewWidth = width
        viewHeight = height
        renderBmp?.recycle()
        renderBmp = null
        renderCanvas = null
        invalidate()
    }

    /** seek 时调用，强制下帧刷新 */
    fun onSeek() {
        invalidate()
    }

    fun onPositionChanged(positionMs: Long) {
        updateMask(positionMs)
    }

    fun setRepository(repository: DmMaskRepository) {
        this.repository = repository
    }

    fun release() {
        stopFrameCallback()
        currentCid = 0L
        maskReady = false
        invalidate()
        clearMask()
    }

    // ---- 内部实现 ----

    private fun invalidate() {
        lastSegIndex = -1
        lastFrameIndex = -1
    }

    private fun startFrameCallback() {
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopFrameCallback() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    /**
     * 每帧执行：查询 mask 帧并渲染。
     * 由 Choreographer vsync 驱动或由 onPositionChanged 手动调用。
     */
    private fun updateMask(positionMs: Long) {
        if (!enabled || !maskReady || currentCid <= 0L) return
        if (viewWidth <= 0 || viewHeight <= 0) return

        val result = repository.queryFrameWithIndex(currentCid, positionMs)
        if (result == null) {
            // 当前 segment 可能还没解析完，下一帧重试
            if (enabled) startFrameCallback()
            return
        }

        // 同帧跳过
        if (result.segIndex == lastSegIndex && result.frameIndex == lastFrameIndex) {
            if (enabled) startFrameCallback()
            return
        }
        lastSegIndex = result.segIndex
        lastFrameIndex = result.frameIndex

        // 诊断日志：视频时间戳 vs mask 帧时间戳
        if (diagCount < 30) {
            val segStartMs = result.segStartTimeMs
            val segDurMs = result.segDurationMs
            val framesInSeg = result.totalFrames
            val maskFrameTimeMs = if (framesInSeg > 0 && segDurMs > 0)
                segStartMs + result.frameIndex * segDurMs / framesInSeg else -1
            val diff = positionMs - maskFrameTimeMs
            AppLog.d(TAG, "sync: video=${positionMs}ms mask=${maskFrameTimeMs}ms diff=${diff}ms " +
                "seg=${result.segIndex}[$${result.frameIndex}/${framesInSeg}] segDur=${segDurMs}ms")
            diagCount++
        }

        val paths = result.frame.paths
        if (paths.isEmpty()) {
            if (enabled) startFrameCallback()
            return
        }

        renderMask(paths)

        // 注册下一帧回调
        if (enabled) startFrameCallback()
    }

    private fun renderMask(paths: List<Path>) {
        ensureRenderBuffer()
        val bmp = renderBmp ?: return
        val canvas = renderCanvas ?: return

        bmp.eraseColor(Color.WHITE)
        canvas.save()
        canvas.scale(viewWidth / SVG_W.toFloat(), viewHeight / SVG_H.toFloat())
        for (path in paths) {
            canvas.drawPath(path, clearPaint)
        }
        canvas.restore()

        danmakuViewProvider()?.maskBitmap = bmp
        specialOverlayProvider()?.maskBitmap = bmp
    }

    private fun ensureRenderBuffer() {
        val bmp = renderBmp
        if (bmp != null && bmp.width == viewWidth && bmp.height == viewHeight && !bmp.isRecycled) return
        renderBmp?.recycle()
        val newBmp = Bitmap.createBitmap(viewWidth, viewHeight, Bitmap.Config.ARGB_8888)
        renderBmp = newBmp
        renderCanvas = Canvas(newBmp)
    }

    private fun clearMask() {
        renderBmp?.recycle()
        renderBmp = null
        renderCanvas = null
        danmakuViewProvider()?.maskBitmap = null
        specialOverlayProvider()?.maskBitmap = null
        invalidate()
    }
}

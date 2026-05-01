package com.tutu.myblbl.feature.player.view

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.model.dm.DmMaskRepository

/**
 * 弹幕防挡蒙版控制器。
 *
 * 性能要点：
 *  - 蒙版 bitmap 使用 [Bitmap.Config.ALPHA_8]（仅 alpha 通道，比 ARGB_8888 节省 75% 内存与 GPU 上传带宽）。
 *  - 蒙版 path 渲染挪到后台 [HandlerThread]，主线程仅做查询/调度与引用切换。
 *  - 双缓冲：后台线程写其中一个 buffer，主线程展示另一个；渲染完成后切换引用，避免 GPU/CPU 竞争。
 *  - 主层 + 高级层共用一个 [DanmakuMaskHostLayout] 父容器，整张蒙版只在父容器做一次
 *    saveLayer + drawBitmap 合成，避免子 view 各自再开离屏图层。
 */
class DmMaskController(
    private val maskHostProvider: () -> DanmakuMaskHostLayout?,
    private var repository: DmMaskRepository
) {
    companion object {
        private const val TAG = "DmMaskController"
        private const val SVG_W = 320
        private const val SVG_H = 180
        private const val RENDER_THREAD_NAME = "DmMaskRender"

        /**
         * 一个 vsync 的近似时长（60Hz ≈ 16.67ms）。用作 mask 上屏路径的物理时延单位。
         */
        private const val VSYNC_INTERVAL_MS = 17L

        /**
         * **mask 比视频额外的上屏延迟**（用于 [frameCallback] 内 query 位置补偿）。
         *
         * 视频走 SurfaceView：ExoPlayer release → SurfaceView buffer → SurfaceFlinger → 显示器
         * （跳过应用层 View hierarchy，约 1 vsync）。
         *
         * mask 走普通 View（[DanmakuMaskHostLayout]）：
         *   frameCallback → 后台渲染 → swap maskBitmap → invalidate
         *   → 主线程下一 vsync traversal/onDraw      ← 多走 1 vsync
         *   → RenderThread 提交 → SurfaceFlinger 合成 ← 多走 1 vsync
         *   → 显示器
         *
         * 比视频多走"主线程 onDraw + RenderThread 合成"两个环节，约 **2 个 vsync ≈ 33ms**。
         * 把 query 位置往前推 33ms，让 mask 实际上屏时刻对应的视频 PTS 与视频屏上 PTS 相等。
         *
         * 调试参考：60ms 过大（提前 ~半帧）；17ms 过小（滞后 ~1 帧，用户能感知）；
         * 33ms 是物理推导值，预期最贴合。
         */
        private const val MASK_PIPELINE_DELAY_MS = 33L

        /**
         * STATE_READY 之后再等一段稳定窗口才放开 mask 渲染。READY 仅意味着"解码器有新数据"，
         * 第一帧画面实际渲染到屏幕上还需要 1–2 个 vsync，提前放开会让 mask 比视频早一帧。
         */
        private const val SEEK_READY_STABILIZE_MS = 80L

        /**
         * 极端场景兜底：长时间卡 buffering 时仍允许 mask 跟随播放位置，避免永远不显示。
         */
        private const val SEEK_RECOVER_HARD_TIMEOUT_MS = 1500L

        /** 每次 seek 后允许诊断的 sync 日志条数，便于排查不同步。 */
        private const val DIAG_LOG_LIMIT = 20
    }

    private var enabled = false
    private var currentCid: Long = 0L
    private var maskReady = false
    private var viewWidth: Int = 0
    private var viewHeight: Int = 0

    // 视频内容在 maskHost 坐标系中的实际显示矩形。AspectRatioFrameLayout 会按视频比例做
    // letterbox / pillarbox，mask 必须按这个矩形缩放和平移，否则人物轮廓与视频画面错位。
    private var videoLeft: Int = 0
    private var videoTop: Int = 0
    private var videoWidth: Int = 0
    private var videoHeight: Int = 0

    private var isPlaying: Boolean = false
    private var playbackReady: Boolean = false

    /** 当前播放速度，用于 anchor 推算时按速度缩放时间差。由 [setPlaybackSpeed] 推送。 */
    @Volatile
    private var playbackSpeed: Float = 1.0f

    /**
     * 视频帧锚点：ExoPlayer 的 [androidx.media3.exoplayer.video.VideoFrameMetadataListener]
     * 在每一帧将要 render 到 surface 时回调，告诉我们：
     *  - [anchorPresentationTimeUs]：这一帧的 PTS（微秒）
     *  - [anchorReleaseTimeNs]：ExoPlayer 决定的 wall-clock release 时刻（纳秒，[System.nanoTime] 系）
     *
     * 有锚点后，任意 wall-clock t 时刻视频显示的 PTS（毫秒）= ptsUs/1000 + (t - releaseNs)/1e6 * speed。
     * 用此公式可精确推算"mask 上屏时刻视频对应的 PTS"，不再依赖经验型固定 lookahead。
     */
    @Volatile
    private var hasVideoAnchor: Boolean = false
    @Volatile
    private var anchorPresentationTimeUs: Long = 0L
    @Volatile
    private var anchorReleaseTimeNs: Long = 0L

    /**
     * 实测的 mask 上屏总管道延迟（纳秒）：从我们 invalidate maskHost 到 mask 实际显示在屏幕上。
     *
     * 由 [onMaskFrameMetrics] 持续推送实测值，做 EMA（指数滑动平均）：
     *   - 首次：直接采用实测值
     *   - 后续：α=1/8 的 EMA，对单帧抖动鲁棒，但能跟上长期趋势变化（如设备发热降频）
     *
     * 默认值 = [MASK_PIPELINE_DELAY_MS] 的纳秒形式，作为 anchor / metrics 都未到达时的回退。
     */
    @Volatile
    private var measuredPipelineDelayNs: Long = MASK_PIPELINE_DELAY_MS * 1_000_000L

    /**
     * seek 状态机：
     *  - [awaitingSeekReady]：seek 已触发，但视频尚未呈现"新位置首帧"。期间 mask 保持清空。
     *  - [seekReadyAt]：进入 STATE_READY 后再加 [SEEK_READY_STABILIZE_MS] 的解封时刻。
     *  - [seekHardDeadlineMs]：极端兜底，超过该时刻无论是否 READY 都解封，避免永久不显示。
     */
    private var awaitingSeekReady: Boolean = false
    private var seekReadyAt: Long = 0L
    private var seekHardDeadlineMs: Long = 0L

    // 帧跳过：用 (segIndex, frameIndex) 判断
    private var lastSegIndex: Int = -1
    private var lastFrameIndex: Int = -1
    private var diagCount = 0

    var playerPositionProvider: (() -> Long)? = null

    // 双缓冲：A/B 交替使用，避免 GPU 上传 race
    private var bufferA: Bitmap? = null
    private var bufferB: Bitmap? = null
    @Volatile
    private var displayingBuffer: Bitmap? = null
    @Volatile
    private var renderingInFlight: Boolean = false

    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    /** 用 SRC 模式一次性把 video bounds 内填成 alpha=255（黑边区域保持 alpha=0）。 */
    private val videoFillPaint = Paint().apply {
        color = Color.BLACK
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val renderThread: HandlerThread = HandlerThread(RENDER_THREAD_NAME).apply { start() }
    private val renderHandler = Handler(renderThread.looper)

    @Volatile
    private var disposed: Boolean = false

    /** post 任务到后台渲染线程，线程已 dispose 则直接同步执行（避免 dead thread 警告）。 */
    private fun postToRender(action: () -> Unit) {
        if (!disposed && renderThread.isAlive) {
            renderHandler.post(action)
        } else {
            action()
        }
    }

    private val frameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        if (!enabled || !maskReady) return@FrameCallback
        // 严格时间戳对齐——和 ExoPlayer 的音视频对齐共用同一套机制：
        //
        // 1. ExoPlayer 通过 [VideoFrameMetadataListener] 给我们 (PTS_us, release_ns) 锚点：
        //      "PTS=ptsUs 这一帧将在 wall_clock=releaseNs 时刻 release 到 surface"
        //    SurfaceFlinger 在下一个 vsync 把它合成上屏。
        //
        // 2. 我们的 mask 这次 frameCallback 触发 → 后台渲染 → 应用层 traversal → RenderThread
        //    → SurfaceFlinger，总耗时 = [measuredPipelineDelayNs]（由 [onMaskFrameMetrics] EMA 实测）。
        //    所以 mask 实际上屏 wall-clock 时刻 = frameTimeNanos + measuredPipelineDelayNs。
        //
        // 3. 在 mask 上屏那一刻，视频显示的 PTS（毫秒）：
        //      = anchorPtsUs/1000 + (maskOnScreenNs - anchorReleaseNs) / 1e6 * playbackSpeed
        //    用这个 PTS query mask 帧 → mask 与视频在同一时刻显示同一 PTS 的内容，**严格对齐**。
        //
        // 暂停时 master clock 冻结，按当前位置严格渲染（无 lookahead，避免暂停瞬间出现"超前"轮廓）。
        val pos = if (!isPlaying) {
            playerPositionProvider?.invoke() ?: return@FrameCallback
        } else if (hasVideoAnchor) {
            val maskOnScreenNs = frameTimeNanos + measuredPipelineDelayNs
            val deltaNs = maskOnScreenNs - anchorReleaseTimeNs
            // 时间差按当前播放速度缩放——倍速播放时视频时间走得更快。
            val deltaScaledNs = (deltaNs * playbackSpeed).toLong()
            val ptsAtMaskNs = anchorPresentationTimeUs * 1_000L + deltaScaledNs
            (ptsAtMaskNs / 1_000_000L).coerceAtLeast(0L)
        } else {
            // 锚点尚未到达（首帧前）。回退到旧的"vsync + 经验延迟"估计，让 mask 在视频
            // 第一帧之前就能基本对齐；anchor 一到达就切到精确推算。
            val rawPos = playerPositionProvider?.invoke() ?: return@FrameCallback
            val nowNanos = System.nanoTime()
            val toVsyncMs = ((frameTimeNanos - nowNanos) / 1_000_000L)
                .coerceIn(0L, VSYNC_INTERVAL_MS)
            rawPos + toVsyncMs + MASK_PIPELINE_DELAY_MS
        }
        scheduleMaskUpdate(pos)
    }

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

    fun onViewSizeChanged(width: Int, height: Int) {
        if (viewWidth == width && viewHeight == height) return
        viewWidth = width
        viewHeight = height
        recycleBuffersAsync()
        invalidate()
    }

    /**
     * 视频内容在 maskHost 中的实际显示矩形（letterbox 后）。每次视频尺寸 / resize mode /
     * 容器尺寸变化都要更新；不更新会导致 mask 缩放错位，看起来"漂"。
     */
    fun setVideoBounds(left: Int, top: Int, width: Int, height: Int) {
        if (videoLeft == left && videoTop == top &&
            videoWidth == width && videoHeight == height
        ) {
            return
        }
        videoLeft = left
        videoTop = top
        videoWidth = width
        videoHeight = height
        invalidate()
    }

    fun setPlaying(playing: Boolean) {
        if (isPlaying == playing) return
        isPlaying = playing
        // 切换播放/暂停状态时，下一帧立刻按新规则重渲染（暂停取消 lookahead）。
        invalidate()
    }

    /**
     * 推送当前播放速度（来自 ExoPlayer.PlaybackParameters.speed）。
     * anchor 推算时按速度缩放时间差：position(t) = anchor.pts + (t - anchor.release) * speed。
     */
    fun setPlaybackSpeed(speed: Float) {
        if (speed.isFinite() && speed > 0f) {
            playbackSpeed = speed
        }
    }

    /**
     * 由 ExoPlayer 的 [androidx.media3.exoplayer.video.VideoFrameMetadataListener] 在每一视频帧
     * 即将 render 时回调推送。给我们一个精确的"PTS ↔ wall clock"锚点，用于 [frameCallback]
     * 内严格推算"mask 上屏时刻视频显示的 PTS"。
     *
     * 注意：
     *  - 此方法在 ExoPlayer 内部 playback thread 调用，[anchor*] 三个字段都是 @Volatile，
     *    主线程读 / playback 线程写无锁安全。
     *  - 视频每秒 30~60 次回调，仅写 3 个字段，开销 < 1µs，可忽略。
     */
    fun onVideoFrameAnchor(presentationTimeUs: Long, releaseTimeNs: Long) {
        anchorPresentationTimeUs = presentationTimeUs
        anchorReleaseTimeNs = releaseTimeNs
        hasVideoAnchor = true
    }

    /**
     * 由 [android.view.Window.addOnFrameMetricsAvailableListener] (API 24+) 持续推送，给出
     * 上一帧 mask 实际经历的应用层渲染管道延迟（纳秒）—— 包括 traversal/draw/sync/issue/swap。
     * 我们再加一个 SurfaceFlinger vsync 作为合成 + 显示器延迟，得到总上屏延迟，做 EMA 平滑后
     * 用于 [frameCallback] 的 query 位置补偿。
     *
     * 这样 mask 上屏延迟从"经验常量"变成"实测自适应值"——设备发热降频、复杂帧 GC、不同
     * 屏幕刷新率等场景都能自动跟上。
     */
    fun onMaskFrameMetrics(applicationDelayNs: Long) {
        if (applicationDelayNs <= 0L) return
        // 应用层延迟 + 1 个 SurfaceFlinger vsync ≈ mask 总上屏延迟
        val total = applicationDelayNs + VSYNC_INTERVAL_MS * 1_000_000L
        val current = measuredPipelineDelayNs
        // EMA：α=1/8，约 8 帧滑动平均；对单帧 jank 鲁棒，又能跟上长期趋势。
        measuredPipelineDelayNs = (current * 7 + total) / 8
    }

    /**
     * 由 [androidx.media3.common.Player.Listener.onPlaybackStateChanged] 调用；
     * 当 state 变为 READY 时启动稳定窗口，到点后才放开 seek 阻塞。
     */
    fun setPlaybackReady(ready: Boolean) {
        if (playbackReady == ready) return
        playbackReady = ready
        if (ready) {
            if (awaitingSeekReady && seekReadyAt == 0L) {
                seekReadyAt = SystemClock.elapsedRealtime() + SEEK_READY_STABILIZE_MS
            }
        } else {
            // 重新进入 BUFFERING（例如连续 seek 中），稳定窗口失效，等下一次 READY 重新计算。
            seekReadyAt = 0L
        }
    }

    fun onSeek() {
        awaitingSeekReady = true
        seekReadyAt = if (playbackReady) {
            // seek 时若 player 已是 READY（数据已缓冲），直接进入稳定窗口；否则等 READY 再算。
            SystemClock.elapsedRealtime() + SEEK_READY_STABILIZE_MS
        } else {
            0L
        }
        seekHardDeadlineMs = SystemClock.elapsedRealtime() + SEEK_RECOVER_HARD_TIMEOUT_MS
        diagCount = 0
        invalidate()
        clearMask()
    }

    /**
     * 外部主动 push 当前播放位置（[MyPlayerView.syncDanmakuPosition] 在播放期间高频调用）。
     *
     * 这条路径没有 `frameTimeNanos` 上下文，但物理时延仍是一样的——mask 比视频多走的两个
     * vsync。用与 [frameCallback] 相同的 [MASK_PIPELINE_DELAY_MS] 补偿，避免两条路径写不同帧。
     */
    fun onPositionChanged(positionMs: Long) {
        val pos = if (isPlaying) positionMs + MASK_PIPELINE_DELAY_MS else positionMs
        scheduleMaskUpdate(pos)
    }

    fun setRepository(repository: DmMaskRepository) {
        this.repository = repository
    }

    /**
     * 暂停 / 重置当前视频对应的蒙版渲染状态（停帧回调、清屏、回收 buffer）。
     *
     * 注意：本方法**可重入**，每次切视频或切清晰度时都会被外部 `releaseDmMask()` 调用，
     * 因此**不能销毁**后台 [renderThread]，否则下一次 [loadMask]/[onViewSizeChanged] 会
     * 因为 looper 已死触发 `IllegalStateException`。线程销毁请使用 [dispose]。
     */
    fun release() {
        stopFrameCallback()
        currentCid = 0L
        maskReady = false
        invalidate()
        clearMask()
        recycleBuffersAsync()
    }

    /**
     * View 真正销毁时调用，彻底回收后台线程。调用后 controller 不应再被使用。
     */
    fun dispose() {
        if (disposed) return
        release()
        disposed = true
        runCatching { renderThread.quitSafely() }
    }

    // ---- 内部实现 ----

    private fun invalidate() {
        lastSegIndex = -1
        lastFrameIndex = -1
    }

    /**
     * seek 状态机：尚未呈现"新位置首帧"前返回 true，期间 frameCallback 跳过 mask 渲染。
     *
     * 解封条件（任一满足）：
     *  1. 进入 STATE_READY 且过了 [SEEK_READY_STABILIZE_MS] 稳定窗口 —— 视频帧已实际显示。
     *  2. 触发 [SEEK_RECOVER_HARD_TIMEOUT_MS] 兜底超时 —— 防止长时间 buffering 时 mask 永远不出。
     */
    private fun shouldSkipForSeek(): Boolean {
        if (!awaitingSeekReady) return false
        val now = SystemClock.elapsedRealtime()
        if (now >= seekHardDeadlineMs) {
            awaitingSeekReady = false
            return false
        }
        if (seekReadyAt > 0L && now >= seekReadyAt) {
            awaitingSeekReady = false
            return false
        }
        return true
    }

    private fun startFrameCallback() {
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopFrameCallback() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    /**
     * 主线程：根据当前位置查询 mask 帧，命中新帧则丢给后台线程渲染。
     */
    private fun scheduleMaskUpdate(positionMs: Long) {
        if (!enabled || !maskReady || currentCid <= 0L) {
            if (enabled) startFrameCallback()
            return
        }
        if (viewWidth <= 0 || viewHeight <= 0) {
            if (enabled) startFrameCallback()
            return
        }
        // seek 后视频解码追上之前不绘制，避免 mask 跳到新位置而视频还停在旧位置造成的错位。
        if (shouldSkipForSeek()) {
            if (displayingBuffer != null) clearMask()
            if (enabled) startFrameCallback()
            return
        }
        // 没拿到 video bounds（首帧前 / 视频尚未 measure）时按 view 全屏回退，避免完全不工作。
        val vL: Int
        val vT: Int
        val vW: Int
        val vH: Int
        if (videoWidth > 0 && videoHeight > 0) {
            vL = videoLeft
            vT = videoTop
            vW = videoWidth
            vH = videoHeight
        } else {
            vL = 0
            vT = 0
            vW = viewWidth
            vH = viewHeight
        }

        val result = repository.queryFrameWithIndex(currentCid, positionMs)
        if (result == null) {
            if (enabled) startFrameCallback()
            return
        }

        if (result.segIndex == lastSegIndex && result.frameIndex == lastFrameIndex) {
            if (enabled) startFrameCallback()
            return
        }
        lastSegIndex = result.segIndex
        lastFrameIndex = result.frameIndex

        if (diagCount < DIAG_LOG_LIMIT) {
            val segStartMs = result.segStartTimeMs
            val segDurMs = result.segDurationMs
            val framesInSeg = result.totalFrames
            val maskFrameTimeMs = if (framesInSeg > 0 && segDurMs > 0)
                segStartMs + result.frameIndex * segDurMs / framesInSeg else -1
            val diff = positionMs - maskFrameTimeMs
            AppLog.d(
                TAG,
                "sync: video=${positionMs}ms mask=${maskFrameTimeMs}ms diff=${diff}ms " +
                    "seg=${result.segIndex}[${result.frameIndex}/${framesInSeg}] " +
                    "videoBounds=($vL,$vT,${vW}x$vH) playing=$isPlaying"
            )
            diagCount++
        }

        val paths = result.frame.paths
        if (paths.isEmpty()) {
            if (enabled) startFrameCallback()
            return
        }

        if (renderingInFlight) {
            // 后台还在渲染上一帧，跳过这次以免堆积；下一个 vsync 会再尝试。
            if (enabled) startFrameCallback()
            return
        }

        if (!ensureBuffers()) {
            if (enabled) startFrameCallback()
            return
        }
        val targetBmp = pickBackBuffer() ?: run {
            if (enabled) startFrameCallback()
            return
        }
        val w = viewWidth
        val h = viewHeight
        val expectedCid = currentCid

        renderingInFlight = true
        postToRender {
            renderOnBackground(targetBmp, paths, w, h, vL, vT, vW, vH, expectedCid)
        }

        if (enabled) startFrameCallback()
    }

    private fun renderOnBackground(
        targetBmp: Bitmap,
        paths: List<Path>,
        w: Int,
        h: Int,
        videoL: Int,
        videoT: Int,
        videoW: Int,
        videoH: Int,
        expectedCid: Long
    ) {
        // 视图尺寸/cid 在等待期间被改了 → 丢弃这次结果。
        if (w != viewWidth || h != viewHeight || expectedCid != currentCid || !enabled) {
            renderingInFlight = false
            return
        }
        if (targetBmp.isRecycled || videoW <= 0 || videoH <= 0) {
            renderingInFlight = false
            return
        }

        val started = SystemClock.elapsedRealtime()
        try {
            val canvas = Canvas(targetBmp)
            // 整张 bitmap 先清成 alpha=0：letterbox 黑边区域 alpha=0 不会擦弹幕。
            targetBmp.eraseColor(Color.TRANSPARENT)
            canvas.save()
            // 视频显示矩形内填充 alpha=255 → 默认不显示弹幕。
            canvas.translate(videoL.toFloat(), videoT.toFloat())
            canvas.drawRect(0f, 0f, videoW.toFloat(), videoH.toFloat(), videoFillPaint)
            // 按视频实际显示矩形把 path 坐标系映射上去（webmask 协议是 SVG_W × SVG_H 标定）。
            canvas.scale(videoW / SVG_W.toFloat(), videoH / SVG_H.toFloat())
            for (path in paths) {
                canvas.drawPath(path, clearPaint)
            }
            canvas.restore()
            // 提示框架尽早把纹理上传到 GPU，避免主线程 onDraw 时再触发上传卡顿。
            targetBmp.prepareToDraw()
        } catch (t: Throwable) {
            AppLog.w(TAG, "render mask failed: ${t.message}")
            renderingInFlight = false
            return
        }

        val cost = SystemClock.elapsedRealtime() - started
        mainHandler.post {
            renderingInFlight = false
            if (!enabled || !maskReady || expectedCid != currentCid) return@post
            if (targetBmp.isRecycled) return@post
            if (w != viewWidth || h != viewHeight) return@post
            displayingBuffer = targetBmp
            maskHostProvider()?.let { host ->
                host.maskBitmap = targetBmp
                host.invalidate()
            }
            if (cost > 16) {
                AppLog.d(TAG, "mask render cost ${cost}ms (paths=${paths.size})")
            }
        }
    }

    private fun ensureBuffers(): Boolean {
        val w = viewWidth
        val h = viewHeight
        if (w <= 0 || h <= 0) return false
        val a = bufferA
        val b = bufferB
        val ok = a != null && b != null &&
            !a.isRecycled && !b.isRecycled &&
            a.width == w && a.height == h &&
            b.width == w && b.height == h
        if (ok) return true
        recycleBuffersAsync()
        return try {
            bufferA = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
            bufferB = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
            displayingBuffer = null
            true
        } catch (oom: OutOfMemoryError) {
            AppLog.w(TAG, "createBitmap OOM: ${w}x${h}")
            bufferA = null
            bufferB = null
            false
        }
    }

    private fun pickBackBuffer(): Bitmap? {
        val displaying = displayingBuffer
        val a = bufferA ?: return null
        val b = bufferB ?: return a
        return if (displaying === a) b else a
    }

    private fun recycleBuffersAsync() {
        val a = bufferA
        val b = bufferB
        bufferA = null
        bufferB = null
        displayingBuffer = null
        // 在后台线程回收，避免阻塞主线程；同时确保不会有正在渲染的引用还在被使用。
        // 线程已 dispose 则直接同步回收，否则会触发 dead thread 警告。
        postToRender {
            a?.takeIf { !it.isRecycled }?.recycle()
            b?.takeIf { !it.isRecycled }?.recycle()
        }
    }

    private fun clearMask() {
        displayingBuffer = null
        maskHostProvider()?.let { host ->
            host.maskBitmap = null
            host.invalidate()
        }
        invalidate()
    }
}

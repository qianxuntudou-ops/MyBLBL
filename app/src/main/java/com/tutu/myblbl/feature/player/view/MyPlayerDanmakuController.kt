package com.tutu.myblbl.feature.player.view

import android.content.Context
import android.graphics.Color
import android.os.SystemClock
import com.kuaishou.akdanmaku.DanmakuConfig
import com.kuaishou.akdanmaku.data.DanmakuItemData
import com.kuaishou.akdanmaku.data.DanmakuVipGradientStyle
import com.kuaishou.akdanmaku.ecs.component.filter.DanmakuDataFilter
import com.kuaishou.akdanmaku.ecs.component.filter.TypeFilter
import com.kuaishou.akdanmaku.render.SimpleRenderer
import com.kuaishou.akdanmaku.ui.DanmakuPlayer
import com.kuaishou.akdanmaku.ui.DanmakuView
import com.tutu.myblbl.feature.player.PlaybackStartupTrace
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.model.dm.DmModel
import com.tutu.myblbl.core.common.ext.isVipColorfulDanmakuAllowed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max

/**
 * Owns danmaku-specific state so MyPlayerView only coordinates player UI and gestures.
 */
class MyPlayerDanmakuController(
    private val context: Context,
    private val danmakuViewProvider: () -> DanmakuView?
) {

    companion object {
        private const val TAG = "DanmakuCtrl"
        private const val MERGE_DUPLICATE_WINDOW_MS = 15_000
        private const val MERGE_DUPLICATE_MIN_COUNT = 2
        private const val MAX_SYNC_DRIFT_MS = 1200L
        private const val COLORFUL_VIP_GRADIENT = 0xEA61
        private const val SEEK_DEDUP_WINDOW_MS = 300L
        private const val SEEK_DEDUP_POSITION_TOLERANCE_MS = 80L
        private const val SMART_FILTER_LEVEL_OFF = 0
        private const val SMART_FILTER_LEVEL_MAX = 10
        private const val LIVE_THROTTLE_WINDOW_MS = 100L
        private const val LIVE_THROTTLE_MAX_ITEMS = 30
        private const val LIVE_MERGE_BUFFER_MS = 800L
        private const val LIVE_DENSITY_TRACK_MS = 5000L
    }

    data class SettingsSnapshot(
        val enabled: Boolean,
        val showAdvancedDanmaku: Boolean,
        val alpha: Float,
        val textSize: Int,
        val speed: Int,
        val screenArea: Int,
        val allowTop: Boolean,
        val allowBottom: Boolean,
        val smartFilterLevel: Int,
        val mergeDuplicate: Boolean
    )

    private var danmakuPlayer: DanmakuPlayer? = null
    private var danmakuConfig = DanmakuConfig(dataFilter = listOf(TypeFilter()))
    private var danmakuData: List<DanmakuItemData> = emptyList()
    private var rawDanmakuData: List<DmModel> = emptyList()
    private var danmakuPositionMs: Long = 0L
    private var isDanmakuStarted = false
    private var isDanmakuPaused = false
    private var liveEngineStarted = false
    private var liveThrottleWindowStart = 0L
    private var liveThrottleCount = 0
    private val liveMergeBuffer = mutableMapOf<MergeDuplicateKey, LiveMergeEntry>()
    private val liveSentTimestamps = ArrayDeque<Long>()
    private var liveFlushJob: Job? = null
    private var liveDanmakuIdCounter = 0L
    private var mergeDuplicate = true
    private var screenPart = 1.0f
    private var smartFilterLevel = SMART_FILTER_LEVEL_OFF
    private var lastSettingsSnapshot: SettingsSnapshot? = null
    private var rawDanmakuSignature: Long = 0L
    private var rawDanmakuCount: Int = 0
    private var preparedDanmakuSignature: Long = 0L
    private var preparedDanmakuCount: Int = 0
    private var lastSeekPositionMs: Long = Long.MIN_VALUE
    private var lastSeekRealtimeMs: Long = 0L
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var prepareJob: Job? = null
    private var preloadTextureJob: Job? = null
    private var prepareGeneration: Long = 0L

    fun setData(
        data: List<DmModel>,
        startupTraceId: String = PlaybackStartupTrace.NO_TRACE,
        startupTraceStartElapsedMs: Long = 0L
    ) {
        prepareJob?.cancel()
        val generation = ++prepareGeneration
        val input = data.toList()
        prepareJob = controllerScope.launch {
            val sortedData = input.sortedBy { it.progress }
            val rawSignature = sortedData.fastRawSignature()
            val allowVipColorful = isVipColorfulDanmakuAllowed()
            val filteredData = sortedData
                .applySmartFilter(level = smartFilterLevel, stage = "full")
            val preparedData = filteredData
                .mergeDuplicateDanmaku(mergeDuplicate, screenPart)
                .mapIndexedNotNull { index, item ->
                    item.toDanmakuItemData(index.toLong(), allowVipColorful)
                }
            val preparedSignature = preparedData.fastPreparedSignature()
            val textureStyles = preparedData.map { it.vipGradientStyle }.filter { it.hasTexture }
            scheduleVipTexturePreload(
                styles = textureStyles,
                generation = generation
            )
            withContext(Dispatchers.Main.immediate) {
                if (prepareGeneration != generation) {
                    return@withContext
                }
                if (rawDanmakuCount == sortedData.size &&
                    rawDanmakuSignature == rawSignature &&
                    preparedDanmakuCount == preparedData.size &&
                    preparedDanmakuSignature == preparedSignature &&
                    danmakuPlayer != null
                ) {
                    return@withContext
                }
                rawDanmakuData = sortedData
                rawDanmakuSignature = rawSignature
                rawDanmakuCount = sortedData.size
                syncSnapshotPosition()
                danmakuData = preparedData
                preparedDanmakuSignature = preparedSignature
                preparedDanmakuCount = preparedData.size
                val existingPlayer = danmakuPlayer
                if (existingPlayer != null) {
                    existingPlayer.clearData()
                    existingPlayer.updateData(danmakuData)
                    if (danmakuPositionMs > 0L) {
                        seekPlayerTo(
                            player = existingPlayer,
                            targetPositionMs = danmakuPositionMs,
                            currentTimeMs = existingPlayer.getCurrentTimeMs(),
                            forceSeek = true,
                            reason = "replace",
                            bypassDedup = true
                        )
                    }
                } else {
                    initPlayer()
                }
                PlaybackStartupTrace.log(
                    traceId = startupTraceId,
                    startElapsedMs = startupTraceStartElapsedMs,
                    step = "danmaku_player_data_applied",
                    message = "count=${preparedData.size} raw=${sortedData.size}"
                )
            }
        }
    }

    fun appendData(data: List<DmModel>) {
        if (data.isEmpty()) {
            return
        }
        val sortedData = data.sortedBy { it.progress }
        rawDanmakuData = if (rawDanmakuData.isEmpty()) {
            sortedData
        } else {
            rawDanmakuData + sortedData
        }
        rawDanmakuCount = rawDanmakuData.size
        rawDanmakuSignature = rawDanmakuData.fastRawSignature()
        appendPreparedData(sortedData, enableMerge = mergeDuplicate)
    }

    /**
     * 直播模式：立即启动弹幕引擎（不等待数据），然后用引擎当前时间作为 position 注入弹幕
     */
    fun startLive() {
        AppLog.d(TAG, "startLive: player=${danmakuPlayer != null} started=$isDanmakuStarted")
        isDanmakuStarted = true
        isDanmakuPaused = false
        ensurePlayer()
        danmakuPlayer?.setLiveMode(true)
        danmakuPlayer?.start(danmakuConfig)
        AppLog.d(TAG, "startLive: after start player=${danmakuPlayer != null}")
    }

    fun addLiveDanmaku(dm: DmModel) {
        if (!isDanmakuStarted || danmakuPlayer == null) {
            startLive()
        }
        val player = danmakuPlayer
        if (player == null) {
            AppLog.w(TAG, "addLiveDanmaku: player is null!")
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - liveThrottleWindowStart >= LIVE_THROTTLE_WINDOW_MS) {
            liveThrottleWindowStart = now
            liveThrottleCount = 0
        }
        if (liveThrottleCount >= LIVE_THROTTLE_MAX_ITEMS) {
            return
        }
        liveThrottleCount++
        if (!liveEngineStarted) {
            player.start(danmakuConfig)
            liveEngineStarted = true
        }

        flushExpiredLiveEntries()

        val key = MergeDuplicateKey(
            content = dm.content.trim().lowercase(),
            mode = dm.mode,
            color = dm.color,
            colorful = dm.colorful,
            colorfulSrc = dm.colorfulSrc.trim()
        )
        val existing = liveMergeBuffer[key]
        if (existing != null && now - existing.createdAt <= LIVE_MERGE_BUFFER_MS) {
            existing.count++
        } else {
            liveMergeBuffer[key] = LiveMergeEntry(
                firstItem = dm,
                count = 1,
                createdAt = now
            )
        }
        scheduleLiveFlush()
    }

    private fun flushExpiredLiveEntries() {
        val now = SystemClock.uptimeMillis()
        pruneLiveSentTimestamps(now)

        val effectiveThreshold = max(3, (baseThreshold() * screenPart).toInt())
        val bufferTotal = liveMergeBuffer.values.sumOf { it.count }

        val expiredKeys = mutableListOf<MergeDuplicateKey>()
        for ((key, entry) in liveMergeBuffer) {
            if (now - entry.createdAt < LIVE_MERGE_BUFFER_MS) continue
            expiredKeys.add(key)
            val N = entry.count
            val other = liveSentTimestamps.size + bufferTotal - N
            val budget = effectiveThreshold - other
            sendMergedLiveItems(entry.firstItem, N, budget)
        }
        expiredKeys.forEach { liveMergeBuffer.remove(it) }
    }

    private fun flushAllLiveEntries() {
        val now = SystemClock.uptimeMillis()
        pruneLiveSentTimestamps(now)

        val effectiveThreshold = max(3, (baseThreshold() * screenPart).toInt())
        val bufferTotal = liveMergeBuffer.values.sumOf { it.count }

        for ((_, entry) in liveMergeBuffer) {
            val N = entry.count
            val other = liveSentTimestamps.size + bufferTotal - N
            val budget = effectiveThreshold - other
            sendMergedLiveItems(entry.firstItem, N, budget)
        }
        liveMergeBuffer.clear()
    }

    private fun sendMergedLiveItems(firstItem: DmModel, N: Int, budget: Int) {
        val player = danmakuPlayer ?: return
        val now = SystemClock.uptimeMillis()
        when {
            N <= 1 || N <= budget -> {
                repeat(N) {
                    doSendLiveDanmaku(firstItem, player)
                    liveSentTimestamps.add(now)
                }
            }
            budget >= 1 -> {
                repeat(budget - 1) {
                    doSendLiveDanmaku(firstItem, player)
                    liveSentTimestamps.add(now)
                }
                val merged = firstItem.copy(
                    content = "${firstItem.content} ×${N - budget + 1}",
                    fontSize = max(firstItem.fontSize, 12) + 2
                )
                doSendLiveDanmaku(merged, player)
                liveSentTimestamps.add(now)
            }
            else -> {
                val merged = firstItem.copy(
                    content = "${firstItem.content} ×$N",
                    fontSize = max(firstItem.fontSize, 12) + 2
                )
                doSendLiveDanmaku(merged, player)
                liveSentTimestamps.add(now)
            }
        }
    }

    private fun doSendLiveDanmaku(dm: DmModel, player: DanmakuPlayer) {
        val currentTime = player.getCurrentTimeMs()
        val color = dm.color.toDanmakuColor(isVipColorfulDanmakuAllowed())
        val data = DanmakuItemData(
            danmakuId = ++liveDanmakuIdCounter,
            position = currentTime.coerceAtLeast(0L),
            content = dm.content,
            mode = DanmakuItemData.DANMAKU_MODE_ROLLING,
            textSize = dm.fontSize.coerceAtLeast(12),
            textColor = color,
            score = 0,
            renderFlags = DanmakuItemData.RENDER_FLAG_NONE,
            vipGradientStyle = DanmakuVipGradientStyle.NONE
        )
        player.send(data)
    }

    private fun scheduleLiveFlush() {
        if (liveMergeBuffer.isEmpty()) return
        liveFlushJob?.cancel()
        liveFlushJob = controllerScope.launch(Dispatchers.Main) {
            delay(LIVE_MERGE_BUFFER_MS)
            flushExpiredLiveEntries()
        }
    }

    private fun pruneLiveSentTimestamps(now: Long) {
        while (liveSentTimestamps.isNotEmpty() && now - liveSentTimestamps.first() > LIVE_DENSITY_TRACK_MS) {
            liveSentTimestamps.removeFirst()
        }
    }

    /**
     * Applies the full setting snapshot in one place so partial UI callbacks do not leave
     * danmaku config in an inconsistent intermediate state.
     */
    fun applySettings(snapshot: SettingsSnapshot) {
        if (lastSettingsSnapshot == snapshot) {
            return
        }
        lastSettingsSnapshot = snapshot
        val normalizedSmartFilterLevel = snapshot.smartFilterLevel.normalizeSmartFilterLevel()
        val durationMs = snapshot.speed.toDanmakuDurationMs()
        val newConfig = danmakuConfig.copy(
            visibility = snapshot.enabled,
            alpha = snapshot.alpha.coerceIn(0.1f, 1f),
            textSizeScale = snapshot.textSize.toDanmakuTextScale(),
            durationMs = durationMs,
            rollingDurationMs = durationMs,
            screenPart = snapshot.screenArea.toDanmakuScreenPart()
        )
        val filterChanged = applyTypeFilterState(
            config = newConfig,
            type = DanmakuItemData.DANMAKU_MODE_CENTER_TOP,
            visible = snapshot.allowTop
        ) or applyTypeFilterState(
            config = newConfig,
            type = DanmakuItemData.DANMAKU_MODE_CENTER_BOTTOM,
            visible = snapshot.allowBottom
        )
        if (filterChanged) {
            newConfig.updateFilter()
        }
        updateConfig(newConfig)
        updatePreparationOptions(
            mergeDuplicateEnabled = snapshot.mergeDuplicate,
            smartFilterLevel = normalizedSmartFilterLevel,
            screenPartValue = snapshot.screenArea.toDanmakuScreenPart()
        )
    }

    fun updatePlaybackSpeed(speed: Float) {
        danmakuPlayer?.updatePlaySpeed(speed)
    }

    fun setEnabled(enabled: Boolean) {
        lastSettingsSnapshot = lastSettingsSnapshot?.copy(enabled = enabled)
        updateVisibility(enabled)
        val danmakuView = danmakuViewProvider()
        if (enabled) {
            danmakuView?.visibility = android.view.View.VISIBLE
            resume()
        } else {
            danmakuView?.visibility = android.view.View.INVISIBLE
            pause()
        }
    }

    fun pause() {
        if (isDanmakuPaused) {
            return
        }
        isDanmakuPaused = true
        danmakuPositionMs = danmakuPlayer?.getCurrentTimeMs() ?: danmakuPositionMs
        danmakuPlayer?.pause()
    }

    fun resume() {
        if (isDanmakuStarted && !isDanmakuPaused) {
            return
        }
        isDanmakuStarted = true
        isDanmakuPaused = false
        if (!hasPreparedData() && !liveEngineStarted) {
            return
        }
        ensurePlayer()
        danmakuPlayer?.start(danmakuConfig)
    }

    fun stop() {
        isDanmakuStarted = false
        isDanmakuPaused = false
        liveEngineStarted = false
        liveThrottleCount = 0
        liveFlushJob?.cancel()
        liveMergeBuffer.clear()
        liveSentTimestamps.clear()
        danmakuPositionMs = 0L
        danmakuPlayer?.setLiveMode(false)
        danmakuPlayer?.stop()
    }

    fun syncPosition(positionMs: Long, forceSeek: Boolean = false) {
        val safePosition = positionMs.coerceAtLeast(0L)
        danmakuPositionMs = safePosition
        val player = danmakuPlayer ?: return
        if (!isDanmakuStarted) {
            return
        }
        val currentTime = player.getCurrentTimeMs()
        if (!forceSeek && safePosition < currentTime) {
            return
        }
        if (forceSeek || abs(currentTime - safePosition) > MAX_SYNC_DRIFT_MS) {
            seekPlayerTo(
                player = player,
                targetPositionMs = safePosition,
                currentTimeMs = currentTime,
                forceSeek = forceSeek,
                reason = "sync"
            )
        }
    }

    fun release() {
        prepareJob?.cancel()
        preloadTextureJob?.cancel()
        liveFlushJob?.cancel()
        liveMergeBuffer.clear()
        liveSentTimestamps.clear()
        controllerScope.cancel()
        releasePlayer()
    }

    private fun rebuildAndApplyData() {
        prepareJob?.cancel()
        val generation = ++prepareGeneration
        prepareJob = controllerScope.launch {
            val allowVipColorful = isVipColorfulDanmakuAllowed()
            val filteredData = rawDanmakuData
                .applySmartFilter(level = smartFilterLevel, stage = "full")
            val preparedData = filteredData
                .mergeDuplicateDanmaku(mergeDuplicate, screenPart)
                .mapIndexedNotNull { index, item ->
                    item.toDanmakuItemData(index.toLong(), allowVipColorful)
                }
            scheduleVipTexturePreload(
                styles = preparedData.map { it.vipGradientStyle }.filter { it.hasTexture },
                generation = generation
            )
            withContext(Dispatchers.Main.immediate) {
                if (prepareGeneration != generation) {
                    return@withContext
                }
                val signature = preparedData.fastPreparedSignature()
                if (preparedDanmakuCount == preparedData.size &&
                    preparedDanmakuSignature == signature &&
                    danmakuPlayer != null
                ) {
                    return@withContext
                }
                syncSnapshotPosition()
                danmakuData = preparedData
                preparedDanmakuSignature = signature
                preparedDanmakuCount = preparedData.size
                val existingPlayer = danmakuPlayer
                if (existingPlayer != null) {
                    existingPlayer.clearData()
                    existingPlayer.updateData(danmakuData)
                    if (danmakuPositionMs > 0L) {
                        seekPlayerTo(
                            player = existingPlayer,
                            targetPositionMs = danmakuPositionMs,
                            currentTimeMs = existingPlayer.getCurrentTimeMs(),
                            forceSeek = true,
                            reason = "replace",
                            bypassDedup = true
                        )
                    }
                } else {
                    initPlayer()
                }
            }
        }
    }

    private fun ensurePlayer() {
        if (danmakuPlayer != null) return
        initPlayer()
    }

    private fun initPlayer() {
        val danmakuView = danmakuViewProvider() ?: return
        danmakuView.isClickable = false
        danmakuView.isFocusable = false
        syncSnapshotPosition()
        releasePlayer()

        // 根据屏幕分辨率动态调整弹幕缓存池大小
        val screenW = context.resources.displayMetrics.widthPixels
        val screenH = context.resources.displayMetrics.heightPixels
        DanmakuConfig.CACHE_POOL_MAX_MEMORY_SIZE =
            DanmakuConfig.computeCachePoolMaxMemorySize(screenW, screenH)

        danmakuPlayer = DanmakuPlayer(SimpleRenderer()).also { player ->
            player.bindView(danmakuView)
            player.updateConfig(danmakuConfig)
            val viewWidth = danmakuView.measuredWidth
            val viewHeight = danmakuView.measuredHeight
            if (viewWidth > 0 && viewHeight > 0) {
                player.notifyDisplayerSizeChanged(viewWidth, viewHeight)
            }
            if (danmakuData.isNotEmpty()) {
                player.updateData(danmakuData)
            }
            if (danmakuPositionMs > 0L) {
                seekPlayerTo(
                    player = player,
                    targetPositionMs = danmakuPositionMs,
                    currentTimeMs = null,
                    forceSeek = true,
                    reason = "init",
                    bypassDedup = true
                )
            }
            if (isDanmakuStarted && hasPreparedData()) {
                player.start(danmakuConfig)
                if (isDanmakuPaused) {
                    player.pause()
                }
            }
        }
    }

    private fun appendPreparedData(data: List<DmModel>, enableMerge: Boolean = false) {
        val previousJob = prepareJob
        val generation = ++prepareGeneration
        prepareJob = controllerScope.launch {
            previousJob?.join()
            val allowVipColorful = isVipColorfulDanmakuAllowed()
            val startIndex = danmakuData.size.toLong()
            val filteredData = data
                .applySmartFilter(level = smartFilterLevel, stage = "append")
            val processedData = filteredData
                .mergeDuplicateDanmaku(enabled = enableMerge, part = screenPart)
            val preparedData = processedData
                .mapIndexedNotNull { index, item ->
                    item.toDanmakuItemData(startIndex + index, allowVipColorful)
                }
            scheduleVipTexturePreload(
                styles = preparedData.map { it.vipGradientStyle }.filter { it.hasTexture },
                generation = generation
            )
            withContext(Dispatchers.Main.immediate) {
                if (prepareGeneration != generation) {
                    return@withContext
                }
                syncSnapshotPosition()
                danmakuData = danmakuData + preparedData
                preparedDanmakuSignature = danmakuData.fastPreparedSignature()
                preparedDanmakuCount = danmakuData.size
                ensurePlayer()
                danmakuPlayer?.updateData(preparedData)
            }
        }
    }

    private fun syncSnapshotPosition() {
        val currentTime = danmakuPlayer?.getCurrentTimeMs() ?: return
        if (currentTime > danmakuPositionMs) {
            danmakuPositionMs = currentTime
        }
    }

    private fun releasePlayer() {
        danmakuPlayer?.release()
        danmakuPlayer = null
        liveEngineStarted = false
        liveThrottleCount = 0
        lastSeekPositionMs = Long.MIN_VALUE
        lastSeekRealtimeMs = 0L
    }

    private fun scheduleVipTexturePreload(
        styles: List<DanmakuVipGradientStyle>,
        generation: Long
    ) {
        if (styles.isEmpty()) {
            return
        }
        preloadTextureJob?.cancel()
        preloadTextureJob = controllerScope.launch(Dispatchers.IO) {
            VipDanmakuTextureCache.preloadStyles(styles)
            withContext(Dispatchers.Main.immediate) {
                if (prepareGeneration != generation) {
                    return@withContext
                }
                // Refresh cached text bitmaps after texture downloads complete.
                danmakuConfig.updateCache()
                danmakuPlayer?.updateConfig(danmakuConfig)
            }
        }
    }

    private fun updatePreparationOptions(mergeDuplicateEnabled: Boolean, smartFilterLevel: Int, screenPartValue: Float) {
        val mergeChanged = mergeDuplicate != mergeDuplicateEnabled
        val smartFilterChanged = this.smartFilterLevel != smartFilterLevel
        val screenPartChanged = screenPart != screenPartValue
        mergeDuplicate = mergeDuplicateEnabled
        screenPart = screenPartValue
        this.smartFilterLevel = smartFilterLevel
        if ((mergeChanged || smartFilterChanged || screenPartChanged) && rawDanmakuData.isNotEmpty()) {
            rebuildAndApplyData()
        }
    }

    private fun seekPlayerTo(
        player: DanmakuPlayer,
        targetPositionMs: Long,
        currentTimeMs: Long?,
        forceSeek: Boolean,
        reason: String,
        bypassDedup: Boolean = false
    ) {
        if (!bypassDedup && shouldSuppressDuplicateSeek(targetPositionMs, currentTimeMs)) {
            return
        }
        player.seekTo(targetPositionMs)
        lastSeekPositionMs = targetPositionMs
        lastSeekRealtimeMs = SystemClock.elapsedRealtime()
        // AkDanmaku seekTo() will restart its timer, so we need to restore
        // the paused snapshot when the video itself is still paused.
        if (isDanmakuPaused) {
            player.pause()
        }
    }

    private fun shouldSuppressDuplicateSeek(
        targetPositionMs: Long,
        currentTimeMs: Long?
    ): Boolean {
        val lastPosition = lastSeekPositionMs
        if (lastPosition == Long.MIN_VALUE) {
            return false
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastSeekRealtimeMs > SEEK_DEDUP_WINDOW_MS) {
            return false
        }
        if (abs(lastPosition - targetPositionMs) > SEEK_DEDUP_POSITION_TOLERANCE_MS) {
            return false
        }
        if (currentTimeMs != null && abs(currentTimeMs - targetPositionMs) <= SEEK_DEDUP_POSITION_TOLERANCE_MS) {
            return true
        }
        return true
    }

    private fun updateVisibility(enabled: Boolean) {
        if (danmakuConfig.visibility == enabled) {
            return
        }
        updateConfig(danmakuConfig.copy(visibility = enabled))
    }

    private fun updateAlpha(alpha: Float) {
        updateConfig(danmakuConfig.copy(alpha = alpha.coerceIn(0.1f, 1f)))
    }

    private fun updateTextSize(size: Int) {
        updateConfig(danmakuConfig.copy(textSizeScale = size.toDanmakuTextScale()))
    }

    private fun updateSpeed(speed: Int) {
        val durationMs = speed.toDanmakuDurationMs()
        updateConfig(
            danmakuConfig.copy(
                durationMs = durationMs,
                rollingDurationMs = durationMs
            )
        )
    }

    private fun updateScreenArea(area: Int) {
        updateConfig(danmakuConfig.copy(screenPart = area.toDanmakuScreenPart()))
    }

    private fun updateAllowTop(allow: Boolean) {
        applyTypeFilterAndDispatch(DanmakuItemData.DANMAKU_MODE_CENTER_TOP, allow)
    }

    private fun updateAllowBottom(allow: Boolean) {
        applyTypeFilterAndDispatch(DanmakuItemData.DANMAKU_MODE_CENTER_BOTTOM, allow)
    }

    private fun applyTypeFilterAndDispatch(type: Int, visible: Boolean) {
        if (!applyTypeFilterState(danmakuConfig, type, visible)) {
            return
        }
        danmakuConfig.updateFilter()
        danmakuPlayer?.updateConfig(danmakuConfig)
    }

    private fun applyTypeFilterState(
        config: DanmakuConfig,
        type: Int,
        visible: Boolean
    ): Boolean {
        val typeFilter = config.dataFilter
            .filterIsInstance<TypeFilter>()
            .firstOrNull()
            ?: return false
        val isCurrentlyVisible = type !in typeFilter.filterSet
        if (isCurrentlyVisible == visible) {
            return false
        }
        if (visible) {
            typeFilter.removeFilterItem(type)
        } else {
            typeFilter.addFilterItem(type)
        }
        return true
    }

    private fun updateConfig(newConfig: DanmakuConfig) {
        if (danmakuConfig == newConfig) {
            return
        }
        danmakuConfig = newConfig
        danmakuPlayer?.updateConfig(newConfig)
    }

    private fun isVipColorfulDanmakuAllowed(): Boolean {
        return context.isVipColorfulDanmakuAllowed()
    }

    private fun baseThreshold(): Int {
        val screenWidth = context.resources.displayMetrics.widthPixels
        return (screenWidth / 1080f * 40).toInt().coerceIn(30, 100)
    }

    private fun hasPreparedData(): Boolean {
        if (rawDanmakuData.isEmpty()) return false
        return danmakuData.isNotEmpty()
    }

    private fun List<DmModel>.mergeDuplicateDanmaku(enabled: Boolean, part: Float): List<DmModel> {
        if (!enabled || isEmpty()) return this

        val effectiveThreshold = max(3, (baseThreshold() * part).toInt())

        // First pass: identify merge groups
        val firstIndexByKey = HashMap<MergeDuplicateKey, Int>()
        val groupIdOf = IntArray(size) { -1 }
        val groups = mutableListOf<MergeGroup>()

        for (i in indices) {
            val item = this[i]
            val key = MergeDuplicateKey(
                content = item.content.trim().lowercase(),
                mode = item.mode,
                color = item.color,
                colorful = item.colorful,
                colorfulSrc = item.colorfulSrc.trim()
            )
            val firstIdx = firstIndexByKey[key]
            if (firstIdx != null &&
                item.progress - this[firstIdx].progress <= MERGE_DUPLICATE_WINDOW_MS
            ) {
                groupIdOf[i] = groupIdOf[firstIdx]
                groups[groupIdOf[i]].count++
            } else {
                firstIndexByKey[key] = i
                val gid = groups.size
                groupIdOf[i] = gid
                groups.add(MergeGroup(firstIndex = i, count = 1))
            }
        }

        // Second pass: compute density and decide merge strategy per group
        for (group in groups) {
            if (group.count < 2) continue
            val windowStart = this[group.firstIndex].progress.toLong()
            val windowEnd = windowStart + MERGE_DUPLICATE_WINDOW_MS
            val total = countInRange(windowStart, windowEnd)
            val other = total - group.count
            val budget = effectiveThreshold - other
            when {
                group.count <= budget -> {
                    group.standaloneCount = group.count
                    group.mergedCount = 0
                }
                budget >= 1 -> {
                    group.standaloneCount = budget - 1
                    group.mergedCount = group.count - budget + 1
                }
                else -> {
                    group.standaloneCount = 0
                    group.mergedCount = group.count
                }
            }
        }

        // Third pass: generate output
        val emitted = IntArray(groups.size)
        return mapIndexedNotNull { index, item ->
            val gid = groupIdOf[index]
            val group = groups[gid]
            if (group.count < 2 || group.mergedCount == 0) return@mapIndexedNotNull item
            val e = emitted[gid]++
            when {
                e < group.standaloneCount -> item
                e == group.standaloneCount -> {
                    val src = this[group.firstIndex]
                    item.copy(
                        content = "${src.content} ×${group.mergedCount}",
                        fontSize = max(item.fontSize, 12) + 2
                    )
                }
                else -> null
            }
        }
    }

    private fun List<DmModel>.applySmartFilter(level: Int, stage: String): List<DmModel> {
        val normalizedLevel = level.normalizeSmartFilterLevel()
        if (normalizedLevel == SMART_FILTER_LEVEL_OFF || isEmpty()) {
            return this
        }
        val maxPositiveScore = asSequence()
            .map { it.aiFlagScore }
            .filter { it > 0 }
            .maxOrNull()
            ?: return this
        val threshold = resolveSmartFilterThreshold(normalizedLevel, maxPositiveScore)
        val filtered = filter { item ->
            val score = item.aiFlagScore
            score <= 0 || score < threshold
        }
        return filtered
    }

    private fun resolveSmartFilterThreshold(level: Int, maxPositiveScore: Int): Int {
        val ratio = (SMART_FILTER_LEVEL_MAX - level).toFloat() / SMART_FILTER_LEVEL_MAX
        return max(1, (maxPositiveScore * ratio).toInt())
    }

    private data class MergeDuplicateKey(
        val content: String,
        val mode: Int,
        val color: Int,
        val colorful: Int,
        val colorfulSrc: String
    )

    private data class MergeGroup(
        val firstIndex: Int,
        var count: Int,
        var standaloneCount: Int = 0,
        var mergedCount: Int = 0
    )

    private data class LiveMergeEntry(
        val firstItem: DmModel,
        var count: Int,
        val createdAt: Long
    )

    private fun List<DmModel>.countInRange(startMs: Long, endMs: Long): Int {
        val from = lowerBoundProgress(startMs)
        if (from >= size) return 0
        return upperBoundProgress(endMs) - from
    }

    private fun List<DmModel>.lowerBoundProgress(target: Long): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (this[mid].progress.toLong() < target) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun List<DmModel>.upperBoundProgress(target: Long): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (this[mid].progress.toLong() <= target) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun DmModel.toDanmakuItemData(index: Long, allowVipColorful: Boolean): DanmakuItemData? {
        val renderContent = toRenderableContent() ?: return null
        return DanmakuItemData(
            danmakuId = id.takeIf { it > 0L } ?: (index + 1L),
            position = progress.toLong().coerceAtLeast(0L),
            content = renderContent,
            mode = mode.toDanmakuMode(),
            textSize = fontSize.coerceAtLeast(12),
            textColor = color.toDanmakuColor(allowVipColorful),
            score = weight.coerceAtLeast(0),
            renderFlags = resolveRenderFlags(allowVipColorful),
            vipGradientStyle = resolveVipGradientStyle(allowVipColorful)
        )
    }

    private fun DmModel.resolveRenderFlags(allowVipColorful: Boolean): Int {
        if (!allowVipColorful) {
            return DanmakuItemData.RENDER_FLAG_NONE
        }
        return if (colorful == COLORFUL_VIP_GRADIENT) {
            DanmakuItemData.RENDER_FLAG_VIP_GRADIENT
        } else {
            DanmakuItemData.RENDER_FLAG_NONE
        }
    }

    private fun DmModel.resolveVipGradientStyle(allowVipColorful: Boolean): DanmakuVipGradientStyle {
        if (!allowVipColorful || colorful != COLORFUL_VIP_GRADIENT) {
            return DanmakuVipGradientStyle.NONE
        }
        return DanmakuVipGradientStyle(
            fillTextureUrl = colorfulStyle.fillColorUrl,
            strokeTextureUrl = colorfulStyle.strokeColorUrl
        )
    }

    private fun DmModel.toRenderableContent(): String? {
        return when {
            content.isBlank() -> null
            mode == 7 -> null
            mode == 9 || content.contains("def text") -> null
            else -> content
        }
    }

    private fun Int.toDanmakuMode(): Int {
        return when (this) {
            DanmakuItemData.DANMAKU_MODE_CENTER_TOP,
            DanmakuItemData.DANMAKU_MODE_CENTER_BOTTOM -> this
            else -> DanmakuItemData.DANMAKU_MODE_ROLLING
        }
    }

    private fun Int.toDanmakuColor(allowVipColorful: Boolean): Int {
        val resolvedColor = if (this == 0) {
            Color.WHITE
        } else {
            this or 0xFF000000.toInt()
        }
        if (!allowVipColorful && resolvedColor != Color.WHITE) {
            return Color.WHITE
        }
        return resolvedColor
    }

    private fun Int.normalizeSmartFilterLevel(): Int {
        return coerceIn(SMART_FILTER_LEVEL_OFF, SMART_FILTER_LEVEL_MAX)
    }

    private fun Int.toDanmakuTextScale(): Float {
        return when (this) {
            30 -> 0.55f
            31 -> 0.6f
            32 -> 0.65f
            33 -> 0.7f
            34 -> 0.75f
            35 -> 0.8f
            36 -> 0.85f
            37 -> 0.9f
            38 -> 0.95f
            39 -> 1.0f
            40 -> 1.14f
            41 -> 1.3f
            42 -> 1.4f
            43 -> 1.5f
            44 -> 1.6f
            45 -> 1.7f
            46 -> 1.8f
            47 -> 2.0f
            48 -> 2.1f
            49 -> 2.2f
            50 -> 2.3f
            51 -> 2.4f
            52 -> 2.5f
            53 -> 2.6f
            54 -> 2.7f
            55 -> 2.8f
            else -> 1.14f
        }
    }

    private fun Int.toDanmakuDurationMs(): Long {
        return when (this) {
            1 -> 12000L
            2 -> 10200L
            3 -> 8400L
            5 -> 6000L
            6 -> 4800L
            7 -> 3840L
            8 -> 3000L
            9 -> 2160L
            else -> 6600L
        }
    }

    private fun Int.toDanmakuScreenPart(): Float {
        return when (this) {
            -1 -> 1f / 8f
            0 -> 0.16f
            1 -> 1f / 4f
            3 -> 1f / 2f
            7 -> 3f / 4f
            else -> 1f
        }
    }

    private fun List<DmModel>.fastRawSignature(): Long {
        var acc = 1469598103934665603L
        for (item in this) {
            acc = acc.mix(item.id)
            acc = acc.mix(item.progress.toLong())
            acc = acc.mix(item.mode.toLong())
            acc = acc.mix(item.fontSize.toLong())
            acc = acc.mix(item.color.toLong())
            acc = acc.mix(item.colorful.toLong())
            acc = acc.mix(item.content.hashCode().toLong())
        }
        return acc
    }

    private fun List<DanmakuItemData>.fastPreparedSignature(): Long {
        var acc = 1469598103934665603L
        for (item in this) {
            acc = acc.mix(item.danmakuId)
            acc = acc.mix(item.position)
            acc = acc.mix(item.mode.toLong())
            acc = acc.mix(item.textSize.toLong())
            acc = acc.mix(item.textColor.toLong())
            acc = acc.mix(item.renderFlags.toLong())
            acc = acc.mix(item.content.hashCode().toLong())
        }
        return acc
    }

    private fun Long.mix(value: Long): Long {
        return (this xor value) * 1099511628211L
    }
}

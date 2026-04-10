package com.tutu.myblbl.ui.view.player

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
import com.tutu.myblbl.model.dm.DmModel
import com.tutu.myblbl.utils.AppLog
import com.tutu.myblbl.utils.isVipColorfulDanmakuAllowed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
        private const val TAG = "MyPlayerDanmaku"
        private const val MERGE_DUPLICATE_WINDOW_MS = 15_000
        private const val MERGE_DUPLICATE_MIN_COUNT = 2
        private const val MAX_SYNC_DRIFT_MS = 1200L
        private const val COLORFUL_VIP_GRADIENT = 0xEA61
        private const val SEEK_DEDUP_WINDOW_MS = 300L
        private const val SEEK_DEDUP_POSITION_TOLERANCE_MS = 80L
        private const val SMART_FILTER_LEVEL_OFF = 0
        private const val SMART_FILTER_LEVEL_LOW = 1
        private const val SMART_FILTER_LEVEL_MEDIUM = 2
        private const val SMART_FILTER_LEVEL_HIGH = 3
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
    private var mergeDuplicate = true
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

    fun setData(data: List<DmModel>) {
        val sortedData = data.sortedBy { it.progress }
        val signature = sortedData.fastRawSignature()
        if (rawDanmakuCount == sortedData.size && rawDanmakuSignature == signature) {
            return
        }
        rawDanmakuData = sortedData
        rawDanmakuSignature = signature
        rawDanmakuCount = sortedData.size
        rebuildAndApplyData()
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
        rawDanmakuSignature = rawDanmakuData.fastRawSignature()
        rawDanmakuCount = rawDanmakuData.size
        if (mergeDuplicate) {
            rebuildAndApplyData()
            return
        }
        appendPreparedData(sortedData)
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
            smartFilterLevel = normalizedSmartFilterLevel
        )
    }

    fun updatePlaybackSpeed(speed: Float) {
        danmakuPlayer?.updatePlaySpeed(speed)
    }

    fun setEnabled(enabled: Boolean) {
        lastSettingsSnapshot = lastSettingsSnapshot?.copy(enabled = enabled)
        updateVisibility(enabled)
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
        if (!hasPreparedData()) {
            return
        }
        ensurePlayer()
        danmakuPlayer?.start(danmakuConfig)
    }

    fun stop() {
        isDanmakuStarted = false
        isDanmakuPaused = false
        danmakuPositionMs = 0L
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
                .mergeDuplicateDanmaku(mergeDuplicate)
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
                recreatePlayer()
            }
        }
    }

    private fun ensurePlayer() {
        if (danmakuPlayer == null) {
            initPlayer()
        }
    }

    private fun recreatePlayer() {
        initPlayer()
    }

    private fun initPlayer() {
        val danmakuView = danmakuViewProvider() ?: return
        danmakuView.isClickable = false
        danmakuView.isFocusable = false
        syncSnapshotPosition()
        releasePlayer()
        danmakuPlayer = DanmakuPlayer(SimpleRenderer()).also { player ->
            player.bindView(danmakuView)
            player.updateConfig(danmakuConfig)
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

    private fun appendPreparedData(data: List<DmModel>) {
        prepareJob?.cancel()
        val generation = ++prepareGeneration
        prepareJob = controllerScope.launch {
            val allowVipColorful = isVipColorfulDanmakuAllowed()
            val startIndex = danmakuData.size.toLong()
            val filteredData = data
                .applySmartFilter(level = smartFilterLevel, stage = "append")
            val preparedData = filteredData
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

    private fun updatePreparationOptions(mergeDuplicateEnabled: Boolean, smartFilterLevel: Int) {
        val mergeChanged = mergeDuplicate != mergeDuplicateEnabled
        val smartFilterChanged = this.smartFilterLevel != smartFilterLevel
        mergeDuplicate = mergeDuplicateEnabled
        this.smartFilterLevel = smartFilterLevel
        if ((mergeChanged || smartFilterChanged) && rawDanmakuData.isNotEmpty()) {
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
            AppLog.d(
                TAG,
                "skip duplicate danmaku seek[$reason]: target=$targetPositionMs, current=${currentTimeMs ?: -1}, force=$forceSeek"
            )
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

    private fun hasPreparedData(): Boolean {
        return danmakuData.isNotEmpty() || rawDanmakuData.isNotEmpty()
    }

    private fun List<DmModel>.mergeDuplicateDanmaku(enabled: Boolean): List<DmModel> {
        if (!enabled || isEmpty()) return this

        val firstIndexByContent = HashMap<MergeDuplicateKey, Int>()
        val mergeCount = IntArray(size)
        val removed = BooleanArray(size)

        for (i in indices) {
            val item = this[i]
            val key = MergeDuplicateKey(
                content = item.content.trim().lowercase(),
                mode = item.mode,
                color = item.color,
                colorful = item.colorful,
                colorfulSrc = item.colorfulSrc.trim()
            )
            val existingIndex = firstIndexByContent[key]
            if (existingIndex != null &&
                item.progress - this[existingIndex].progress <= MERGE_DUPLICATE_WINDOW_MS
            ) {
                mergeCount[existingIndex]++
                removed[i] = true
            } else {
                firstIndexByContent[key] = i
            }
        }

        return mapIndexedNotNull { index, item ->
            if (removed[index]) return@mapIndexedNotNull null
            val count = mergeCount[index] + 1
            if (count >= MERGE_DUPLICATE_MIN_COUNT) {
                item.copy(
                    content = "${item.content} ×$count",
                    fontSize = max(item.fontSize, 12) + 2
                )
            } else {
                item
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
        AppLog.d(
            TAG,
            "smartFilter[$stage]: level=$normalizedLevel, threshold=$threshold, maxScore=$maxPositiveScore, before=${size}, after=${filtered.size}, filtered=${size - filtered.size}"
        )
        return filtered
    }

    private fun resolveSmartFilterThreshold(level: Int, maxPositiveScore: Int): Int {
        return when {
            maxPositiveScore <= 3 -> when (level) {
                SMART_FILTER_LEVEL_LOW -> 3
                SMART_FILTER_LEVEL_MEDIUM -> 2
                else -> 1
            }

            maxPositiveScore <= 10 -> when (level) {
                SMART_FILTER_LEVEL_LOW -> 7
                SMART_FILTER_LEVEL_MEDIUM -> 4
                else -> 2
            }

            else -> when (level) {
                SMART_FILTER_LEVEL_LOW -> max(1, (maxPositiveScore * 3) / 4)
                SMART_FILTER_LEVEL_MEDIUM -> max(1, maxPositiveScore / 2)
                else -> max(1, maxPositiveScore / 4)
            }
        }
    }

    private data class MergeDuplicateKey(
        val content: String,
        val mode: Int,
        val color: Int,
        val colorful: Int,
        val colorfulSrc: String
    )

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
        return coerceIn(SMART_FILTER_LEVEL_OFF, SMART_FILTER_LEVEL_HIGH)
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
            1 -> 7000L
            2 -> 6000L
            3 -> 5000L
            5 -> 3200L
            6 -> 2600L
            7 -> 2200L
            8 -> 1800L
            9 -> 1200L
            else -> 3800L
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

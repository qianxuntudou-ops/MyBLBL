# 空降助手 (SponsorBlock) 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 MyBLBL 播放器中集成空降助手功能，自动识别并跳过视频中的恰饭、开场、片尾片段。

**Architecture:** 新建 `sponsor` 包，包含数据模型、Repository、UseCase 三层。UseCase 在 ViewModel 的 `updatePlaybackPosition` 中被调用检查当前位置。UI 使用 Android View（与现有播放器一致），跳过按钮叠加在 `MyPlayerView` 上，进度条标记叠加在 `DefaultTimeBar` 上。

**Tech Stack:** Kotlin, OkHttp (现有), kotlinx.serialization (需确认依赖), Media3 ExoPlayer (现有), Android View (现有), DataStore (现有)

---

## 文件结构

### 新建文件
- `app/src/main/java/com/tutu/myblbl/feature/player/sponsor/SponsorSegment.kt` - 数据模型
- `app/src/main/java/com/tutu/myblbl/feature/player/sponsor/SponsorBlockRepository.kt` - API 网络请求
- `app/src/main/java/com/tutu/myblbl/feature/player/sponsor/SponsorBlockUseCase.kt` - 业务逻辑（跳过判断、防抖）
- `app/src/main/java/com/tutu/myblbl/feature/player/sponsor/SponsorSkipOverlayView.kt` - 跳过按钮叠加 View
- `app/src/main/java/com/tutu/myblbl/feature/player/sponsor/SponsorProgressMarkerView.kt` - 进度条片段标记 View

### 修改文件
- `app/src/main/java/com/tutu/myblbl/feature/player/settings/PlayerSettingsStore.kt` - 新增设置字段
- `app/src/main/java/com/tutu/myblbl/feature/player/VideoPlayerViewModel.kt` - 集成 UseCase
- `app/src/main/java/com/tutu/myblbl/feature/player/view/MyPlayerView.kt` - 添加叠加 View
- `app/src/main/java/com/tutu/myblbl/feature/player/view/MyPlayerControlView.kt` - 添加进度条标记
- `app/src/main/java/com/tutu/myblbl/feature/player/view/MyPlayerSettingMenuBuilder.kt` - 添加设置菜单项
- `app/src/main/java/com/tutu/myblbl/feature/player/view/MyPlayerSettingView.kt` - 添加设置项常量和处理
- `app/src/main/res/values/strings.xml` - 添加中文字符串

---

### Task 1: 数据模型 - SponsorSegment

**Files:**
- Create: `app/src/main/java/com/tutu/myblbl/feature/player/sponsor/SponsorSegment.kt`

- [ ] **Step 1: 创建 SponsorSegment 数据模型**

```kotlin
package com.tutu.myblbl.feature.player.sponsor

import kotlinx.serialization.Serializable

@Serializable
data class SponsorSegment(
    val segment: List<Float> = emptyList(),
    val UUID: String = "",
    val category: String = "",
    val actionType: String = "skip",
    val locked: Int = 0,
    val votes: Int = 0,
    val videoDuration: Float = 0f
) {
    val startTimeMs: Long get() = ((segment.getOrNull(0) ?: 0f) * 1000).toLong()
    val endTimeMs: Long get() = ((segment.getOrNull(1) ?: 0f) * 1000).toLong()
    val isSkipType: Boolean get() = actionType == "skip"

    fun categoryName(): String = when (category) {
        CATEGORY_SPONSOR -> "恰饭片段"
        CATEGORY_INTRO -> "开场动画"
        CATEGORY_OUTRO -> "片尾动画"
        else -> category
    }

    fun categoryColor(): Long = when (category) {
        CATEGORY_SPONSOR -> 0xFFFFA500   // 橙色
        CATEGORY_INTRO -> 0xFF42A5F5     // 蓝色
        CATEGORY_OUTRO -> 0xFFAB47BC     // 紫色
        else -> 0xFFFFA500
    }

    companion object {
        const val CATEGORY_SPONSOR = "sponsor"
        const val CATEGORY_INTRO = "intro"
        const val CATEGORY_OUTRO = "outro"
        val ALL_CATEGORIES = listOf(CATEGORY_SPONSOR, CATEGORY_INTRO, CATEGORY_OUTRO)
    }
}
```

- [ ] **Step 2: 检查 kotlinx.serialization 依赖是否已存在**

Run: `grep -r "kotlinx.serialization" app/build.gradle.kts`
- 如果不存在，在 `app/build.gradle.kts` 的 dependencies 中添加：
  ```
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
  ```
  并在 `plugins` 块中添加 `id("org.jetbrains.kotlin.plugin.serialization")`
- 如果已存在，跳过

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/tutu/myblbl/feature/player/sponsor/SponsorSegment.kt
git commit -m "feat(sponsor): add SponsorSegment data model"
```

---

### Task 2: Repository - SponsorBlockRepository

**Files:**
- Create: `app/src/main/java/com/tutu/myblbl/feature/player/sponsor/SponsorBlockRepository.kt`

- [ ] **Step 1: 创建 SponsorBlockRepository**

```kotlin
package com.tutu.myblbl.feature.player.sponsor

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object SponsorBlockRepository {

    private const val BASE_URL = "https://bsbsb.top/api"
    private const val TAG = "SponsorBlock"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    suspend fun getSegments(
        bvid: String,
        cid: Long = 0L,
        categories: List<String> = SponsorSegment.ALL_CATEGORIES
    ): List<SponsorSegment> = withContext(Dispatchers.IO) {
        try {
            val params = buildList {
                add("videoID=$bvid")
                if (cid > 0L) add("cid=$cid")
                categories.forEach { add("category=$it") }
            }
            val url = "$BASE_URL/skipSegments?${params.joinToString("&")}"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "MyBLBL/1.0")
                .get()
                .build()

            val response = client.newCall(request).execute()
            when (response.code) {
                200 -> {
                    val body = response.body?.string() ?: return@withContext emptyList()
                    val segments = json.decodeFromString<List<SponsorSegment>>(body)
                    Log.d(TAG, "获取到 ${segments.size} 个空降片段 for $bvid")
                    normalizeSegments(segments)
                }
                404 -> {
                    Log.d(TAG, "视频 $bvid 没有空降数据")
                    emptyList()
                }
                else -> {
                    Log.w(TAG, "API 返回错误: ${response.code}")
                    emptyList()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取空降片段失败: ${e.message}")
            emptyList()
        }
    }

    private fun normalizeSegments(segments: List<SponsorSegment>): List<SponsorSegment> {
        return segments
            .filter { it.isSkipType && it.endTimeMs > it.startTimeMs }
            .groupBy { it.category }
            .values
            .mapNotNull { candidates ->
                candidates.maxWithOrNull(
                    compareBy<SponsorSegment> { it.locked }
                        .thenBy { it.votes }
                )
            }
            .sortedBy { it.startTimeMs }
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add app/src/main/java/com/tutu/myblbl/feature/player/sponsor/SponsorBlockRepository.kt
git commit -m "feat(sponsor): add SponsorBlockRepository with API client"
```

---

### Task 3: UseCase - SponsorBlockUseCase

**Files:**
- Create: `app/src/main/java/com/tutu/myblbl/feature/player/sponsor/SponsorBlockUseCase.kt`

- [ ] **Step 1: 创建 SponsorBlockUseCase**

```kotlin
package com.tutu.myblbl.feature.player.sponsor

import android.util.Log

class SponsorBlockUseCase {

    companion object {
        private const val TAG = "SponsorBlockUseCase"
        private const val GRACE_PERIOD_MS = 3000L
        private const val SEEK_BACK_THRESHOLD_MS = 2000L
    }

    enum class SkipAction { AUTO_SKIP, SHOW_BUTTON }

    data class SkipResult(
        val segment: SponsorSegment,
        val action: SkipAction
    )

    private var segments: List<SponsorSegment> = emptyList()
    private var nextSegmentIndex: Int = 0
    private val skippedIds = mutableSetOf<String>()
    private var lastPositionMs: Long = 0
    private var lastAutoSkipTime: Long = 0

    suspend fun loadSegments(bvid: String, cid: Long) {
        reset()
        try {
            segments = SponsorBlockRepository.getSegments(bvid, cid)
            nextSegmentIndex = 0
            Log.d(TAG, "加载了 ${segments.size} 个空降片段")
        } catch (e: Exception) {
            Log.w(TAG, "加载空降片段失败: ${e.message}")
        }
    }

    fun checkPosition(positionMs: Long, autoSkip: Boolean): SkipResult? {
        if (segments.isEmpty()) return null

        val isGracePeriod = System.currentTimeMillis() - lastAutoSkipTime < GRACE_PERIOD_MS

        if (!isGracePeriod) {
            if (positionMs < lastPositionMs - SEEK_BACK_THRESHOLD_MS) {
                resetSkippedForSeek(positionMs)
            }
            lastPositionMs = positionMs
        } else {
            if (positionMs > lastPositionMs) {
                lastPositionMs = positionMs
            }
        }

        // 跳过已处理或已过的片段
        while (nextSegmentIndex < segments.size) {
            val candidate = segments[nextSegmentIndex]
            if (candidate.UUID in skippedIds || positionMs > candidate.endTimeMs) {
                nextSegmentIndex++
                continue
            }
            break
        }

        val segment = segments.getOrNull(nextSegmentIndex)
            ?.takeIf { positionMs in it.startTimeMs..it.endTimeMs }
            ?: return null

        val action = if (autoSkip) {
            skippedIds.add(segment.UUID)
            lastAutoSkipTime = System.currentTimeMillis()
            nextSegmentIndex++
            SkipAction.AUTO_SKIP
        } else {
            SkipAction.SHOW_BUTTON
        }

        return SkipResult(segment, action)
    }

    fun skipCurrent(): Long? {
        val segment = segments.getOrNull(nextSegmentIndex) ?: return null
        skippedIds.add(segment.UUID)
        nextSegmentIndex++
        return segment.endTimeMs
    }

    fun dismissCurrent() {
        val segment = segments.getOrNull(nextSegmentIndex) ?: return
        skippedIds.add(segment.UUID)
        nextSegmentIndex++
    }

    fun onUserSeek(positionMs: Long) {
        resetSkippedForSeek(positionMs)
        nextSegmentIndex = segments.indexOfFirst { positionMs <= it.endTimeMs }
            .takeIf { it >= 0 } ?: segments.size
        lastPositionMs = positionMs
        lastAutoSkipTime = 0
    }

    fun getSegments(): List<SponsorSegment> = segments

    fun reset() {
        segments = emptyList()
        nextSegmentIndex = 0
        skippedIds.clear()
        lastPositionMs = 0
        lastAutoSkipTime = 0
    }

    private fun resetSkippedForSeek(positionMs: Long) {
        val keep = skippedIds.filterTo(mutableSetOf()) { id ->
            segments.firstOrNull { it.UUID == id }?.let { positionMs > it.endTimeMs } ?: true
        }
        skippedIds.clear()
        skippedIds.addAll(keep)
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add app/src/main/java/com/tutu/myblbl/feature/player/sponsor/SponsorBlockUseCase.kt
git commit -m "feat(sponsor): add SponsorBlockUseCase with skip logic"
```

---

### Task 4: 设置字段 - PlayerSettingsStore

**Files:**
- Modify: `app/src/main/java/com/tutu/myblbl/feature/player/settings/PlayerSettingsStore.kt`

- [ ] **Step 1: 在 PlayerSettings data class 中添加两个字段**

在 `PlayerSettings` data class 的 `resumePlayback` 字段之后添加：

```kotlin
    val sponsorBlockEnabled: Boolean = false,
    val sponsorBlockAutoSkip: Boolean = true
```

- [ ] **Step 2: 在 PlayerSettingsStore object 中添加 key 常量**

在 `KEY_RESUME_PLAYBACK` 之后添加：

```kotlin
    private const val KEY_SPONSOR_BLOCK_ENABLED = "sponsor_block_enabled"
    private const val KEY_SPONSOR_BLOCK_AUTO_SKIP = "sponsor_block_auto_skip"
```

- [ ] **Step 3: 在 load() 方法的 snapshot 构建中追加新字段**

在 `append(readSetting(KEY_RESUME_PLAYBACK).orEmpty())` 之后追加：

```kotlin
            append("|")
            append(readSetting(KEY_SPONSOR_BLOCK_ENABLED).orEmpty())
            append("|")
            append(readSetting(KEY_SPONSOR_BLOCK_AUTO_SKIP).orEmpty())
```

- [ ] **Step 4: 在 load() 方法的 settings 构建中追加新字段**

在 `resumePlayback = parseToggle(...)` 之后追加：

```kotlin
            sponsorBlockEnabled = parseToggle(
                readSetting(KEY_SPONSOR_BLOCK_ENABLED),
                defaultValue = false
            ),
            sponsorBlockAutoSkip = parseToggle(
                readSetting(KEY_SPONSOR_BLOCK_AUTO_SKIP),
                defaultValue = true
            )
```

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/tutu/myblbl/feature/player/settings/PlayerSettingsStore.kt
git commit -m "feat(sponsor): add sponsor block settings to PlayerSettingsStore"
```

---

### Task 5: ViewModel 集成

**Files:**
- Modify: `app/src/main/java/com/tutu/myblbl/feature/player/VideoPlayerViewModel.kt`

- [ ] **Step 1: 添加 import**

在 import 区域添加：

```kotlin
import com.tutu.myblbl.feature.player.sponsor.SponsorBlockUseCase
import com.tutu.myblbl.feature.player.sponsor.SponsorSegment
```

- [ ] **Step 2: 添加 UseCase 实例和 UI 状态**

在 ViewModel 类体中，`_currentPosition` 声明附近添加：

```kotlin
    private val sponsorBlockUseCase = SponsorBlockUseCase()

    sealed interface SponsorSkipUiState {
        data object Hidden : SponsorSkipUiState
        data class ShowButton(val segment: SponsorSegment) : SponsorSkipUiState
        data class AutoSkipped(val segment: SponsorSegment) : SponsorSkipUiState
    }

    private val _sponsorSkipState = MutableStateFlow<SponsorSkipUiState>(SponsorSkipUiState.Hidden)
    val sponsorSkipState: StateFlow<SponsorSkipUiState> = _sponsorSkipState

    val sponsorSegments: StateFlow<List<SponsorSegment>>
        get() = MutableStateFlow(sponsorBlockUseCase.getSegments())
```

注意：`sponsorSegments` 改为用字段：

```kotlin
    private val _sponsorSegments = MutableStateFlow<List<SponsorSegment>>(emptyList())
    val sponsorSegments: StateFlow<List<SponsorSegment>> = _sponsorSegments
```

- [ ] **Step 3: 在 loadVideoInfo 中加载空降片段**

在 `loadVideoInfo` 方法中，`clearDanmaku()` 调用附近（约第 676 行），添加：

```kotlin
                sponsorBlockUseCase.reset()
                _sponsorSkipState.value = SponsorSkipUiState.Hidden
                _sponsorSegments.value = emptyList()
```

在 `loadVideoInfo` 的 try 块中，弹幕预加载代码之后添加：

```kotlin
                // 加载空降助手片段
                val targetBvid = bvid?.takeIf { it.isNotBlank() }
                if (targetBvid != null && cid > 0L && currentSettings.sponsorBlockEnabled) {
                    viewModelScope.launch {
                        sponsorBlockUseCase.loadSegments(targetBvid, cid)
                        _sponsorSegments.value = sponsorBlockUseCase.getSegments()
                    }
                }
```

注意：这里 `loadVideoInfo` 内部已经有一个 `viewModelScope.launch`，空降片段加载应该在那个 launch 内的合适位置（在 `loadUgcVideoInfo` 或 `loadPgcVideoInfo` 之前即可，因为它是独立的网络请求，不依赖视频详情数据）。

实际插入位置：在第 649 行 `viewModelScope.launch {` 之后、`_isLoading.value = true` 之前，加入空降重置逻辑。在第 693 行 `loadUgcVideoInfo` 调用之前，加入空降加载逻辑（使用独立的 `launch` 不阻塞主流程）。

- [ ] **Step 4: 在 updatePlaybackPosition 中检查跳过**

在 `updatePlaybackPosition` 方法中，`updateSubtitleText(sanitizedPositionMs)` 之后添加：

```kotlin
        checkSponsorBlock(sanitizedPositionMs)
```

添加新方法：

```kotlin
    private fun checkSponsorBlock(positionMs: Long) {
        val settings = currentSettings
        if (!settings.sponsorBlockEnabled) return
        val result = sponsorBlockUseCase.checkPosition(positionMs, settings.sponsorBlockAutoSkip)
        when {
            result == null -> {
                val current = _sponsorSkipState.value
                if (current is SponsorSkipUiState.ShowButton) {
                    _sponsorSkipState.value = SponsorSkipUiState.Hidden
                }
            }
            result.action == SponsorBlockUseCase.SkipAction.AUTO_SKIP -> {
                _sponsorSkipState.value = SponsorSkipUiState.AutoSkipped(result.segment)
                pendingSeekPositionMs = result.segment.endTimeMs
                _playbackRequest.value = PlaybackRequest(
                    mediaSource = null,
                    seekPositionMs = result.segment.endTimeMs,
                    playWhenReady = true,
                    replaceInPlace = true,
                    startupTraceId = PlaybackStartupTrace.NO_TRACE,
                    startupTraceStartElapsedMs = 0L
                )
            }
            result.action == SponsorBlockUseCase.SkipAction.SHOW_BUTTON -> {
                _sponsorSkipState.value = SponsorSkipUiState.ShowButton(result.segment)
            }
        }
    }
```

注意：这里 `_playbackRequest` 用于 seek 可能不合适。更合理的方式是通过 Fragment/View 层直接调用 `player.seekTo()`。后续 Task 7 会在 View 层处理实际的 seek 操作。ViewModel 只需要暴露一个 `seekForSponsor` 方法：

```kotlin
    fun seekForSponsor(positionMs: Long) {
        pendingSeekPositionMs = positionMs
    }
```

- [ ] **Step 5: 添加手动跳过和忽略方法**

```kotlin
    fun sponsorSkip() {
        val targetMs = sponsorBlockUseCase.skipCurrent() ?: return
        _sponsorSkipState.value = SponsorSkipUiState.Hidden
        pendingSeekPositionMs = targetMs
    }

    fun sponsorDismiss() {
        sponsorBlockUseCase.dismissCurrent()
        _sponsorSkipState.value = SponsorSkipUiState.Hidden
    }

    fun sponsorUserSeek(positionMs: Long) {
        sponsorBlockUseCase.onUserSeek(positionMs)
        _sponsorSkipState.value = SponsorSkipUiState.Hidden
    }
```

- [ ] **Step 6: 在切换分P时重置空降状态**

搜索所有调用 `loadVideoInfo` 切换分P的地方（如 `selectEpisode`、`playInteractionChoice`），确保新加载时空降状态自动重置（已在 Step 3 中 `loadVideoInfo` 内处理）。

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/com/tutu/myblbl/feature/player/VideoPlayerViewModel.kt
git commit -m "feat(sponsor): integrate SponsorBlockUseCase into VideoPlayerViewModel"
```

---

### Task 6: 跳过按钮 UI - SponsorSkipOverlayView

**Files:**
- Create: `app/src/main/java/com/tutu/myblbl/feature/player/sponsor/SponsorSkipOverlayView.kt`

- [ ] **Step 1: 创建 SponsorSkipOverlayView**

这是一个叠加在播放器上的 View，显示跳过按钮或自动跳过 Toast。

```kotlin
package com.tutu.myblbl.feature.player.sponsor

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

class SponsorSkipOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val skipContainer: LinearLayout
    private val categoryLabel: TextView
    private val skipButton: TextView
    private val dismissButton: TextView
    private val toastView: TextView

    private var onSkipListener: (() -> Unit)? = null
    private var onDismissListener: (() -> Unit)? = null
    private var toastHideRunnable: Runnable? = null

    init {
        clipChildren = false
        clipToPadding = false

        // Toast 视图（顶部）
        toastView = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = GradientDrawable().apply {
                setColor(0xE000C853.toInt())
                cornerRadius = dp(20).toFloat()
            }
            visibility = GONE
        }
        addView(toastView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
            topMargin = dp(60)
        })

        // 跳过按钮容器（右下角）
        skipContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                setColor(0xCC000000.toInt())
                cornerRadius = dp(12).toFloat()
            }
            visibility = GONE
        }

        // 类别标签
        categoryLabel = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(0xFFFFA500.toInt())
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }
        skipContainer.addView(categoryLabel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(8) })

        // 跳过按钮
        skipButton = TextView(context).apply {
            text = "跳过 ▶"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                setColor(0xFF00C853.toInt())
                cornerRadius = dp(8).toFloat()
            }
            setOnClickListener { onSkipListener?.invoke() }
        }
        skipContainer.addView(skipButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(8) })

        // 忽略按钮
        dismissButton = TextView(context).apply {
            text = "✕"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(0x99FFFFFF.toInt())
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setOnClickListener { onDismissListener?.invoke() }
        }
        skipContainer.addView(dismissButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        addView(skipContainer, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.END or Gravity.BOTTOM
            marginEnd = dp(16)
            bottomMargin = dp(80)
        })
    }

    fun setListeners(onSkip: () -> Unit, onDismiss: () -> Unit) {
        onSkipListener = onSkip
        onDismissListener = onDismiss
    }

    fun showSkipButton(segment: SponsorSegment) {
        categoryLabel.text = segment.categoryName()
        skipContainer.visibility = VISIBLE
        skipContainer.alpha = 0f
        skipContainer.animate()
            .alpha(1f)
            .setDuration(200)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    fun hideSkipButton() {
        if (skipContainer.visibility != VISIBLE) return
        skipContainer.animate()
            .alpha(0f)
            .setDuration(200)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    skipContainer.visibility = GONE
                    skipContainer.animate().setListener(null)
                }
            })
            .start()
    }

    fun showAutoSkipToast(segment: SponsorSegment) {
        toastHideRunnable?.let { removeCallbacks(it) }
        toastView.text = "已跳过: ${segment.categoryName()}"
        toastView.visibility = VISIBLE
        toastView.alpha = 0f
        toastView.animate()
            .alpha(1f)
            .setDuration(150)
            .start()

        val hideRunnable = Runnable {
            toastView.animate()
                .alpha(0f)
                .setDuration(300)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        toastView.visibility = GONE
                        toastView.animate().setListener(null)
                    }
                })
                .start()
        }
        toastHideRunnable = hideRunnable
        postDelayed(hideRunnable, 2000)
    }

    fun hideAll() {
        hideSkipButton()
        toastHideRunnable?.let { removeCallbacks(it) }
        toastView.visibility = GONE
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add app/src/main/java/com/tutu/myblbl/feature/player/sponsor/SponsorSkipOverlayView.kt
git commit -m "feat(sponsor): add SponsorSkipOverlayView for skip button and toast"
```

---

### Task 7: 进度条标记 - SponsorProgressMarkerView

**Files:**
- Create: `app/src/main/java/com/tutu/myblbl/feature/player/sponsor/SponsorProgressMarkerView.kt`

- [ ] **Step 1: 创建 SponsorProgressMarkerView**

这是一个叠加在进度条上方的 View，用半透明彩色矩形标记片段位置。

```kotlin
package com.tutu.myblbl.feature.player.sponsor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.View

class SponsorProgressMarkerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var segments: List<SponsorSegment> = emptyList()
    private var durationMs: Long = 0L
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setSegments(segments: List<SponsorSegment>) {
        this.segments = segments
        invalidate()
    }

    fun setDuration(durationMs: Long) {
        this.durationMs = durationMs
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (segments.isEmpty() || durationMs <= 0L) return

        val width = width.toFloat()
        val height = height.toFloat()

        for (segment in segments) {
            val startRatio = segment.startTimeMs.toFloat() / durationMs
            val endRatio = segment.endTimeMs.toFloat() / durationMs
            val left = startRatio * width
            val right = endRatio * width

            paint.color = (segment.categoryColor() and 0x00FFFFFFL).toInt() or 0x99000000.toInt()
            canvas.drawRect(left, 0f, right, height, paint)
        }
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add app/src/main/java/com/tutu/myblbl/feature/player/sponsor/SponsorProgressMarkerView.kt
git commit -m "feat(sponsor): add SponsorProgressMarkerView for progress bar markers"
```

---

### Task 8: 集成 UI 到 MyPlayerView 和 MyPlayerControlView

**Files:**
- Modify: `app/src/main/java/com/tutu/myblbl/feature/player/view/MyPlayerView.kt`
- Modify: `app/src/main/java/com/tutu/myblbl/feature/player/view/MyPlayerControlView.kt`

- [ ] **Step 1: 在 MyPlayerView 中添加 SponsorSkipOverlayView**

在 `MyPlayerView` 类中，`specialDmkOverlayView` 声明之后添加字段：

```kotlin
    private var sponsorSkipOverlay: SponsorSkipOverlayView? = null
```

在 `init` 块中，`setupYouTubeOverlay()` 之后添加：

```kotlin
        sponsorSkipOverlay = SponsorSkipOverlayView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        addView(sponsorSkipOverlay)
```

添加公开方法：

```kotlin
    fun setSponsorListeners(onSkip: () -> Unit, onDismiss: () -> Unit) {
        sponsorSkipOverlay?.setListeners(onSkip, onDismiss)
    }

    fun showSponsorSkipButton(segment: SponsorSegment) {
        sponsorSkipOverlay?.showSkipButton(segment)
    }

    fun hideSponsorSkipButton() {
        sponsorSkipOverlay?.hideSkipButton()
    }

    fun showSponsorAutoSkipToast(segment: SponsorSegment) {
        sponsorSkipOverlay?.showAutoSkipToast(segment)
    }

    fun hideSponsorAll() {
        sponsorSkipOverlay?.hideAll()
    }
```

添加 import：

```kotlin
import com.tutu.myblbl.feature.player.sponsor.SponsorSkipOverlayView
import com.tutu.myblbl.feature.player.sponsor.SponsorSegment
```

- [ ] **Step 2: 在 MyPlayerControlView 中添加 SponsorProgressMarkerView**

在 `MyPlayerControlView` 类中，`timeBar` 初始化之后添加：

```kotlin
    private var sponsorMarkerView: SponsorProgressMarkerView? = null
```

在 `initViews` 方法中，`timeBar = findViewById(R.id.exo_progress)` 之后添加：

```kotlin
        // 在 timeBar 上方叠加空降标记
        sponsorMarkerView = SponsorProgressMarkerView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(3)).apply {
                bottomMargin = dp(0)
                gravity = Gravity.BOTTOM
            }
        }
        // 将 marker 添加为 timeBar 的同级，位于 timeBar 正上方
        (timeBar.parent as? ViewGroup)?.addView(sponsorMarkerView)
```

添加方法：

```kotlin
    fun setSponsorSegments(segments: List<SponsorSegment>) {
        sponsorMarkerView?.setSegments(segments)
    }

    fun setSponsorDuration(durationMs: Long) {
        sponsorMarkerView?.setDuration(durationMs)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
```

添加 import：

```kotlin
import com.tutu.myblbl.feature.player.sponsor.SponsorProgressMarkerView
import com.tutu.myblbl.feature.player.sponsor.SponsorSegment
```

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/tutu/myblbl/feature/player/view/MyPlayerView.kt
git add app/src/main/java/com/tutu/myblbl/feature/player/view/MyPlayerControlView.kt
git commit -m "feat(sponsor): integrate overlay and progress marker views"
```

---

### Task 9: Fragment 绑定 - 连接 ViewModel 和 View

**Files:**
- Modify: `app/src/main/java/com/tutu/myblbl/feature/player/VideoPlayerFragment.kt` (或播放器 Activity/Fragment)

- [ ] **Step 1: 找到播放器 Fragment/Activity 中监听 ViewModel 状态的位置**

搜索 `VideoPlayerFragment.kt` 中 `currentPosition` 或 `updatePlaybackPosition` 的收集逻辑。

- [ ] **Step 2: 添加 sponsorSkipState 的收集**

在 Fragment 的 `onViewCreated` 或状态收集区域添加：

```kotlin
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.sponsorSkipState.collect { state ->
                when (state) {
                    is VideoPlayerViewModel.SponsorSkipUiState.Hidden -> {
                        playerView.hideSponsorSkipButton()
                    }
                    is VideoPlayerViewModel.SponsorSkipUiState.ShowButton -> {
                        playerView.showSponsorSkipButton(state.segment)
                    }
                    is VideoPlayerViewModel.SponsorSkipUiState.AutoSkipped -> {
                        playerView.hideSponsorSkipButton()
                        playerView.showSponsorAutoSkipToast(state.segment)
                        player.player?.seekTo(state.segment.endTimeMs)
                    }
                }
            }
        }
```

- [ ] **Step 3: 设置跳过/忽略回调**

```kotlin
        playerView.setSponsorListeners(
            onSkip = { viewModel.sponsorSkip() },
            onDismiss = { viewModel.sponsorDismiss() }
        )
```

- [ ] **Step 4: 收集片段数据更新进度条标记**

```kotlin
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.sponsorSegments.collect { segments ->
                playerView.controller?.setSponsorSegments(segments)
            }
        }
```

- [ ] **Step 5: 在用户手动 seek 时通知 ViewModel**

找到 Fragment/View 中用户 seek 的位置（scrub stop、快进快退），调用：

```kotlin
viewModel.sponsorUserSeek(positionMs)
```

- [ ] **Step 6: 提交**

```bash
git add <fragment-file>
git commit -m "feat(sponsor): bind ViewModel state to player overlay views"
```

---

### Task 10: 设置菜单 - 空降助手开关

**Files:**
- Modify: `app/src/main/java/com/tutu/myblbl/feature/player/view/MyPlayerSettingView.kt`
- Modify: `app/src/main/java/com/tutu/myblbl/feature/player/view/MyPlayerSettingMenuBuilder.kt`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: 在 MyPlayerSettingView 中添加常量**

在 `ITEM_DM_SMART_SHIELD` 之后添加：

```kotlin
        internal const val ITEM_SPONSOR_BLOCK_ENABLED = 110
        internal const val ITEM_SPONSOR_BLOCK_AUTO_SKIP = 111
```

- [ ] **Step 2: 在 MyPlayerSettingMenuBuilder 的 PanelState 中添加字段**

```kotlin
        val sponsorBlockEnabled: Boolean = false,
        val sponsorBlockAutoSkip: Boolean = true
```

- [ ] **Step 3: 在 buildMainMenu 中添加空降助手设置项**

在 `ITEM_DM_SETTING` 之后添加：

```kotlin
            PlayerSettingRow.Item(
                id = MyPlayerSettingView.ITEM_SPONSOR_BLOCK_ENABLED,
                title = "空降助手",
                value = state.sponsorBlockEnabled.toOpenCloseLabel(),
                iconRes = R.drawable.ic_dm_enable  // 复用图标或创建新图标
            ),
```

- [ ] **Step 4: 在 buildDmChoiceMenu 中处理新设置项**

在 `ITEM_DM_SMART_SHIELD` 分支之后添加：

```kotlin
            MyPlayerSettingView.ITEM_SPONSOR_BLOCK_ENABLED -> buildBooleanChoiceMenu(
                menuKey = MyPlayerSettingView.ITEM_SPONSOR_BLOCK_ENABLED,
                title = "空降助手",
                currentValue = state.sponsorBlockEnabled
            )
            MyPlayerSettingView.ITEM_SPONSOR_BLOCK_AUTO_SKIP -> buildBooleanChoiceMenu(
                menuKey = MyPlayerSettingView.ITEM_SPONSOR_BLOCK_AUTO_SKIP,
                title = "自动跳过",
                currentValue = state.sponsorBlockAutoSkip
            )
```

- [ ] **Step 5: 在 MyPlayerSettingView 的选择回调中处理新设置项**

找到处理 `ITEM_DM_*` 的地方（`onItemClick` 或类似方法），添加 `ITEM_SPONSOR_BLOCK_ENABLED` 和 `ITEM_SPONSOR_BLOCK_AUTO_SKIP` 的处理，通过 `PlayerSettingsStore` 保存到 DataStore。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/tutu/myblbl/feature/player/view/MyPlayerSettingView.kt
git add app/src/main/java/com/tutu/myblbl/feature/player/view/MyPlayerSettingMenuBuilder.kt
git commit -m "feat(sponsor): add sponsor block settings to player menu"
```

---

### Task 11: 编译验证和修复

- [ ] **Step 1: 编译项目**

Run: `./gradlew assembleDebug 2>&1 | tail -50`
Expected: BUILD SUCCESSFUL

如果编译失败，根据错误信息修复。

- [ ] **Step 2: 修复编译问题**

常见问题：
- import 遗漏：补充缺失的 import
- 类型不匹配：调整方法签名
- View 层级问题：调整 addView 的时机和参数

- [ ] **Step 3: 提交修复**

```bash
git add -A
git commit -m "fix(sponsor): resolve compilation issues"
```

# 空降助手 (SponsorBlock) 功能设计

## 概述

基于 BilibiliSponsorBlock 社区数据，自动识别并跳过视频中的恰饭广告、开场动画、片尾动画片段。采用直接集成方式（无插件系统），通过 UseCase 层封装业务逻辑。

## 需求

- 支持自动跳过和手动跳过两种模式，设置中可切换
- 支持 3 种片段类别：恰饭(sponsor)、开场(intro)、片尾(outro)
- 进度条上用彩色标记显示片段位置
- 默认关闭，需手动开启

## 架构

### 分层

```
UI Layer (Compose + View)
├── SponsorSkipOverlay       - 跳过按钮叠加层
├── SponsorSkipToast         - 自动跳过提示
├── SponsorProgressBar       - 进度条标记
└── PlayerSettingsScreen     - 设置项

ViewModel Layer
└── VideoPlayerViewModel     - 调用 UseCase，管理 UI 状态

UseCase Layer
└── SponsorBlockUseCase      - 片段加载、跳过判断、防抖

Repository Layer
└── SponsorBlockRepository   - API 请求

Data Layer
└── SponsorSegment           - 数据模型
```

### 数据流

```
视频加载 → ViewModel.loadVideo()
  → SponsorBlockUseCase.loadSegments(bvid, cid)
    → SponsorBlockRepository.getSegments(bvid, cid, categories)
      → GET https://bsbsb.top/api/skipSegments
      → 解析为 List<SponsorSegment>

位置更新 → _currentPosition 变化
  → SponsorBlockUseCase.checkPosition(positionMs, autoSkip)
    → 查找当前位置匹配的片段
    → 返回 SkipResult(segment, action)

  action == AUTO_SKIP → player.seekTo(segment.endTimeMs)
  action == SHOW_BUTTON → 更新 UI 状态显示跳过按钮
```

## 数据层

### API

- **URL**: `https://bsbsb.top/api/skipSegments`
- **Method**: GET
- **参数**: `videoID`(bvid), `cid`(可选), `category`(每类一个，重复参数)
- **请求类别**: `sponsor`, `intro`, `outro`

### 响应格式

```json
[
  {
    "segment": [15.5, 30.2],
    "UUID": "uuid-string",
    "category": "sponsor",
    "actionType": "skip",
    "locked": 0,
    "votes": 10,
    "videoDuration": 600.0
  }
]
```

### 数据模型

```kotlin
data class SponsorSegment(
    val segment: List<Float>,
    val UUID: String,
    val category: String,
    val actionType: String,
    val locked: Int = 0,
    val votes: Int = 0,
    val videoDuration: Float = 0f
) {
    val startTimeMs: Long get() = (segment[0] * 1000).toLong()
    val endTimeMs: Long get() = (segment[1] * 1000).toLong()
}
```

### Repository

`SponsorBlockRepository` - 使用 OkHttp 请求 API，按 locked + votes 排序去重。

## 业务层

### SponsorBlockUseCase

```kotlin
class SponsorBlockUseCase(private val repository: SponsorBlockRepository) {

    enum class SkipAction { AUTO_SKIP, SHOW_BUTTON, NONE }

    data class SkipResult(
        val segment: SponsorSegment,
        val action: SkipAction
    )

    suspend fun loadSegments(bvid: String, cid: Long)
    fun checkPosition(positionMs: Long, autoSkip: Boolean): SkipResult?
    fun skipCurrent(): Long?
    fun dismissCurrent()
    fun getSegments(): List<SponsorSegment>
    fun reset()
}
```

### 关键逻辑

1. **防抖**：自动跳过后 3 秒内不触发回拉检测
2. **回拉检测**：位置后退 > 2 秒时重置已跳过记录
3. **片段排序**：按开始时间排序，线性扫描匹配（片段数量少，无需二分）
4. **去重**：同位置片段取 votes 最高且 locked 的

## ViewModel 集成

在 `VideoPlayerViewModel` 中：

- 添加 `SponsorBlockUseCase` 实例
- 视频加载时调用 `loadSegments(bvid, cid)`
- 复用现有的 `_currentPosition` StateFlow 监听位置变化
- 添加 `_sponsorSkipState` StateFlow 管理 UI 状态
- 跳过时调用现有的 `player.seekTo()`

### 新增状态

```kotlin
sealed interface SponsorSkipUiState {
    data object Hidden : SponsorSkipUiState
    data class ShowButton(val segment: SponsorSegment) : SponsorSkipUiState
    data class AutoSkipped(val segment: SponsorSegment) : SponsorSkipUiState
}
```

## UI 层

### 跳过按钮

叠加在 `MyPlayerView` 右下角：
- 类别标签（对应颜色：恰饭=橙色、开场=蓝色、片尾=紫色）
- 绿色"跳过"按钮
- 白色关闭按钮
- 淡入淡出动画

### 自动跳过 Toast

屏幕顶部短暂显示："已跳过: 恰饭片段"

### 进度条标记

在 SeekBar 上叠加半透明彩色矩形段，位置根据片段起止时间计算。

### 设置项

在播放器设置中增加：
- 空降助手总开关（默认关）
- 自动跳过开关（默认开）
- 链接到 bsbsb.top

## 设置存储

复用现有的 `PlayerSettingsStore` (DataStore)，新增字段：
- `sponsorBlockEnabled: Boolean` (默认 false)
- `sponsorBlockAutoSkip: Boolean` (默认 true)

## 错误处理

- API 请求失败：静默失败，不影响播放
- 无片段数据：正常播放，无任何 UI 显示
- 网络超时：5 秒超时，取消请求

## 不做的事

- 不做片段投票/提交功能
- 不做插件系统
- 不做片段预览交互
- 不做 Poi（兴趣点）类型

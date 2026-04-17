# 播放器 UI 重构实施计划

> 目标：将播放器 UI 从"多 owner 并存"重构为"一个协调器 + 多个纯渲染层"，消除控制层、seek overlay、fragment 外层进度条、面板层之间的显隐冲突。

---

## 一、核心约束

以下 4 条约束在任意阶段都不可破坏：

1. **单一状态源** — 所有播放 UI 状态只由 `PlaybackUiCoordinator` 决定
2. **单一下边栏占位** — 任意时刻底部只能有一个 occupant
3. **单一时间线** — 任意时刻只允许一根进度条可见
4. **单一焦点所有者** — 任意时刻只允许一个焦点持有者

---

## 二、状态模型定义

### 2.1 状态切片

| 状态切片 | 值域 | 作用 |
|---|---|---|
| `chromeState` | `Hidden / ProgressOnly / Full` | 控制主控制层显示级别。`ProgressOnly` 保留"仅进度条 2 秒过渡"的现有体验 |
| `bottomOccupant` | `None / SlimTimeline / FullChrome / BottomPanel` | 统一管理底部占位，杜绝多进度条 |
| `seekState` | `None / TapSeek / HoldSeek / SpeedMode / SwipeSeek / DoubleTapSeek` | 统一所有 seek 会话。`SpeedMode` 为右长按倍速播放，不改变播放位置 |
| `panelState` | `None / Settings / Episode / Related / Action / Owner / NextUp / Interaction / ResumeHint` | 统一外层面板和弹窗策略 |
| `focusOwner` | `PlayerRoot / Controller / Panel / Dialog / Interaction` | 统一方向键和返回键归属 |
| `hudState` | `Ambient / Chrome / Seek / Panel / Completion` | 驱动时钟、字幕 inset、调试信息 |

### 2.2 bottomOccupant 与 chromeState 映射

| chromeState | bottomOccupant | 可见时间线 |
|---|---|---|
| `Full` | `FullChrome` | `FullTimeline`（controller 内 DefaultTimeBar） |
| `ProgressOnly` | `FullChrome` | `FullTimeline`（仅进度条部分） |
| `Hidden` | `SlimTimeline` 或 `None` | `SlimTimeline`（fragment 外层 bottomProgressBar）或无 |
| 任意 | `BottomPanel` | 无（面板占位） |

### 2.3 seekState 对时间线的影响

`seekState != None` 时，当前可见时间线切到 `PreviewMode`，显示 target position、ghost progress 或 marker，但不切换时间线实例。

### 2.4 panelState 优先级

```
Dialog > Interaction > ResumeHint > NextUp > Settings / Episode / Related / Action / Owner（互斥）
```

- `panelState != None` 时，seek session 不启动
- `NextUp` 出现时，`bottomOccupant` 由 coordinator 决定，不由 `VideoPlayerAutoPlayController` 独立控制

---

## 三、分阶段实施

### Phase 1：状态建模期

**目标**：扩展 `PlayerOverlayCoordinator` 为 `PlaybackUiCoordinator`，建立完整的状态模型，不改 UI 行为，只让外层能读到统一语义状态。

#### 3.1.1 新建 `PlaybackUiCoordinator`

```
包路径：com.tutu.myblbl.feature.player
```

- 定义上述 6 个状态切片的 enum 和 state holder
- 提供 `val chromeState: ChromeState` 等只读属性
- 提供 `fun transition(event: UiEvent)` 统一状态变迁入口
- 所有状态变迁在一个函数内完成，禁止外部直接 setter

#### 3.1.2 定义事件类型

```kotlin
sealed class UiEvent {
    object ToggleChrome : UiEvent()
    object ChromeTimeout : UiEvent()
    object SeekStarted : UiEvent()
    object SeekFinished : UiEvent()
    object SeekCancelled : UiEvent()
    object PanelOpened(val panel: PanelType) : UiEvent()
    object PanelClosed : UiEvent()
    object PlaybackEnded : UiEvent()
    object PlaybackResumed : UiEvent()
    object InteractionStarted : UiEvent()
    object InteractionEnded : UiEvent()
    object ResumeHintShown : UiEvent()
    object ResumeHintDismissed : UiEvent()
    // ...
}
```

#### 3.1.3 在现有代码中埋入状态同步（double-check 模式）

- `MyPlayerControlViewLayoutManager.setUxState()` 内增加对 `PlaybackUiCoordinator.chromeState` 的同步写入
- `VideoPlayerFragment.renderControllerChrome()` 内增加对 `bottomOccupant` 和 `hudState` 的同步写入
- **`PlayerActivity.renderControllerChrome()` 同步执行相同写入**（PlayerActivity 与 VideoPlayerFragment 有完全相同的 chrome 联动逻辑，必须同步改造）
- `YouTubeOverlay.ensureOverlayVisible()` / `hideOverlayImmediately()` 内增加对 `seekState` 的同步写入
- `PlayerOverlayCoordinator.onRelatedPanelShown()` / `onRelatedPanelHidden()` 增加对 `panelState` 的同步写入
- `VideoPlayerOverlayController` 中各 dialog/panel 的 `keepControllerVisibleForOverlay()` / `restoreControllerAfterOverlay()` 调用处增加对 `panelState` 的同步写入
- 旧逻辑不变，新状态仅作为镜像存在，用于后续阶段切换时的校验

#### 3.1.4 验收标准

- [ ] `PlaybackUiCoordinator` 可被任意层读取当前状态
- [ ] 所有现有 UI 行为不变
- [ ] 状态镜像与实际 UI 可见性一致性可通过 debug 日志验证

---

### Phase 2：时间线合并期

**目标**：收敛三根进度条为"一套 timeline 数据源，两种皮肤，三种模式"。

#### 3.2.1 新建 `TimelineRenderer` 接口

```kotlin
interface TimelineRenderer {
    fun show(positionMs: Long, durationMs: Long)
    fun showPreview(targetPositionMs: Long, durationMs: Long)
    fun hide()
    fun isActive(): Boolean
}
```

#### 3.2.2 实现两个 renderer

| Renderer | 绑定 View | 何时激活 |
|---|---|---|
| `FullTimelineRenderer` | `MyPlayerControlView` 内的 `DefaultTimeBar` | `bottomOccupant = FullChrome` |
| `SlimTimelineRenderer` | `VideoPlayerFragment` 内的 `bottomProgressBar` | `bottomOccupant = SlimTimeline` |

> **与现有 coordinator 的关系**：`PlayerControlTimelineCoordinator` 已实现 multi-window 时间线数据计算（position offset、duration 累加、formatTime 等），`TimelineRenderer` 不应重新实现这些逻辑。`FullTimelineRenderer` 应委托 `PlayerControlTimelineCoordinator` 获取 position/duration，只负责 UI 层面的 show/hide/preview 渲染。`VideoPlayerProgressCoordinator` 负责定时轮询 player 进度并通过回调分发，其 `onProgressPublished` 回调应同时驱动两个 renderer 的 `show()`，由 renderer 自行判断是否 active。

#### 3.2.3 改造 `bottomOccupant` 切换逻辑

在 `PlaybackUiCoordinator` 内：

- `bottomOccupant` 变化时，旧 renderer 调 `hide()`，新 renderer 调 `show()`
- `seekState` 变化时，当前 renderer 调 `showPreview()` 或恢复 `show()`
- 废弃 `YouTubeOverlay.progressBar`（`yt_overlay.xml`），其显示逻辑由 `bottomOccupant` 规则接管
- 废弃 `MyPlayerView.dispatchSeekPreviewState()` → `VideoPlayerFragment.onSeekPreviewStateChanged()` 这条跨层回调链，改为 `TimelineRenderer` 直接读 coordinator 状态

> **`progressBar` 迁移调用链**：`YouTubeOverlay` 中以下调用点需逐个迁移到 renderer：
> - `updateProgress(positionMs, durationMs)` — 被 `seekTo()`、`handleDoubleTap()`、`showSwipeSeek()`、`showControllerSeek()`、`showSpeedSeek()` 调用，设置 `progressBar.max/progress`。迁移后，这些调用方改为写 coordinator 的 `seekPreviewTarget`，由当前 active renderer 读取并渲染。
> - `updateProgressBarVisibility()` — 由 `ensureOverlayVisible()` / `hideOverlayImmediately()` / `setPersistentBottomProgressEnabled()` 调用。迁移后由 coordinator 根据 `bottomOccupant` 直接控制 renderer 的 show/hide，`YouTubeOverlay` 不再持有 `progressBar` 引用。

#### 3.2.4 删除跨层进度条联动

> **定位策略**：以下行号仅供参考，实际重构时以符号名定位，因代码会随编辑偏移。

| 删除目标 | 文件 | 行为 |
|---|---|---|
| `bottomProgressSeekPreviewActive / Position / Duration` | `VideoPlayerFragment.kt` / `PlayerActivity.kt` | 改由 `SlimTimelineRenderer` 管理 |
| `hiddenSeekPreviewActive / Position / Duration` | `MyPlayerView.kt` | 改由 coordinator + renderer 管理 |
| `renderBottomProgressBar()` 中所有 seek preview 判断 | `VideoPlayerFragment.kt` / `PlayerActivity.kt` | 简化为 `SlimTimelineRenderer.show()` |
| `YouTubeOverlay.updateProgressBarVisibility()` | `YouTubeOverlay.kt` | 废弃，由 coordinator 控制 |
| `clearBottomProgressSeekPreview()` | `VideoPlayerFragment.kt` / `PlayerActivity.kt` | 改由 `SlimTimelineRenderer` 内部管理 |

#### 3.2.5 验收标准

- [ ] 任意时刻屏幕上最多一根时间线
- [ ] seek 期间时间线平滑切到 preview 模式，不闪烁
- [ ] controller 显示/隐藏时，时间线正确切换 Full/Slim
- [ ] 面板打开时，时间线让位给面板
- [ ] `showBottomProgressBar` 只影响 `SlimTimeline` 是否在 `chromeState=Hidden` 时可见

---

### Phase 3：Seek 会话重构期

**目标**：统一所有 seek 为 `SeekSession`，方向切换不 restore controller。

#### 3.3.1 新建 `SeekSession`

```kotlin
class SeekSession(
    private val coordinator: PlaybackUiCoordinator,
    private val player: ExoPlayer,
    private val timelineRenderer: TimelineRenderer,
    private val danmakuSync: (Long) -> Unit
) {
    fun startTapSeek(forward: Boolean, seekMs: Long)
    fun startHoldSeek(forward: Boolean)
    fun startSwipeSeek(startPositionMs: Long)
    fun updateSwipeTarget(deltaX: Float, width: Float, durationMs: Long)
    fun changeDirection(forward: Boolean)
    fun commit()
    fun cancel()
}
```

#### 3.3.2 统一 seek 行为

| 场景 | 现状 | 目标 |
|---|---|---|
| 左右短按 | `ProgressiveSeekHelper.doSingleSeek()` 即时 `seekTo` | `TapSeek`：即时 commit，短暂反馈后消失 |
| 左长按 | `doRewindTick()` 每次 ACTION_DOWN 都 `seekTo` | `HoldSeek`：每次 DOWN commit 一次离散跳 |
| 右长按 | 倍速播放 `enterSpeedMode()` | 独立为 `SpeedMode`（见 3.3.2a） |
| 双击 | `YouTubeOverlay.seekTo()` 即时 commit | `DoubleTapSeek`：即时 commit，overlay 纯视觉反馈 |
| 滑动 | `handleSwipeSeekTouch()` 松手时 commit | `SwipeSeek`：松手时一次性 commit |

##### 3.3.2a `SpeedMode` 决策

**结论：保留为独立 `SpeedMode`，不合并入 `HoldSeek`。**

理由：
- 现有 `ProgressiveSeekHelper` 中右长按实现的是速度递进播放（2x→4x→8x→16x，每 1.5s 自动升档），不改变播放位置，与左长按的离散回退语义完全不同。
- `SeekSession` 的 `startHoldSeek()` / `commit()` 语义是"累积偏移量后一次性 seekTo"，不适用于速度递进场景。
- `SpeedMode` 独立后，`SeekSession` 新增 `fun startSpeedMode()` / `fun finishSpeedMode()` / `fun stepUpSpeed()`，内部只改 `player.playbackParameters`，不影响 timeline position。
- `seekState` 值域补充 `SpeedMode` 枚举值，与 `HoldSeek` 互斥。

改动影响：
- `seekState` 值域更新为 `None / TapSeek / HoldSeek / SpeedMode / SwipeSeek / DoubleTapSeek`
- `SeekSession` 新增三个方法
- `YouTubeOverlay.showSpeedSeek()` / `finishSpeedSeek()` 保持不变，仍负责倍速 UI 渲染

#### 3.3.3 方向切换不 restore controller

- `SeekSession.changeDirection()` 只改内部方向标志，更新 preview UI
- 不调 `restoreControllerAfterGesture()`
- 不调 `setUseController(false / true)`

#### 3.3.4 弹幕同步时机调整

- **旧**：每次 `seekTo` 都调 `syncDanmakuPosition(forceSeek = true)`
- **新**：只在 `SeekSession.commit()` 和 `TapSeek` 即时 commit 时调
- `HoldSeek` 的离散回退：每次 DOWN 仍然 commit + sync（保持即时反馈）

#### 3.3.5 废弃 `ProgressiveSeekHelper`

整个内部类（`MyPlayerView.kt:1217-1411`）替换为 `SeekSession` 调用。

#### 3.3.6 验收标准

- [ ] 长按右、长按左、长按中途切方向，不会把 full controller 误拉出来
- [ ] seek 期间 timeline preview 平滑跟随 target
- [ ] seek 结束后弹幕位置正确
- [ ] 不因频繁 seekTo 造成缓冲抖动
- [ ] `YouTubeOverlay` 只负责视觉渲染，不调 `setUseController()`

---

### Phase 4：焦点治理期

**目标**：所有焦点操作通过 coordinator 统一管理。

#### 3.4.1 `focusOwner` 切换规则

| 事件 | focusOwner 变化 | 焦点操作 |
|---|---|---|
| controller 隐藏完成 | → `PlayerRoot` | 焦点回收到 `MyPlayerView`（需设 `isFocusable = true`） |
| controller 显示 | → `Controller` | `requestPlayPauseFocus()` |
| 面板打开 | → `Panel` | 面板内首个可聚焦 view |
| dialog 打开 | → `Dialog` | dialog 内默认焦点 |
| interaction 激活 | → `Interaction` | interaction view |
| panel/dialog 关闭 | 恢复到上一级 | 由 coordinator 根据 `focusRestoreTarget` 决定 |

#### 3.4.2 `MyPlayerView.isFocusable` 改造

- 设置 `isFocusable = true`
- 确保 controller GONE 后焦点不会留在已 GONE 的子树

> **风险细节**：`MyPlayerView` 当前在 `init` 块中设 `isFocusable = false`（MyPlayerView.kt），但 `fragment_video_player.xml` 中通过 XML 属性 `android:focusable="true"` 覆盖了该值。改为 `isFocusable = true` 后需验证：
> 1. `onTouchEvent` 的行为变化 — `isFocusable = true` 时，View 会更积极地消费 `ACTION_DOWN` 事件。当前 `onTouchEvent` 中 `gestureDetector.onTouchEvent(event)` 后直接 `return true`，需确认不会因此拦截应该传递给子 view 的事件。
> 2. `performClick()` 当前调用 `toggleControllerVisibility()`，focusable 后 `performClick` 可能被系统更频繁调用，需确保不会重复触发。
> 3. `dispatchKeyEvent` 的回传路径 — focusable 后，MyPlayerView 会成为 key event 的候选目标，需确认不会拦截应交给 controller 的按键。
>
> **建议**：Phase 4 开始前，先创建一个只改 `isFocusable = true` 的分支，全量跑一遍触摸和遥控器场景的冒烟测试，确认无退化后再继续后续焦点改造。

#### 3.4.3 `PlayerControlFocusCoordinator` 整合

现有 `PlayerControlFocusCoordinator` 已实现 controller 内部 16 种 `FocusTarget`（PLAY_PAUSE、PREVIOUS、NEXT、REWIND、FAST_FORWARD、DM_SWITCH、SETTINGS、EPISODE、MORE、OWNER、SUBTITLE、RELATED、REPEAT、LIVE_SETTINGS、CLOSE、TIME_BAR）的记录和恢复，以及 DPad 方向键路由。

整合策略：
- **保留** `PlayerControlFocusCoordinator` 作为 controller 内部焦点管理的实现细节，不废弃
- `PlaybackUiCoordinator` 的 `focusOwner` 只管顶层归属（PlayerRoot / Controller / Panel / Dialog / Interaction），不感知 controller 内部具体哪个 button
- `focusOwner` 从非 Controller 态恢复到 Controller 时，委托 `PlayerControlFocusCoordinator.restoreRememberedFocus()` 选择具体 button
- `PlayerOverlayCoordinator.FocusTarget`（5 种，RELATED_BUTTON 等）废弃，统一使用 `PlayerControlFocusCoordinator` 的 16 种 FocusTarget
- `VideoPlayerOverlayController` 中散落的 `playerView.rememberCurrentFocusTarget()` / `playerView.restoreRememberedFocus()` 改为通过 coordinator 路由到 `PlayerControlFocusCoordinator`

#### 3.4.4 返回键优先级链

```
Dialog → Panel → Interaction → ResumeHint → Controller → ExitPrompt
```

由 `PlaybackUiCoordinator.handleBackPress()` 统一实现，替代现有 `PlayerOverlayCoordinator.handleBackPress()` 的 callback 模式。

#### 3.4.5 废弃焦点直接操作

| 删除目标 | 替代 |
|---|---|
| `controller?.rememberCurrentFocusTarget()` | coordinator 维护 `focusRestoreTarget` |
| `controller?.restoreRememberedFocus()` | coordinator 统一恢复 |
| `playerView.requestXxxFocus()` 散落调用 | coordinator 通过 `focusRestoreTarget` 路由 |

#### 3.4.6 验收标准

- [ ] seek UI 收起后，方向键永远有响应
- [ ] 关闭 panel/dialog 后焦点恢复到正确位置
- [ ] 返回键优先级稳定，不会穿透
- [ ] `focusOwner` 可通过 debug 日志验证

---

### Phase 5：面板与 HUD 收口期

**目标**：时钟、字幕 inset、next-up、related、settings、interaction 全部接入统一显隐策略。

#### 3.5.1 时钟规则

| hudState | 时钟可见 |
|---|---|
| `Ambient` | 隐藏 |
| `Chrome` | 显示 |
| `Seek` | 隐藏（减少注意力竞争） |
| `Panel` | 由产品决定，规则显式声明 |
| `Completion` | 隐藏 |

#### 3.5.2 字幕 bottom inset 规则

| bottomOccupant | 字幕 bottom margin |
|---|---|
| `None` | 最小 inset（现有 `px60`） |
| `SlimTimeline` | 抬高一档，避免压线 |
| `FullChrome` | 完整控制层之上（现有 `px300`） |
| `BottomPanel` | panel 顶部之上，高度动态计算 |

替代现有 `renderControllerChrome()` 中的 `if (visibility == VISIBLE) px300 else px60` 硬编码。

#### 3.5.3 面板互斥

- `NextUp` 由 coordinator 决定是否与 `SlimTimeline` 共存
- `VideoPlayerAutoPlayController` 不再独立控制 `viewNext` 可见性，改为通过 coordinator 发 `UiEvent.PlaybackEnded` / `UiEvent.NextUpAction`
- `ResumeHint` 纳入 `panelState`，`VideoPlayerResumeHintController` 通过 coordinator 管理

#### 3.5.4 删除旧联动

| 删除目标 | 替代 |
|---|---|
| `renderControllerChrome()` 中字幕 margin 和时钟可见性逻辑 | coordinator 根据 `bottomOccupant` + `hudState` 下发 |
| `PlayerOverlayCoordinator`（旧） | `PlaybackUiCoordinator` 完全替代 |
| `VideoPlayerAutoPlayController` 独立动画 | 通过 coordinator 控制，保留动画实现 |

#### 3.5.5 验收标准

- [ ] 字幕永远不会被底部占位层压住
- [ ] 时钟显示规则稳定，不随内部 view visibility 抖动
- [ ] 设置、相关推荐、选集、更多、UP、下一集提示、互动视频之间不会互相穿透
- [ ] `showBottomProgressBar` 只影响隐藏态时间线是否可见

---

## 四、涉及文件清单

### 需要新建的文件

| 文件 | 职责 |
|---|---|
| `PlaybackUiCoordinator.kt` | 顶层状态协调器 |
| `UiEvent.kt` | 所有 UI 事件定义 |
| `TimelineRenderer.kt` | 时间线渲染接口 |
| `FullTimelineRenderer.kt` | FullTimeline 实现，绑定 DefaultTimeBar |
| `SlimTimelineRenderer.kt` | SlimTimeline 实现，绑定 bottomProgressBar |
| `SeekSession.kt` | 统一 seek 会话 |

### 需要改造的文件

| 文件 | 改造内容 |
|---|---|
| `PlayerOverlayCoordinator.kt` | 扩展为 `PlaybackUiCoordinator` 或被替代 |
| `MyPlayerView.kt` | 移除 `ProgressiveSeekHelper`，移除 `hiddenSeekPreview*`，焦点改为 coordinator 驱动 |
| `MyPlayerControlViewLayoutManager.kt` | UX state 变迁同步到 coordinator |
| `YouTubeOverlay.kt` | 移除 `progress_bar` 独立显示逻辑，移除 `Callback.onAnimationStart` 中 `setUseController` 调用 |
| `VideoPlayerFragment.kt` | 移除 `renderControllerChrome` 中字幕/时钟联动，改由 coordinator 下发；移除 `bottomProgressSeekPreview*` |
| `VideoPlayerOverlayController.kt` | 焦点恢复改由 coordinator 管理 |
| `VideoPlayerAutoPlayController.kt` | 不再独立控制 viewNext，通过 coordinator 事件驱动 |
| **`PlayerActivity.kt`** | **与 VideoPlayerFragment 同步改造**：移除 `renderControllerChrome` 中字幕/时钟联动，移除 `bottomProgressSeekPreview*`，改由 coordinator 下发。此文件包含与 VideoPlayerFragment 几乎完全相同的 chrome 联动和 bottomProgressBar 逻辑，必须同步改造，否则会遗留旧逻辑孤岛 |
| **`PlayerControlFocusCoordinator.kt`** | **Phase 4 整合**：保留为 controller 内部焦点管理实现，废弃 `PlayerOverlayCoordinator.FocusTarget`（5 种），统一使用本文件的 16 种 `FocusTarget`。`rememberCurrentFocusTarget()` / `restoreRememberedFocus()` 改由 coordinator 路由调用 |
| **`PlayerControlTimelineCoordinator.kt`** | **Phase 2 依赖**：`FullTimelineRenderer` 委托本 coordinator 获取 position/duration 数据，不重新实现 timeline 数据计算逻辑 |
| **`VideoPlayerProgressCoordinator.kt`** | **Phase 2 依赖**：其 `onProgressPublished` 回调应同时驱动两个 `TimelineRenderer` 的 `show()`，由 renderer 自行判断是否 active |

### 需要修改的布局

| 文件 | 修改内容 |
|---|---|
| `yt_overlay.xml` | 移除或废弃 `progress_bar`（Phase 2） |
| `fragment_video_player.xml` | `bottom_progress_bar` 改由 `SlimTimelineRenderer` 控制 |
| `my_exo_styled_player_view.xml` | 无需改动 |

---

## 五、风险与注意事项

1. **`isFocusable = true` 对触摸的影响** — Phase 4 改动后需在真机上全面测试触摸手势（单击、双击、滑动 seek）是否正常。建议 Phase 4 开始前先创建独立分支验证（详见 3.4.2）
2. **右长按倍速行为** — 已决策保留为独立 `SpeedMode`（详见 3.3.2a）
3. **弹幕 sync 时机** — Phase 3 改为 commit 时一次性 sync，需验证弹幕不会出现明显跳跃
4. **Phase 1/2 依赖** — Phase 2 的 `TimelineRenderer` 依赖 Phase 1 的 `bottomOccupant` 状态，不可并行
5. **`PlayerActivity` 同步改造** — `PlayerActivity.kt` 包含与 `VideoPlayerFragment` 几乎完全相同的 `renderControllerChrome()`、`renderBottomProgressBar()`、`bottomProgressSeekPreview*` 逻辑。每个 Phase 的改造必须同步应用到 `PlayerActivity`，否则会出现行为不一致。建议优先考虑将公共逻辑抽取到共享 helper 或直接在 coordinator 中统一处理
6. **现有 coordinator 复用** — `PlayerControlTimelineCoordinator`（timeline 数据计算）和 `VideoPlayerProgressCoordinator`（进度轮询）已有成熟实现，Phase 2 的 `TimelineRenderer` 必须委托它们，不可重新实现
7. **`PlayerControlFocusCoordinator` 整合边界** — 该文件管理 controller 内部 16 种 FocusTarget，是已验证的稳定实现。Phase 4 不应废弃或重写该文件，而是将其作为 coordinator 焦点路由的底层委托
8. **`YouTubeOverlay.progressBar` 迁移完整性** — 该 progressBar 有 5 个调用点（`updateProgress`、`ensureOverlayVisible`、`hideOverlayImmediately`、`setPersistentBottomProgressEnabled`、`updateProgressBarVisibility`），Phase 2 必须逐个迁移到 renderer，不可遗漏
9. **回归测试** — 每个 Phase 完成后需回归以下场景：
   - 普通播放 / 暂停 / 恢复
   - 左右短按 seek / 长按 seek / 双击 seek / 滑动 seek
   - 右长按倍速播放（升档 / 降档 / 退出）
   - seek 中途切方向
   - 设置面板打开/关闭
   - 相关推荐面板打开/关闭
   - 选集 dialog 打开/关闭
   - 更多操作 dialog 打开/关闭
   - UP 主详情 dialog 打开/关闭
   - 下一集提示出现/消失
   - 互动视频选择
   - 恢复进度提示
   - 字幕显示/隐藏
   - 时钟显示/隐藏
   - 底部常驻进度条开启/关闭
   - 屏幕旋转 / 尺寸变化
   - **PlayerActivity 和 VideoPlayerFragment 两套入口分别验证上述全部场景**

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
| `seekState` | `None / TapSeek / HoldSeek / SwipeSeek / DoubleTapSeek` | 统一所有 seek 会话 |
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
- `YouTubeOverlay.ensureOverlayVisible()` / `hideOverlayImmediately()` 内增加对 `seekState` 的同步写入
- `PlayerOverlayCoordinator.onRelatedPanelShown()` / `onRelatedPanelHidden()` 增加对 `panelState` 的同步写入
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

#### 3.2.3 改造 `bottomOccupant` 切换逻辑

在 `PlaybackUiCoordinator` 内：

- `bottomOccupant` 变化时，旧 renderer 调 `hide()`，新 renderer 调 `show()`
- `seekState` 变化时，当前 renderer 调 `showPreview()` 或恢复 `show()`
- 废弃 `YouTubeOverlay.progressBar`（`yt_overlay.xml:24`），其显示逻辑由 `bottomOccupant` 规则接管
- 废弃 `MyPlayerView.dispatchSeekPreviewState()` → `VideoPlayerFragment.onSeekPreviewStateChanged()` 这条跨层回调链，改为 `TimelineRenderer` 直接读 coordinator 状态

#### 3.2.4 删除跨层进度条联动

| 删除目标 | 文件 | 行为 |
|---|---|---|
| `bottomProgressSeekPreviewActive / Position / Duration` | `VideoPlayerFragment.kt:152-154` | 改由 `SlimTimelineRenderer` 管理 |
| `hiddenSeekPreviewActive / Position / Duration` | `MyPlayerView.kt:132-134` | 改由 coordinator + renderer 管理 |
| `renderBottomProgressBar()` 中所有 seek preview 判断 | `VideoPlayerFragment.kt:990-1018` | 简化为 `SlimTimelineRenderer.show()` |
| `YouTubeOverlay.updateProgressBarVisibility()` | `YouTubeOverlay.kt:513-519` | 废弃，由 coordinator 控制 |

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
| 右长按 | 倍速播放 `enterSpeedMode()` | 独立为 `SpeedMode` 或统一为 `HoldSeek`，需产品确认 |
| 双击 | `YouTubeOverlay.seekTo()` 即时 commit | `DoubleTapSeek`：即时 commit，overlay 纯视觉反馈 |
| 滑动 | `handleSwipeSeekTouch()` 松手时 commit | `SwipeSeek`：松手时一次性 commit |

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
- 测试 `onTouchEvent` 和 `performClick` 在 focusable 状态下不退化
- 确保 controller GONE 后焦点不会留在已 GONE 的子树

#### 3.4.3 返回键优先级链

```
Dialog → Panel → Interaction → ResumeHint → Controller → ExitPrompt
```

由 `PlaybackUiCoordinator.handleBackPress()` 统一实现，替代现有 `PlayerOverlayCoordinator.handleBackPress()` 的 callback 模式。

#### 3.4.4 废弃焦点直接操作

| 删除目标 | 替代 |
|---|---|
| `controller?.rememberCurrentFocusTarget()` | coordinator 维护 `focusRestoreTarget` |
| `controller?.restoreRememberedFocus()` | coordinator 统一恢复 |
| `playerView.requestXxxFocus()` 散落调用 | coordinator 通过 `focusRestoreTarget` 路由 |

#### 3.4.5 验收标准

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

### 需要修改的布局

| 文件 | 修改内容 |
|---|---|
| `yt_overlay.xml` | 移除或废弃 `progress_bar`（Phase 2） |
| `fragment_video_player.xml` | `bottom_progress_bar` 改由 `SlimTimelineRenderer` 控制 |
| `my_exo_styled_player_view.xml` | 无需改动 |

---

## 五、风险与注意事项

1. **`isFocusable = true` 对触摸的影响** — Phase 4 改动后需在真机上全面测试触摸手势（单击、双击、滑动 seek）是否正常
2. **右长按倍速行为** — Phase 3 需提前与产品确认是否保留，如保留则定义为独立 `SpeedMode` 而非挂载在 `HoldSeek` 上
3. **弹幕 sync 时机** — Phase 3 改为 commit 时一次性 sync，需验证弹幕不会出现明显跳跃
4. **Phase 1/2 依赖** — Phase 2 的 `TimelineRenderer` 依赖 Phase 1 的 `bottomOccupant` 状态，不可并行
5. **回归测试** — 每个 Phase 完成后需回归以下场景：
   - 普通播放 / 暂停 / 恢复
   - 左右短按 seek / 长按 seek / 双击 seek / 滑动 seek
   - seek 中途切方向
   - 设置面板打开/关闭
   - 相关推荐面板打开/关闭
   - 选集 dialog 打开/关闭
   - 下一集提示出现/消失
   - 互动视频选择
   - 恢复进度提示
   - 字幕显示/隐藏
   - 时钟显示/隐藏
   - 底部常驻进度条开启/关闭
   - 屏幕旋转 / 尺寸变化

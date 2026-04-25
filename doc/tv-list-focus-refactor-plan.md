# TV 列表焦点系统重构方案

## 背景

首页热门视频、推荐视频、图鉴/分区列表、视频详情推荐列表存在遥控器焦点向下移动时偶发卡住或列表突然回到上方某个滑动进度的问题。当前焦点与滚动恢复逻辑分散在 `BaseListFragment`、`BaseAdapter`、`VideoAdapter`、`VideoCardFocusHelper`、`RecyclerViewLoadMoreFocusController`、`TabContentFocusHelper` 和各 Fragment 中，导致焦点来源不唯一。

这次重构的核心目的不是单纯让焦点能继续往下走，而是把“焦点状态”和“滚动状态”收敛到同一个稳定 anchor 上，避免任何旧 position、旧 View 引用或旧 `LayoutManager` state 把列表拉回历史位置。

## 当前深入分析

### 现象

用户连续按 DOWN 浏览视频列表时，列表有时不是继续向下，而是突然回到上面某个曾经停留过的滚动进度。表现通常发生在以下场景：

1. 按 DOWN 触发滚动或加载更多。
2. item detach 或数据 diff 更新发生。
3. 焦点短暂丢失到 `RecyclerView` 容器，或仍引用已经 detach/recycle 的旧 item View。
4. 旧恢复逻辑再次执行，按旧 `LayoutManager` state、旧 `focusedView` 或旧 `rememberedPosition` 恢复。
5. `RecyclerView` 被滚回旧 first visible 位置，视觉上就是“下滑时突然回到上面某个进度”。

### 主要根因

#### 1. `BaseListFragment` 保存并恢复 `LayoutManager` state

`captureListStateForReturnRestore()` 会保存：

```kotlin
pendingLayoutState = lm.onSaveInstanceState()
pendingReturnRestoreAttempts = 2
```

`restorePendingReturnState()` 又会执行：

```kotlin
rv.layoutManager?.onRestoreInstanceState(state)
```

这对返回详情页后的恢复有用，但对正在下滑、加载更多、刷新 diff 的列表是高风险的。只要 `pendingReturnRestoreAttempts` 未清零，数据变化后 observer 会再次调度恢复，旧 `LayoutManager` state 可能覆盖当前真实滚动位置。

结论：第一阶段必须停止在 TV 视频流里恢复 `LayoutManager` Parcelable。返回恢复只能通过 stable key anchor 定位 item，不能恢复整个 layout state。

#### 2. `BaseAdapter` 保存旧 View 引用和旧 position

`BaseAdapter` 当前保存：

```kotlin
internal var focusedView: View? = null
internal var rememberedPosition: Int = RecyclerView.NO_POSITION
```

`VideoAdapter` 在焦点变化和点击时调用 `rememberItemInteraction(view, position)`。但 `RecyclerView` 的 item View 会 recycle/detach，旧 View 引用不可靠；旧 position 在刷新、去重、插入、删除后也可能指向另一条视频。

结论：后续只能保存 stable key、position 快照、row、column、offset。View 引用只能作为当前帧临时对象，不能成为跨数据更新的状态。

#### 3. `RecyclerViewLoadMoreFocusController` 会把焦点停到 RecyclerView 容器

它会执行 `parkFocusInRecyclerView()`、`recyclerView.requestFocus()`，并临时修改 `descendantFocusability`。这能掩盖 detach 时的焦点丢失，但副作用是容器焦点会触发另一套 fallback：

1. `RecyclerView` 自己获得焦点。
2. DOWN/UP 再从 first visible 或 last known position 恢复。
3. 数据回来后 `consumePendingFocusAfterLoadMore()` 又尝试 smooth scroll 和 retry focus。

这些逻辑与 `BaseListFragment` 的返回恢复、`VideoCardFocusHelper` 的 FocusFinder 导航会互相抢焦点，容易把列表带回旧位置。

结论：新系统不允许把 `RecyclerView` 作为最终焦点。加载更多期间当前业务 item 继续持有焦点；数据回来后由 anchor 计算下一行目标。

#### 4. `VideoCardFocusHelper` 的 DOWN 依赖 FocusFinder

当前 DOWN 流程：

```kotlin
FocusFinder.getInstance().findNextFocus(rv, target, View.FOCUS_DOWN)
```

FocusFinder 是按屏幕几何找 view，不理解网格的业务行列，也不理解 load more 不是业务 item。滚动后可见项变化时，FocusFinder 可能选中非预期目标，再被旧恢复逻辑修正，形成跳动。

结论：列表内部 UP/DOWN 必须由 position、row、column 计算。`VideoCardFocusHelper` 只保留页面级跳转，如顶部回 tab、左边界回侧栏。

#### 5. `VideoFeedFragment` 的刷新滚顶和保留 offset 也会参与回跳

`refresh()` 会设置：

```kotlin
pendingScrollToTopAfterRefresh = true
```

`applyReplacedVideosNow()` 会在特定条件下 `scrollToTop()`，而非刷新替换时又通过 `preserveScrollOffset` 按 first visible position 保留 offset。这个逻辑和 TV 焦点恢复没有共享同一份状态，容易出现“视觉滚动位置”和“焦点 anchor”不一致。

结论：TV 焦点重构后，列表替换应按操作来源分流：

1. 用户明确刷新或重选 tab：允许滚顶并清空 anchor。
2. 返回恢复、后台数据更新、分页 append：按 stable key anchor 恢复，不恢复 layout state。
3. 手势滑动后按遥控器：以最近可见业务 item 或最近聚焦 item 重新建立 anchor。

## 重构目标

1. 焦点永远属于业务 item，不属于 `RecyclerView` 容器。
2. 方向键移动按 position、row、column 计算，不依赖 FocusFinder。
3. 数据变化后按 stable key 恢复焦点，不保存旧 View 引用。
4. 滚动和 `requestFocus` 作为一个原子操作执行。
5. 加载更多期间当前 item 不丢焦点，数据回来后移动到预期下一行。
6. 嵌套 lane 上下移动时保持横向 child index。
7. 禁止 TV 视频流恢复旧 `LayoutManager` Parcelable，避免旧滚动进度覆盖当前进度。
8. 明确区分用户主动滚顶、返回恢复、分页 append、后台刷新四类数据更新来源。

## 需要废弃或弱化的旧逻辑

### 立即废弃或禁用

#### `RecyclerViewLoadMoreFocusController`

第一阶段在热门/推荐视频流中关闭。它的 `parkFocusInRecyclerView()`、`recyclerView.requestFocus()`、`descendantFocusability` 修改是焦点停到容器和回跳的高风险点。

#### `BaseListFragment.pendingLayoutState`

TV 视频流第一阶段不再使用 `lm.onSaveInstanceState()` 和 `onRestoreInstanceState()` 进行返回恢复。可以保留字段给非 TV 页面过渡，但新控制器接管的页面必须跳过这条路径。

#### `BaseAdapter.focusedView`

不再作为恢复依据。保留字段只用于兼容未迁移页面。迁移后的页面不读写它。

#### `VideoCardFocusHelper` 的列表内部 DOWN

保留左边界回侧栏、顶部回 tab、底部页面级跳转；禁用普通列表内部 DOWN 导航。列表内部方向键统一交给新的 `TvListFocusController`。

### 过渡期保留

1. `TabContentFocusHelper` 可作为非 TV 页面和未迁移页面 fallback。
2. `RecyclerViewFocusRestoreHelper` 可保留，但新 TV 控制器不直接使用其 `scrollToPosition()` 简化恢复。
3. 各 Adapter 的私有 `focusedView` 可分阶段迁移，不阻塞第一阶段热门视频修复。

## 新增模块

目录：

```text
app/src/main/java/com/tutu/myblbl/core/ui/focus/tv/
```

当前已存在：

```text
TvFocusableAdapter.kt
```

建议新增：

```text
TvFocusAnchor.kt
TvFocusStrategy.kt
GridTvFocusStrategy.kt
LinearTvFocusStrategy.kt
RecyclerViewFocusOperator.kt
TvListFocusController.kt
TvNestedLaneFocusController.kt
```

## 核心接口和类

### `TvFocusableAdapter`

当前文件已创建。建议保持接口简洁：

```kotlin
interface TvFocusableAdapter {
    fun focusableItemCount(): Int
    fun stableKeyAt(position: Int): String?
    fun findPositionByStableKey(key: String): Int
    fun isFocusablePosition(position: Int): Boolean = position in 0 until focusableItemCount()
}
```

`BaseAdapter` 建议实现默认逻辑：

```kotlin
override fun focusableItemCount(): Int = contentCount()
override fun stableKeyAt(position: Int): String? = getFocusStableKey(position)
override fun findPositionByStableKey(key: String): Int = items.indexOfFirst { getFocusStableKey(it) == key }
protected open fun getFocusStableKey(item: MODEL): String? = null
```

注意：load more cell 不是业务 item，不能 focus，不能参与 `focusableItemCount()`。

### `TvFocusAnchor`

```kotlin
data class TvFocusAnchor(
    val stableKey: String?,
    val adapterPosition: Int,
    val row: Int,
    val column: Int,
    val offsetTop: Int = 0,
    val source: Source = Source.FOCUS
) {
    enum class Source {
        FOCUS,
        VISIBLE_ITEM,
        RETURN_RESTORE,
        PENDING_LOAD_MORE
    }
}
```

`offsetTop` 用于保持视觉位置，但它从当前业务 item 计算，不能从 `LayoutManager` Parcelable 恢复。

### `TvFocusStrategy`

```kotlin
interface TvFocusStrategy {
    fun anchorFor(position: Int, stableKey: String?, offsetTop: Int): TvFocusAnchor
    fun nextPosition(anchor: TvFocusAnchor, direction: Int, itemCount: Int): Int?
}
```

### `GridTvFocusStrategy`

用于热门视频、推荐视频、搜索结果、收藏、历史、用户空间视频网格。

规则：

1. UP = position - spanCount。
2. DOWN = position + spanCount。
3. LEFT/RIGHT 只在同一行内移动。
4. 最后一行不足 spanCount 时，DOWN 到不存在目标则停留当前 item，除非可以加载更多。
5. 计算 itemCount 时必须使用业务 item 数，不能包含 load more cell。

### `LinearTvFocusStrategy`

用于普通纵向列表。

规则：

1. UP = position - 1。
2. DOWN = position + 1。
3. LEFT/RIGHT 默认交给页面级 transition。

### `RecyclerViewFocusOperator`

职责：把滚动和聚焦合并为原子操作，避免滚动已经发生但焦点仍被旧逻辑恢复。

建议 API：

```kotlin
class RecyclerViewFocusOperator(
    private val recyclerView: RecyclerView,
    private val adapter: TvFocusableAdapter
) {
    fun focusPosition(
        position: Int,
        offsetTop: Int = 0,
        reason: String,
        onFocused: ((Int) -> Unit)? = null
    ): Boolean
}
```

执行规则：

1. position 必须在 `0 until adapter.focusableItemCount()`。
2. 如果目标 holder 已 attach 且可见，直接 `requestFocus()`。
3. 如果未 attach，优先 `LinearLayoutManager.scrollToPositionWithOffset(position, offsetTop)`。
4. 下一帧再次查找 holder 并 `requestFocus()`。
5. 如果仍失败，只能聚焦最近可见业务 item，不能聚焦 `RecyclerView`。
6. 每次 focus request 带 token；新请求发起后，旧 post/retry 自动失效。
7. 操作过程中不要调用 `smoothScrollToPosition()` 作为第一阶段默认实现，先保证确定性。

### `TvListFocusController`

职责：

1. 记录当前 anchor。
2. 处理 DPAD。
3. 计算目标 position。
4. 执行滚动加聚焦。
5. 处理加载更多。
6. 数据变化后按 stable key 恢复。
7. 屏蔽旧 `LayoutManager` state 恢复。

建议状态：

```kotlin
private var currentAnchor: TvFocusAnchor? = null
private var pendingMoveAfterLoadMore: TvFocusAnchor? = null
private var pendingRestoreAfterDataChange: TvFocusAnchor? = null
private var focusToken = 0
```

关键行为：

1. `onItemFocused(view, position)`：从当前 View 计算 offset，保存 stable key anchor。
2. `handleKey(view, keyCode, event)`：只处理 ACTION_DOWN 的 DPAD。
3. `move(direction)`：通过 strategy 计算目标。
4. 目标存在：调用 operator.focusPosition。
5. DOWN 到底且 `canLoadMore()`：保存 `pendingMoveAfterLoadMore`，触发 `loadMore()`，返回 true，当前 item 保持焦点。
6. `onDataChanged(reason)`：优先处理 pending load more，其次按 stable key 恢复当前 anchor，再次才按 position 兜底。
7. `focusPrimary()`：优先 current anchor，失败则 first visible business item，最后 position 0。
8. `clearAnchorForUserRefresh()`：用户主动刷新、tab 重选、返回顶部时调用。

### `TvNestedLaneFocusController`

用于 `HomeLaneFragment` 和 `HomeLaneAdapter`。

Anchor：

```kotlin
data class TvLaneFocusAnchor(
    val laneStableKey: String?,
    val lanePosition: Int,
    val laneOffsetTop: Int,
    val childStableKey: String?,
    val childPosition: Int,
    val childOffsetLeft: Int
)
```

ViewHolder 接口：

```kotlin
interface FocusableLaneHolder {
    val childRecyclerView: RecyclerView
    fun childCount(): Int
    fun childStableKey(position: Int): String?
    fun findChildPositionByStableKey(key: String): Int
    fun focusChild(position: Int): Boolean
}
```

UP/DOWN 规则：

1. 当前 lane = L，当前 child = C。
2. DOWN 到 L + 1，优先保持 child C。
3. 目标 lane child 数不足时落到最后一个 child。
4. UP 同理。
5. 如果当前焦点在 lane header 或 timeline filter，上下移动先进入目标 lane header，再进入 child。

执行流程：

1. 外层 scroll 到目标 lane。
2. 下一帧找到 holder。
3. 内层 scroll 到目标 child。
4. 再下一帧 focus child。
5. 全程不保存 child View 引用。

## 第一阶段：热门/推荐视频 Grid

第一阶段优先修复用户反馈的“下滑视频列表时突然回到上方某个滑动进度”。作用范围限定在 `VideoFeedFragment`、`HotListFragment`、`RecommendListFragment`、`VideoAdapter`、`BaseAdapter`、`BaseListFragment` 和 `core/ui/focus/tv/*`。

### 1. `BaseAdapter` 改造

1. 实现 `TvFocusableAdapter`。
2. 新增 `protected open fun getFocusStableKey(item: MODEL): String? = null`。
3. `focusableItemCount()` 返回 `contentCount()`。
4. `stableKeyAt(position)` 只允许业务 item。
5. `findPositionByStableKey(key)` 从 `items` 查找。
6. 保留 `focusedView` 和 `rememberedPosition` 供未迁移页面使用，但 TV 新控制器不读它们。

### 2. `VideoAdapter` 改造

1. 让 `videoKey(video)` 可被 stable key 复用。
2. 覆盖 `getFocusStableKey(item: VideoModel)`。
3. 构造参数增加：

```kotlin
private val onItemFocused: ((View, Int) -> Unit)? = null
private val onItemDpad: ((View, Int, KeyEvent) -> Boolean)? = null
private val useLegacyFocusHelper: Boolean = true
```

4. ViewHolder 获得焦点时调用 `onItemFocused(view, position)`。
5. DPAD 先交给 `onItemDpad`。
6. `useLegacyFocusHelper = false` 时，不让 `VideoCardFocusHelper` 处理列表内部 DOWN。
7. `removeDislikedItem()` 和 `removeDislikedUpItems()` 不能再 `viewToFocus?.requestFocus()`，应通知 controller `onDataChanged(reason = REMOVE_ITEM)`。

### 3. `BaseListFragment` 改造

新增字段：

```kotlin
protected var tvFocusController: TvListFocusController? = null
protected open val enableTvListFocusController: Boolean = false
```

新增策略方法：

```kotlin
protected open fun createTvFocusStrategy(): TvFocusStrategy =
    GridTvFocusStrategy { getSpanCount() }
```

初始化规则：

1. adapter 和 layoutManager 创建完成后，如果 `enableTvListFocusController == true` 且 adapter 是 `TvFocusableAdapter`，创建 `TvListFocusController`。
2. `AdapterDataObserver` 先调用 `tvFocusController?.onDataChanged(reason)`。
3. 当新控制器启用时，不调度 `schedulePendingReturnRestore()`，或至少跳过 `pendingLayoutState` 恢复。
4. `focusPrimaryContent()` 优先 `tvFocusController?.focusPrimary()`。
5. `onPause()` 调用 `tvFocusController?.captureCurrentAnchor()`，不保存 `LayoutManager` Parcelable。
6. `onResume()` 调用 `tvFocusController?.restoreCapturedAnchor()`，不走旧 position 恢复。
7. `onDestroyView()` release controller。

### 4. `VideoFeedFragment` 改造

1. 开启新控制器：

```kotlin
override val enableTvListFocusController: Boolean = true
override val enableLoadMoreFocusController: Boolean = false
```

2. 创建 `VideoAdapter` 时传入：

```kotlin
useLegacyFocusHelper = false,
onItemFocused = { view, position ->
    tvFocusController?.onItemFocused(view, position)
},
onItemDpad = { view, keyCode, event ->
    tvFocusController?.handleKey(view, keyCode, event) == true
}
```

3. `loadData(page)` 不再依赖 `RecyclerViewLoadMoreFocusController`。
4. `applyAppendedVideos()` 后调用：

```kotlin
tvFocusController?.onDataChanged(TvDataChangeReason.APPEND)
```

5. `applyReplacedVideosNow()` 根据来源调用：

```kotlin
tvFocusController?.onDataChanged(TvDataChangeReason.REPLACE_PRESERVE_ANCHOR)
```

6. 用户明确刷新、tab 重选、返回顶部时调用：

```kotlin
tvFocusController?.clearAnchorForUserRefresh()
```

7. 移除 `loadMoreFocusController?.consumePendingFocusAfterLoadMore()` 和 `clearPendingFocusAfterLoadMore()`。

### 5. `HotListFragment` 首个接入

先只让热门视频启用新系统，推荐视频保持旧逻辑观察一轮。如果热门验证通过，再把 `RecommendListFragment` 接入。

可选做法：

```kotlin
override val enableTvListFocusController: Boolean = true
override val enableLoadMoreFocusController: Boolean = false
```

如果 `enableTvListFocusController` 放在 `VideoFeedFragment`，则热门和推荐会一起接入。为了降低风险，建议先在 `VideoFeedFragment` 提供开关，`HotListFragment` 覆盖打开。

## 数据变化原因枚举

建议新增：

```kotlin
enum class TvDataChangeReason {
    INITIAL_LOAD,
    USER_REFRESH,
    REPLACE_PRESERVE_ANCHOR,
    APPEND,
    REMOVE_ITEM,
    RETURN_RESTORE
}
```

处理规则：

1. `INITIAL_LOAD`：无 anchor 时 focus position 0；有 anchor 时按 stable key 恢复。
2. `USER_REFRESH`：清空 anchor，允许滚顶。
3. `REPLACE_PRESERVE_ANCHOR`：按 stable key 恢复，失败才按旧 position 兜底。
4. `APPEND`：如果有 pending load more，移动到下一行；否则保持当前焦点。
5. `REMOVE_ITEM`：被删 item 有 stable key 则找相邻合法 position。
6. `RETURN_RESTORE`：按 stable key 恢复，不恢复 layout state。

## 回跳问题的验收修复点

第一阶段必须满足：

1. DOWN 导航不调用 FocusFinder。
2. DOWN 触发加载更多时不调用 `RecyclerView.requestFocus()`。
3. 数据 append 后不调用旧 `consumePendingFocusAfterLoadMore()`。
4. TV 新控制器启用时不执行 `LayoutManager.onRestoreInstanceState()`。
5. `setAdapterData(... preserveScrollOffset = true)` 与 TV anchor 不冲突；新控制器启用时优先 anchor 恢复。
6. 刷新滚顶只发生在用户明确刷新或重选 tab 时，普通 append/后台 replace 不滚顶。

## 首页图鉴/分区二阶段重构

`HomeLaneFragment` 和 `HomeLaneAdapter` 是嵌套 RecyclerView，目前也保存 `focusedView`，并在外层 detach 时恢复到可见 lane。二阶段接入 `TvNestedLaneFocusController`。

改造点：

1. `HomeLaneAdapter` 不再保存 child View 引用。
2. `HomeLaneSection` 提供 lane stable key：timeline、follow、title/style/moreSeasonType。
3. `LaneItemAdapter` 暴露 child stable key，复用当前 `diffKey()`。
4. 外层 lane DOWN 由 `TvNestedLaneFocusController` 计算，不由 `focusNextSectionFrom()` 猜。
5. timeline 插入后按 lane stable key 和 child stable key 恢复。

## 详情页三阶段重构

推荐视频列表接入 `TvListFocusController`。头部按钮区域和列表之间使用明确 page-level transition：

1. 从按钮区 DOWN：进入推荐列表 `focusPrimary()`。
2. 推荐列表第一行 UP：返回最近按钮。
3. 推荐列表内部 UP/DOWN：策略计算。
4. 不使用 FocusFinder 猜列表内部目标。

## 迁移顺序

1. 热门视频普通 Grid：`HotListFragment`、`VideoFeedFragment`、`VideoAdapter`、`BaseAdapter`、`BaseListFragment`、`core/ui/focus/tv/*`。
2. 推荐视频普通 Grid：`RecommendListFragment`。
3. 其他普通 Grid：搜索、收藏、历史、用户空间。
4. 首页图鉴/分区 Lane：`HomeLaneFragment`、`HomeLaneAdapter`、`LaneItemAdapter`、timeline adapter。
5. 详情页推荐列表和按钮区域。
6. 删除旧逻辑：`RecyclerViewLoadMoreFocusController`、`VideoCardFocusHelper` 中 FocusFinder/DOWN 导航、`BaseAdapter.focusedView/rememberedPosition`、`BaseListFragment.pendingLayoutState` 恢复。

## 验收标准

### 热门视频

1. 连续按 DOWN，焦点始终按同一列向下移动。
2. 目标 item 不可见时，滚动后焦点落到目标 item。
3. 到底触发加载更多时，当前 item 不丢焦点。
4. 加载更多完成后，焦点移动到下一行对应列。
5. 没有更多数据时，焦点停留在当前最后一行，不跳到 `RecyclerView`。
6. 快速连续按 DOWN 不应卡死。
7. 手势滑动和遥控器混用后，焦点仍能恢复到最近业务 item。
8. 下滑过程中 append、diff、item detach 后，不回到上方旧滚动进度。
9. 从详情返回后，按 stable key 回到原视频卡片；原视频不存在时落到最近合法 position。

### 推荐视频

1. 与热门视频同一套验收。
2. 首页内容 ready 事件不触发额外滚顶。
3. 用户登录状态变化刷新时，只有明确刷新才清空 anchor。

### 图鉴/分区

1. 横向 lane 内 LEFT/RIGHT 正常。
2. 从第 N 个 child 按 DOWN，落到下一 lane 的第 N 个 child。
3. 下一 lane item 数不足时，落到最后一个 child。
4. 外层滚动过程中数据刷新，不丢焦点。
5. timeline 插入后，焦点按 stable key 恢复。
6. 不出现焦点停在 `RecyclerView` 容器上的情况。

### 返回恢复

1. 从列表进入详情。
2. 返回后焦点回到原视频卡片。
3. 如果原视频被刷新移位，按 stable key 找回。
4. 如果原视频不存在，落到最近合法位置。
5. 返回恢复不调用 `LayoutManager.onRestoreInstanceState()`。

## 建议调试日志

第一阶段建议在 `TvListFocusController` 中临时加结构化日志：

```text
TvFocus onItemFocused pos= key= row= col= offset=
TvFocus key DOWN from= target= reason=
TvFocus loadMore pendingAnchor= targetAfterAppend=
TvFocus dataChanged reason= itemCount= anchorKey= resolvedPos=
TvFocus focusPosition token= pos= attached= offset= reason=
TvFocus fallbackNearestVisible from= target=
```

这些日志能直接判断回跳来自哪里：如果出现旧 `restorePendingReturnState`、`RecyclerView.requestFocus()` 或非用户刷新 `scrollToTop()`，说明仍有旧路径未切断。

## 当前工作区注意事项

继续执行前建议先运行：

```bash
git status --short
```

当前仓库已有与播放器相关的未提交变更，焦点重构时不要回退这些文件。`TvFocusableAdapter.kt` 已存在，后续应在同一目录补齐控制器和策略类。

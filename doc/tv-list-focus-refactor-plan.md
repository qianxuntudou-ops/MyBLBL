# TV 列表焦点系统重构方案

## 背景

首页热门视频、图鉴/分区列表、视频详情推荐列表存在遥控器焦点向下移动偶发卡住的问题。当前焦点逻辑分散在 BaseListFragment、BaseAdapter、VideoAdapter、VideoCardFocusHelper、RecyclerViewLoadMoreFocusController 和各 Fragment 中，导致焦点来源不唯一。数据刷新、加载更多、item detach、页面返回时，焦点可能停到 RecyclerView 容器或恢复到错误 item。

## 重构目标

1. 焦点永远属于业务 item，不属于 RecyclerView 容器。
2. 方向键移动按 position、row、column 计算，不依赖 FocusFinder。
3. 数据变化后按 stable key 恢复焦点，不保存旧 View 引用。
4. 滚动和 requestFocus 作为一个原子操作执行。
5. 加载更多期间当前 item 不丢焦点，数据回来后移动到预期下一行。
6. 嵌套 lane 上下移动时保持横向 child index。

## 需要废弃或弱化的旧逻辑

### RecyclerViewLoadMoreFocusController

逐步废弃。它会 parkFocusInRecyclerView、recyclerView.requestFocus、修改 descendantFocusability，并让加载更多期间焦点停到 RecyclerView。这个设计是偶发卡住的主要风险点。

### VideoCardFocusHelper 的列表内部导航

保留左边界回侧栏、顶部回 tab 等页面级跳转；删除或禁用列表内部 UP/DOWN 主导航。列表内部方向键统一交给新的 TvListFocusController。

### BaseAdapter.focusedView

不要继续保存 View 引用。RecyclerView item 会复用和 detach，View 引用不能作为可靠焦点状态。后续只保存 stableKey、adapterPosition、row、column、offset。

## 新增模块

新增目录：

```text
app/src/main/java/com/tutu/myblbl/core/ui/focus/tv/
```

建议新增文件：

```text
TvFocusableAdapter.kt
TvFocusAnchor.kt
TvFocusStrategy.kt
GridTvFocusStrategy.kt
LinearTvFocusStrategy.kt
RecyclerViewFocusOperator.kt
TvListFocusController.kt
TvNestedLaneFocusController.kt
```

## 核心接口和类

### TvFocusableAdapter

```kotlin
interface TvFocusableAdapter {
    fun focusableItemCount(): Int
    fun stableKeyAt(position: Int): String?
    fun findPositionByStableKey(key: String): Int
    fun isFocusablePosition(position: Int): Boolean = position in 0 until focusableItemCount()
}
```

BaseAdapter 建议实现该接口，并新增 getFocusStableKey。loadMore 位置不是业务 item，不能 focus。

### TvFocusAnchor

```kotlin
data class TvFocusAnchor(
    val stableKey: String?,
    val adapterPosition: Int,
    val row: Int,
    val column: Int,
    val offsetTop: Int = 0
)
```

### TvFocusStrategy

```kotlin
interface TvFocusStrategy {
    fun anchorFor(position: Int, stableKey: String?, offsetTop: Int): TvFocusAnchor
    fun nextPosition(anchor: TvFocusAnchor, direction: Int, itemCount: Int): Int?
}
```

### GridTvFocusStrategy

用于热门视频、推荐视频、搜索结果、收藏、历史等网格列表。规则：UP = position - spanCount，DOWN = position + spanCount，LEFT 和 RIGHT 只在同一行内移动。

### LinearTvFocusStrategy

用于普通纵向列表。规则：UP = position - 1，DOWN = position + 1。

### RecyclerViewFocusOperator

负责把滚动和聚焦合并成原子操作：

1. 如果目标 holder 已 attach，直接 requestFocus。
2. 如果没有 attach，scrollToPositionWithOffset。
3. post 到下一帧后再次找 holder 并 requestFocus。
4. 如果仍失败，focusNearestVisible。
5. 不允许把 RecyclerView 本身作为最终焦点。

### TvListFocusController

职责：记录 anchor、处理方向键、计算目标 position、执行滚动加聚焦、处理加载更多、数据变化后按 stable key 恢复。

关键行为：

- handleKey 将 DPAD 转成 View.FOCUS_*。
- move 通过 strategy.nextPosition 计算目标。
- target 存在时调用 operator.focusPosition。
- DOWN 到底且 canLoadMore 时保存 pendingAfterLoadMore 并触发 loadMore。
- onDataChanged 时如果有 pendingAfterLoadMore，则移动到下一行目标；否则按 stable key 恢复当前 anchor。
- 不调用 recyclerView.requestFocus 作为最终焦点。

## BaseListFragment 改造

逐步废弃：

```kotlin
enableLoadMoreFocusController
loadMoreFocusController
installLoadMoreFocusController()
pendingReturnRestoreAttempts
pendingLayoutState
restorePosted
adapter.focusedView
adapter.getRememberedPosition()
```

新增字段：

```kotlin
protected var tvFocusController: TvListFocusController? = null
```

新增策略方法：

```kotlin
protected open fun createTvFocusStrategy(): TvFocusStrategy = GridTvFocusStrategy { getSpanCount() }
```

initView 中 adapter 和 layoutManager 创建后，若 adapter 实现 TvFocusableAdapter，则创建 TvListFocusController。AdapterDataObserver 过渡期同时调用 tvFocusController?.onDataChanged() 和旧的 schedulePendingReturnRestore()。onPause 保存 anchor，onResume 恢复 anchor。focusPrimaryContent 优先使用 tvFocusController.focusPrimary()。

## VideoAdapter 改造

1. 提供 stable key：getFocusStableKey(item: VideoModel) = videoKey(item)。
2. 构造参数增加 onItemFocused、onItemDpad、useLegacyFocusHelper。
3. ViewHolder 获得焦点时上报 onItemFocused(view, position)。
4. DPAD 按键先交给 onItemDpad。
5. 热门视频先传 useLegacyFocusHelper = false，不再让 VideoCardFocusHelper 处理 DOWN。

## HotListFragment 改造

关闭旧加载更多焦点控制：

```kotlin
override val enableLoadMoreFocusController: Boolean = false
```

创建 VideoAdapter 时传入：

```kotlin
useLegacyFocusHelper = false
onItemFocused = { view, position -> tvFocusController?.onItemFocused(view, position) }
onItemDpad = { view, keyCode, event -> tvFocusController?.handleKey(view, keyCode, event) == true }
```

替换 loadMoreFocusController?.consumePendingFocusAfterLoadMore() 为 tvFocusController?.onDataChanged()。setData 和 addData 完成后也要触发 tvFocusController?.onDataChanged()。

## 首页图鉴/分区二阶段重构

HomeLaneFragment 和 HomeLaneAdapter 是嵌套 RecyclerView，需要 TvNestedLaneFocusController。

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

UP/DOWN 规则：当前 lane = L，当前 child = C。DOWN 到 L + 1，并优先保持 child C；如果目标 lane child 数不足，则落到最后一个 child。UP 同理。

执行流程：外层 scroll 到目标 lane，post 后找到 holder，内层 scroll 到目标 child，再 post 后 focus child。

## 详情页三阶段重构

推荐视频列表接入 TvListFocusController。头部按钮区域和列表之间使用明确 page-level transition，不使用 FocusFinder 猜。进入推荐列表时调用 tvFocusController.focusPrimary()。

## 迁移顺序

1. 热门视频普通 Grid：BaseAdapter、BaseListFragment、VideoAdapter、HotListFragment、core/ui/focus/tv/*。
2. 其他普通 Grid：推荐、搜索、收藏、历史、用户空间。
3. 首页图鉴/分区 Lane：HomeLaneFragment、HomeLaneAdapter、相关横向 Adapter。
4. 详情页推荐列表和按钮区域。
5. 删除旧逻辑：RecyclerViewLoadMoreFocusController、VideoCardFocusHelper 中 FocusFinder/DOWN 导航、BaseAdapter focusedView/rememberedPosition、BaseListFragment pendingLayoutState restore。

## 验收标准

### 热门视频

1. 连续按 DOWN，焦点始终按同一列向下移动。
2. 目标 item 不可见时，滚动后焦点落到目标 item。
3. 到底触发加载更多时，当前 item 不丢焦点。
4. 加载更多完成后，焦点移动到下一行对应列。
5. 没有更多数据时，焦点停留在当前最后一行，不跳到 RecyclerView。
6. 快速连续按 DOWN 不应卡死。
7. 手势滑动和遥控器混用后，焦点仍能恢复到最近业务 item。

### 图鉴/分区

1. 横向 lane 内 LEFT/RIGHT 正常。
2. 从第 N 个 child 按 DOWN，落到下一 lane 的第 N 个 child。
3. 下一 lane item 数不足时，落到最后一个 child。
4. 外层滚动过程中数据刷新，不丢焦点。
5. 时间线插入后，焦点按 stable key 恢复。
6. 不出现焦点停在 RecyclerView 容器上的情况。

### 返回恢复

1. 从列表进入详情。
2. 返回后焦点回到原视频卡片。
3. 如果原视频被刷新移位，按 stable key 找回。
4. 如果原视频不存在，落到最近合法位置。

## 当前已知工作区变更

本次操作曾尝试开始重构，确认成功创建过：

```text
app/src/main/java/com/tutu/myblbl/core/ui/focus/tv/TvFocusableAdapter.kt
```

继续执行前建议先运行：

```bash
git status --short
```

确认当前工作区变更，再按本文档分阶段推进。

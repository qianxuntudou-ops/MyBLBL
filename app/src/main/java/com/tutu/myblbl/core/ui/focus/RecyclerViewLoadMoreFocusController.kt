package com.tutu.myblbl.core.ui.focus

import android.os.Build
import android.os.SystemClock
import android.view.FocusFinder
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.tutu.myblbl.R
import com.tutu.myblbl.core.common.log.AppLog

class RecyclerViewLoadMoreFocusController(
    private val recyclerView: RecyclerView,
    private val callbacks: Callbacks,
    private val config: Config = Config()
) {
    companion object {
        private const val TAG = "FocusLoadMore"
        fun fromView(view: View): RecyclerViewLoadMoreFocusController? {
            var current: View? = view
            while (current != null) {
                if (current is RecyclerView) {
                    return current.getTag(R.id.tag_recycler_load_more_focus_controller) as? RecyclerViewLoadMoreFocusController
                }
                current = current.parent as? View
            }
            return null
        }
    }

    data class Config(
        val isEnabled: () -> Boolean = { true },
        val scrollOnDownEdgeFactor: Float = 0.8f
    )

    interface Callbacks {
        fun canLoadMore(): Boolean
        fun loadMore()
    }

    private var installed = false
    private var pendingFocusAfterLoadMoreAnchorPos = RecyclerView.NO_POSITION
    private var pendingFocusAfterLoadMoreTargetPos = RecyclerView.NO_POSITION
    private var focusParkedDescendantFocusability: Int? = null
    private val focusRetryDelayMillis = 16L
    private val focusRetryMaxAttempts = 30
    private val focusProtectWindowMs = 500L
    private val recyclerFocusRecoverWindowMs = 3_000L
    private var lastVerticalNavAtMs = 0L
    private var lastVerticalNavDirection = View.FOCUS_DOWN
    private var lastKnownFocusedAdapterPos = RecyclerView.NO_POSITION
    private var lastKnownFocusedSpanIndex: Int? = null
    private var detachFocusRestoreToken = 0
    private var originalOverScrollMode: Int? = null
    private var originalDefaultFocusHighlightEnabled: Boolean? = null
    private var didSuppressDefaultFocusHighlight = false
    private var focusHighlightListener: ViewTreeObserver.OnGlobalFocusChangeListener? = null

    private val adapterObserver = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() {
            consumePendingFocusAfterLoadMore()
        }

        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
            consumePendingFocusAfterLoadMore()
        }

        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
            consumePendingFocusAfterLoadMore()
        }
    }

    private val recyclerKeyListener = View.OnKeyListener { _, keyCode, event ->
        if (!installed || !config.isEnabled()) {
            return@OnKeyListener false
        }
        if (event.action != KeyEvent.ACTION_DOWN || !recyclerView.isFocused) {
            return@OnKeyListener false
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                markVerticalNav(View.FOCUS_DOWN)
                handleRecyclerFocusedDpadDown()
            }

            KeyEvent.KEYCODE_DPAD_UP -> {
                markVerticalNav(View.FOCUS_UP)
                if (pendingFocusAfterLoadMoreAnchorPos != RecyclerView.NO_POSITION) {
                    clearPendingFocusAfterLoadMore()
                }
                false
            }

            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (pendingFocusAfterLoadMoreAnchorPos != RecyclerView.NO_POSITION) {
                    clearPendingFocusAfterLoadMore()
                }
                false
            }

            else -> false
        }
    }

    private val childAttachListener = object : RecyclerView.OnChildAttachStateChangeListener {
        override fun onChildViewAttachedToWindow(view: View) = Unit

        override fun onChildViewDetachedFromWindow(view: View) {
            maybeProtectFocusOnChildDetach(view)
        }
    }

    fun install() {
        if (installed) {
            return
        }
        installed = true
        recyclerView.setTag(R.id.tag_recycler_load_more_focus_controller, this)
        if (originalOverScrollMode == null) {
            originalOverScrollMode = recyclerView.overScrollMode
        }
        recyclerView.overScrollMode = View.OVER_SCROLL_NEVER
        ensureDefaultFocusHighlightSuppressionHook()
        recyclerView.adapter?.registerAdapterDataObserver(adapterObserver)
        recyclerView.setOnKeyListener(recyclerKeyListener)
        recyclerView.addOnChildAttachStateChangeListener(childAttachListener)
        log("install", "state=${stateSummary()}")
    }

    fun release() {
        if (!installed) {
            return
        }
        installed = false
        if (recyclerView.getTag(R.id.tag_recycler_load_more_focus_controller) === this) {
            recyclerView.setTag(R.id.tag_recycler_load_more_focus_controller, null)
        }
        detachFocusRestoreToken++
        recyclerView.adapter?.unregisterAdapterDataObserver(adapterObserver)
        recyclerView.removeOnChildAttachStateChangeListener(childAttachListener)
        recyclerView.setOnKeyListener(null)
        clearPendingFocusAfterLoadMore()
        unparkFocusInRecyclerViewIfNeeded()
        removeDefaultFocusHighlightSuppressionHook()
        restoreRecyclerDefaultFocusHighlightIfSuppressed()
        originalOverScrollMode?.let { mode ->
            if (recyclerView.overScrollMode == View.OVER_SCROLL_NEVER) {
                recyclerView.overScrollMode = mode
            }
        }
        originalOverScrollMode = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            originalDefaultFocusHighlightEnabled?.let { enabled ->
                if (!recyclerView.defaultFocusHighlightEnabled) {
                    recyclerView.defaultFocusHighlightEnabled = enabled
                }
            }
        }
        originalDefaultFocusHighlightEnabled = null
        didSuppressDefaultFocusHighlight = false
        log("release", "state=${stateSummary()}")
    }

    fun notifyItemVerticalNavigation(target: View, direction: Int) {
        if (!installed || !config.isEnabled()) {
            return
        }
        if (direction != View.FOCUS_UP && direction != View.FOCUS_DOWN) {
            return
        }
        markVerticalNav(direction)
        val position = resolveAdapterPosition(target)
        position?.let(::rememberLastKnownFocus)
        log(
            "notifyVerticalNav",
            "dir=${directionName(direction)} pos=$position span=$lastKnownFocusedSpanIndex target=${viewSummary(target)} state=${stateSummary()}"
        )
    }

    fun handleItemDpadDown(target: View): Boolean {
        if (!installed || !config.isEnabled()) {
            return false
        }
        markVerticalNav(View.FOCUS_DOWN)
        val position = resolveAdapterPosition(target)
        log(
            "itemDown",
            "pos=$position span=$lastKnownFocusedSpanIndex target=${viewSummary(target)} state=${stateSummary()}"
        )
        if (position != null) {
            rememberLastKnownFocus(position)
            return handleDpadDown(target, position)
        }
        return handleDpadDownFallback(target)
    }

    fun clearPendingFocusAfterLoadMore() {
        log(
            "clearPending",
            "anchor=$pendingFocusAfterLoadMoreAnchorPos target=$pendingFocusAfterLoadMoreTargetPos state=${stateSummary()}"
        )
        pendingFocusAfterLoadMoreAnchorPos = RecyclerView.NO_POSITION
        pendingFocusAfterLoadMoreTargetPos = RecyclerView.NO_POSITION
        unparkFocusInRecyclerViewIfNeeded()
    }

    fun consumePendingFocusAfterLoadMore(): Boolean {
        val anchorPos = pendingFocusAfterLoadMoreAnchorPos
        if (anchorPos == RecyclerView.NO_POSITION) {
            return false
        }
        if (!installed || !config.isEnabled()) {
            clearPendingFocusAfterLoadMore()
            return false
        }

        val adapter = recyclerView.adapter ?: run {
            clearPendingFocusAfterLoadMore()
            return false
        }
        val itemCount = adapter.itemCount
        if (itemCount <= 0) {
            clearPendingFocusAfterLoadMore()
            return false
        }

        val focused = recyclerView.rootView?.findFocus()
        if (focused != null && !isDescendantOf(focused, recyclerView)) {
            clearPendingFocusAfterLoadMore()
            return false
        }

        val existingTarget = pendingFocusAfterLoadMoreTargetPos
        if (existingTarget != RecyclerView.NO_POSITION) {
            log(
                "consumePending.skipExisting",
                "anchor=$anchorPos target=$existingTarget itemCount=$itemCount state=${stateSummary()}"
            )
            if (existingTarget !in 0 until itemCount) {
                clearPendingFocusAfterLoadMore()
                return false
            }
            return true
        }

        val spanCount = spanCountForLayoutManager()?.coerceAtLeast(1) ?: 1
        val candidatePos = when {
            anchorPos + spanCount in 0 until itemCount -> anchorPos + spanCount
            anchorPos + 1 in 0 until itemCount -> anchorPos + 1
            else -> null
        }
        log(
            "consumePending",
            "anchor=$anchorPos itemCount=$itemCount spanCount=$spanCount candidate=$candidatePos state=${stateSummary()}"
        )
        if (candidatePos == null) {
            val fallbackPos = anchorPos.coerceIn(0, itemCount - 1)
            clearPendingFocusAfterLoadMore()
            scrollAndFocusAdapterPosition(fallbackPos, smooth = false)
            return true
        }

        pendingFocusAfterLoadMoreTargetPos = candidatePos
        parkFocusInRecyclerView(keepDescendantFocusabilityOverridden = true)
        scrollAndFocusAdapterPosition(
            position = candidatePos,
            smooth = true,
            onFocused = {
                if (pendingFocusAfterLoadMoreTargetPos == candidatePos) {
                    clearPendingFocusAfterLoadMore()
                }
            }
        )
        return true
    }

    private fun handleRecyclerFocusedDpadDown(): Boolean {
        if (pendingFocusAfterLoadMoreAnchorPos != RecyclerView.NO_POSITION) {
            log("recyclerDown.pending", "state=${stateSummary()}")
            parkFocusInRecyclerView(keepDescendantFocusabilityOverridden = true)
            return true
        }
        val itemCount = recyclerView.adapter?.itemCount ?: 0
        if (itemCount <= 0) {
            log("recyclerDown.empty", "state=${stateSummary()}")
            return true
        }
        if (restoreFocusAfterDetach()) {
            log("recyclerDown.restoreHandled", "state=${stateSummary()}")
            return true
        }
        val candidatePos = (firstVisibleAdapterPosition() ?: 0).coerceIn(0, itemCount - 1)
        log("recyclerDown.fallback", "candidate=$candidatePos state=${stateSummary()}")
        scrollAndFocusAdapterPosition(candidatePos, smooth = false)
        return true
    }

    private fun handleDpadDown(target: View, position: Int): Boolean {
        val rootItem = recyclerView.findContainingItemView(target) ?: target
        val next = FocusFinder.getInstance().findNextFocus(recyclerView, rootItem, View.FOCUS_DOWN)
        if (next != null && isDescendantOf(next, recyclerView)) {
            val nextPosition = resolveAdapterPosition(next)
            if (isValidDownCandidate(position, nextPosition)) {
                log("handleDown.nextFocus", "from=$position next=${viewSummary(next)}")
                next.requestFocus()
                return true
            }
            log(
                "handleDown.rejectNextFocus",
                "from=$position next=${viewSummary(next)} nextPos=$nextPosition currentSpan=${spanIndexForPosition(position)} nextSpan=${nextPosition?.let(::spanIndexForPosition)}"
            )
        }

        if (recyclerView.canScrollVertically(1)) {
            val dy = (rootItem.height * config.scrollOnDownEdgeFactor).toInt().coerceAtLeast(1)
            recyclerView.scrollBy(0, dy)
            val itemCount = recyclerView.adapter?.itemCount ?: 0
            val candidatePos = preferredDownCandidatePosition(position, itemCount)
            log(
                "handleDown.scroll",
                "from=$position dy=$dy itemCount=$itemCount candidate=$candidatePos state=${stateSummary()}"
            )
            recyclerView.postIfAlive {
                if (tryFocusNextDownFromCurrent()) {
                    return@postIfAlive
                }
                if (candidatePos != null) {
                    scrollAndFocusAdapterPosition(candidatePos, smooth = false)
                } else {
                    log("handleDown.noCandidateAfterScroll", "from=$position state=${stateSummary()}")
                }
            }
            return true
        }

        if (callbacks.canLoadMore()) {
            pendingFocusAfterLoadMoreAnchorPos = position
            pendingFocusAfterLoadMoreTargetPos = RecyclerView.NO_POSITION
            log("handleDown.loadMore", "anchor=$position state=${stateSummary()}")
            callbacks.loadMore()
            parkFocusInRecyclerView(keepDescendantFocusabilityOverridden = true)
        } else {
            log("handleDown.bottomNoMore", "from=$position state=${stateSummary()}")
        }
        return true
    }

    private fun handleDpadDownFallback(target: View): Boolean {
        val rootItem = recyclerView.findContainingItemView(target) ?: target
        val next = FocusFinder.getInstance().findNextFocus(recyclerView, rootItem, View.FOCUS_DOWN)
        if (next != null && isDescendantOf(next, recyclerView)) {
            val currentPosition = resolveAdapterPosition(target) ?: lastKnownFocusedAdapterPos
            val nextPosition = resolveAdapterPosition(next)
            if (currentPosition == RecyclerView.NO_POSITION || isValidDownCandidate(currentPosition, nextPosition)) {
                log("handleDownFallback.nextFocus", "next=${viewSummary(next)}")
                next.requestFocus()
                return true
            }
            log(
                "handleDownFallback.rejectNextFocus",
                "current=$currentPosition next=${viewSummary(next)} nextPos=$nextPosition currentSpan=${currentPosition.takeIf { it != RecyclerView.NO_POSITION }?.let(::spanIndexForPosition)} nextSpan=${nextPosition?.let(::spanIndexForPosition)}"
            )
        }

        if (recyclerView.canScrollVertically(1)) {
            val baseHeight = rootItem.height.takeIf { it > 0 } ?: (recyclerView.height / 2).coerceAtLeast(1)
            val dy = (baseHeight * config.scrollOnDownEdgeFactor).toInt().coerceAtLeast(1)
            log("handleDownFallback.scroll", "dy=$dy state=${stateSummary()}")
            recyclerView.scrollBy(0, dy)
            recyclerView.postIfAlive {
                tryFocusNextDownFromCurrent()
            }
            return true
        }

        if (callbacks.canLoadMore()) {
            val itemCount = recyclerView.adapter?.itemCount ?: 0
            val fallbackAnchor = when {
                lastKnownFocusedAdapterPos in 0 until itemCount -> lastKnownFocusedAdapterPos
                (lastVisibleAdapterPosition() ?: RecyclerView.NO_POSITION) in 0 until itemCount -> lastVisibleAdapterPosition()!!
                (firstVisibleAdapterPosition() ?: RecyclerView.NO_POSITION) in 0 until itemCount -> firstVisibleAdapterPosition()!!
                itemCount > 0 -> itemCount - 1
                else -> RecyclerView.NO_POSITION
            }
            if (fallbackAnchor != RecyclerView.NO_POSITION) {
                pendingFocusAfterLoadMoreAnchorPos = fallbackAnchor
                pendingFocusAfterLoadMoreTargetPos = RecyclerView.NO_POSITION
            }
            log("handleDownFallback.loadMore", "anchor=$fallbackAnchor state=${stateSummary()}")
            callbacks.loadMore()
            parkFocusInRecyclerView(keepDescendantFocusabilityOverridden = true)
        } else {
            log("handleDownFallback.bottomNoMore", "state=${stateSummary()}")
        }
        return true
    }

    private fun markVerticalNav(direction: Int) {
        lastVerticalNavAtMs = SystemClock.uptimeMillis()
        lastVerticalNavDirection = direction
        log("markVerticalNav", "dir=${directionName(direction)} state=${stateSummary()}")
    }

    private fun maybeProtectFocusOnChildDetach(detachedChild: View) {
        if (!installed || !config.isEnabled() || !recyclerView.isAttachedToWindow) {
            return
        }

        val hasPendingLoadMore = pendingFocusAfterLoadMoreAnchorPos != RecyclerView.NO_POSITION
        if (!hasPendingLoadMore) {
            val now = SystemClock.uptimeMillis()
            if (now - lastVerticalNavAtMs > focusProtectWindowMs) {
                log("detach.skipWindow", "child=${viewSummary(detachedChild)} delta=${now - lastVerticalNavAtMs} state=${stateSummary()}")
                return
            }
        }

        val focused = recyclerView.rootView?.findFocus()
        val focusWasInThisRecycler = focused != null && isDescendantOf(focused, recyclerView)
        if (!focusWasInThisRecycler && focused != null) {
            log("detach.skipExternalFocus", "child=${viewSummary(detachedChild)} focused=${viewSummary(focused)}")
            return
        }

        val detachingContainedFocus = focused != null && isDescendantOf(focused, detachedChild)
        if (!detachingContainedFocus && focused != null) {
            log("detach.skipOtherChild", "child=${viewSummary(detachedChild)} focused=${viewSummary(focused)}")
            return
        }

        log(
            "detach.protect",
            "child=${viewSummary(detachedChild)} pending=${pendingFocusAfterLoadMoreAnchorPos != RecyclerView.NO_POSITION} focused=${viewSummary(focused)} state=${stateSummary()}"
        )
        parkFocusInRecyclerView(keepDescendantFocusabilityOverridden = hasPendingLoadMore)

        val token = ++detachFocusRestoreToken
        recyclerView.postIfAlive {
            if (detachFocusRestoreToken != token) {
                log("detach.restore.cancelToken", "token=$token current=$detachFocusRestoreToken state=${stateSummary()}")
                return@postIfAlive
            }
            if (pendingFocusAfterLoadMoreAnchorPos != RecyclerView.NO_POSITION) {
                log("detach.restore.skipPendingLoadMore", "token=$token state=${stateSummary()}")
                return@postIfAlive
            }
            log("detach.restore.try", "token=$token state=${stateSummary()}")
            restoreFocusAfterDetach()
        }
    }

    private fun restoreFocusAfterDetach(): Boolean {
        val adapter = recyclerView.adapter ?: return false
        val itemCount = adapter.itemCount
        if (itemCount <= 0) {
            return false
        }

        val focused = recyclerView.rootView?.findFocus()
        if (focused != null && focused !== recyclerView && isDescendantOf(focused, recyclerView)) {
            log("restoreAfterDetach.alreadyFocused", "focused=${viewSummary(focused)} state=${stateSummary()}")
            return true
        }

        val firstVisible = firstVisibleAdapterPosition() ?: return false
        val lastVisible = lastVisibleAdapterPosition() ?: firstVisible
        if (lastVisible < firstVisible) {
            log(
                "restoreAfterDetach.skipInvalidVisibleRange",
                "first=$firstVisible last=$lastVisible state=${stateSummary()}"
            )
            return false
        }
        if (firstVisible !in 0 until itemCount) {
            return false
        }

        val anchor = pendingFocusAfterLoadMoreAnchorPos
            .takeIf { it != RecyclerView.NO_POSITION }
            ?: lastKnownFocusedAdapterPos.takeIf { it != RecyclerView.NO_POSITION }
            ?: firstVisible
        val spanCount = spanCountForLayoutManager()?.coerceAtLeast(1) ?: 1
        val desired = when (lastVerticalNavDirection) {
            View.FOCUS_UP -> anchor - spanCount
            else -> anchor + spanCount
        }.coerceIn(0, itemCount - 1)

        val visibleRangeCoversAnchor = anchor in firstVisible..lastVisible
        val visibleRangeCoversDesired = desired in firstVisible..lastVisible
        val visibleRangeNearAnchor = kotlin.math.abs(firstVisible - anchor) <= spanCount &&
            kotlin.math.abs(lastVisible - anchor) <= spanCount * 2

        if (!visibleRangeCoversAnchor && !visibleRangeCoversDesired && !visibleRangeNearAnchor) {
            log(
                "restoreAfterDetach.useDesiredDirectly",
                "first=$firstVisible last=$lastVisible anchor=$anchor desired=$desired dir=${directionName(lastVerticalNavDirection)} state=${stateSummary()}"
            )
            return scrollAndFocusAdapterPosition(desired, smooth = false)
        }

        val candidateInVisibleRange = desired.coerceIn(firstVisible, lastVisible)
        val candidate = when (val layoutManager = recyclerView.layoutManager) {
            is GridLayoutManager -> {
                val targetSpan = lastKnownFocusedSpanIndex
                if (targetSpan == null) {
                    candidateInVisibleRange
                } else {
                    findVisiblePositionWithSpanIndex(
                        layoutManager = layoutManager,
                        firstVisible = firstVisible,
                        lastVisible = lastVisible,
                        targetSpanIndex = targetSpan,
                        direction = lastVerticalNavDirection,
                        anchor = anchor,
                        desired = desired
                    ) ?: candidateInVisibleRange
                }
            }

            else -> candidateInVisibleRange
        }
        log(
            "restoreAfterDetach",
            "first=$firstVisible last=$lastVisible anchor=$anchor desired=$desired candidate=$candidate dir=${directionName(lastVerticalNavDirection)} span=$lastKnownFocusedSpanIndex state=${stateSummary()}"
        )
        return scrollAndFocusAdapterPosition(candidate, smooth = false)
    }

    private fun parkFocusInRecyclerView(keepDescendantFocusabilityOverridden: Boolean) {
        if (!installed || !config.isEnabled() || !recyclerView.isAttachedToWindow) {
            return
        }

        val focused = recyclerView.rootView?.findFocus()
        if (focused != null && focused !== recyclerView && !isDescendantOf(focused, recyclerView)) {
            log("park.skipExternalFocus", "focused=${viewSummary(focused)}")
            return
        }

        suppressRecyclerDefaultFocusHighlight()

        if (focusParkedDescendantFocusability == null) {
            focusParkedDescendantFocusability = recyclerView.descendantFocusability
            recyclerView.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        }

        if (recyclerView.isFocused || recyclerView.requestFocus()) {
            log(
                "park.focused",
                "keepOverride=$keepDescendantFocusabilityOverridden focused=${viewSummary(recyclerView.rootView?.findFocus())} state=${stateSummary()}"
            )
            if (!keepDescendantFocusabilityOverridden) {
                unparkFocusInRecyclerViewIfNeeded()
            }
            return
        }

        recyclerView.postIfAlive {
            val currentFocused = recyclerView.rootView?.findFocus()
            if (currentFocused != null &&
                currentFocused !== recyclerView &&
                !isDescendantOf(currentFocused, recyclerView)
            ) {
                log("park.retrySkipExternal", "focused=${viewSummary(currentFocused)}")
                restoreRecyclerDefaultFocusHighlightIfSuppressed()
                return@postIfAlive
            }

            suppressRecyclerDefaultFocusHighlight()
            if (!recyclerView.isFocused) {
                recyclerView.requestFocus()
            }
            log(
                "park.retry",
                "keepOverride=$keepDescendantFocusabilityOverridden focused=${viewSummary(recyclerView.rootView?.findFocus())} state=${stateSummary()}"
            )
            if (!keepDescendantFocusabilityOverridden) {
                unparkFocusInRecyclerViewIfNeeded()
            }
        }
    }

    private fun unparkFocusInRecyclerViewIfNeeded() {
        val original = focusParkedDescendantFocusability ?: return
        focusParkedDescendantFocusability = null
        recyclerView.descendantFocusability = original
    }

    private fun suppressRecyclerDefaultFocusHighlight() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        if (originalDefaultFocusHighlightEnabled == null) {
            originalDefaultFocusHighlightEnabled = recyclerView.defaultFocusHighlightEnabled
        }
        if (recyclerView.defaultFocusHighlightEnabled) {
            recyclerView.defaultFocusHighlightEnabled = false
            didSuppressDefaultFocusHighlight = true
        }
    }

    private fun restoreRecyclerDefaultFocusHighlightIfSuppressed() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !didSuppressDefaultFocusHighlight) {
            return
        }
        val original = originalDefaultFocusHighlightEnabled ?: return
        if (!recyclerView.defaultFocusHighlightEnabled) {
            recyclerView.defaultFocusHighlightEnabled = original
        }
        didSuppressDefaultFocusHighlight = false
    }

    private fun ensureDefaultFocusHighlightSuppressionHook() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || focusHighlightListener != null) {
            return
        }
        if (originalDefaultFocusHighlightEnabled == null) {
            originalDefaultFocusHighlightEnabled = recyclerView.defaultFocusHighlightEnabled
        }
        val listener = ViewTreeObserver.OnGlobalFocusChangeListener { oldFocus, newFocus ->
            if (!installed) {
                return@OnGlobalFocusChangeListener
            }
            if (newFocus === recyclerView) {
                suppressRecyclerDefaultFocusHighlight()
                maybeRecoverRecyclerFocus("globalFocus")
            } else if (oldFocus === recyclerView) {
                restoreRecyclerDefaultFocusHighlightIfSuppressed()
            }
        }
        focusHighlightListener = listener
        runCatching { recyclerView.viewTreeObserver.addOnGlobalFocusChangeListener(listener) }
        if (recyclerView.isFocused) {
            suppressRecyclerDefaultFocusHighlight()
            maybeRecoverRecyclerFocus("installFocused")
        }
    }

    private fun removeDefaultFocusHighlightSuppressionHook() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val listener = focusHighlightListener
        focusHighlightListener = null
        if (listener != null) {
            runCatching { recyclerView.viewTreeObserver.removeOnGlobalFocusChangeListener(listener) }
        }
    }

    private fun tryFocusNextDownFromCurrent(): Boolean {
        val focused = recyclerView.findFocus() ?: return false
        if (!isDescendantOf(focused, recyclerView)) {
            return false
        }
        val itemView = recyclerView.findContainingItemView(focused) ?: return false
        val currentPosition = resolveAdapterPosition(itemView)
        val next = FocusFinder.getInstance().findNextFocus(recyclerView, itemView, View.FOCUS_DOWN)
        val nextPosition = next?.let(::resolveAdapterPosition)
        if (next != null && isDescendantOf(next, recyclerView) && isValidDownCandidate(currentPosition, nextPosition)) {
            log("tryFocusNextDown.success", "from=${viewSummary(itemView)} next=${viewSummary(next)}")
            next.requestFocus()
            return true
        }
        log(
            "tryFocusNextDown.miss",
            "from=${viewSummary(itemView)} currentPos=$currentPosition next=${viewSummary(next)} nextPos=$nextPosition state=${stateSummary()}"
        )
        return false
    }

    private fun maybeRecoverRecyclerFocus(reason: String) {
        if (!installed || !config.isEnabled() || !recyclerView.isAttachedToWindow) {
            return
        }
        if (pendingFocusAfterLoadMoreAnchorPos != RecyclerView.NO_POSITION) {
            log("recoverRecyclerFocus.skipPending", "reason=$reason state=${stateSummary()}")
            return
        }
        if (recyclerView.rootView?.findFocus() !== recyclerView) {
            return
        }

        val now = SystemClock.uptimeMillis()
        val delta = now - lastVerticalNavAtMs
        val itemCount = recyclerView.adapter?.itemCount ?: 0
        val hasKnownFocus = lastKnownFocusedAdapterPos in 0 until itemCount
        if (!hasKnownFocus && delta > recyclerFocusRecoverWindowMs) {
            log(
                "recoverRecyclerFocus.skipWindow",
                "reason=$reason delta=$delta hasKnownFocus=$hasKnownFocus state=${stateSummary()}"
            )
            return
        }

        recyclerView.postIfAlive {
            if (!installed || !config.isEnabled() || !recyclerView.isAttachedToWindow) {
                return@postIfAlive
            }
            if (pendingFocusAfterLoadMoreAnchorPos != RecyclerView.NO_POSITION) {
                log("recoverRecyclerFocus.skipPendingPosted", "reason=$reason state=${stateSummary()}")
                return@postIfAlive
            }
            if (recyclerView.rootView?.findFocus() !== recyclerView) {
                return@postIfAlive
            }

            if (restoreFocusAfterDetach()) {
                log("recoverRecyclerFocus.restored", "reason=$reason state=${stateSummary()}")
                return@postIfAlive
            }

            val postedItemCount = recyclerView.adapter?.itemCount ?: 0
            if (postedItemCount <= 0) {
                return@postIfAlive
            }
            val fallbackPosition = when {
                lastKnownFocusedAdapterPos in 0 until postedItemCount -> lastKnownFocusedAdapterPos
                (firstVisibleAdapterPosition() ?: RecyclerView.NO_POSITION) in 0 until postedItemCount ->
                    firstVisibleAdapterPosition()!!
                else -> 0
            }
            log(
                "recoverRecyclerFocus.fallback",
                "reason=$reason fallback=$fallbackPosition state=${stateSummary()}"
            )
            scrollAndFocusAdapterPosition(fallbackPosition, smooth = false)
        }
    }

    private fun scrollAndFocusAdapterPosition(
        position: Int,
        smooth: Boolean,
        onFocused: (() -> Unit)? = null
    ): Boolean {
        val itemCount = recyclerView.adapter?.itemCount ?: return false
        if (position !in 0 until itemCount) {
            log("scrollAndFocus.invalid", "position=$position itemCount=$itemCount state=${stateSummary()}")
            maybeAbortPendingLoadMoreFocusRetry(position)
            return false
        }
        recyclerView.findViewHolderForAdapterPosition(position)?.itemView?.let { itemView ->
            if (isPartiallyVisibleInRecycler(itemView) && itemView.requestFocus()) {
                log("scrollAndFocus.immediate", "position=$position view=${viewSummary(itemView)} state=${stateSummary()}")
                onFocused?.invoke()
                return true
            }
        }
        log("scrollAndFocus.defer", "position=$position smooth=$smooth state=${stateSummary()}")
        if (smooth) {
            recyclerView.smoothScrollToPosition(position)
        } else {
            recyclerView.scrollToPosition(position)
        }
        retryFocusAdapterPosition(position, focusRetryMaxAttempts, onFocused)
        return true
    }

    private fun retryFocusAdapterPosition(
        position: Int,
        attemptsLeft: Int,
        onFocused: (() -> Unit)? = null
    ) {
        if (!isAliveForFocusJump()) {
            maybeAbortPendingLoadMoreFocusRetry(position)
            return
        }
        val itemCount = recyclerView.adapter?.itemCount ?: run {
            maybeAbortPendingLoadMoreFocusRetry(position)
            return
        }
        if (position !in 0 until itemCount) {
            maybeAbortPendingLoadMoreFocusRetry(position)
            return
        }
        recyclerView.findViewHolderForAdapterPosition(position)?.itemView?.let { itemView ->
            if (isPartiallyVisibleInRecycler(itemView) && itemView.requestFocus()) {
                log(
                    "retryFocus.success",
                    "position=$position attemptsLeft=$attemptsLeft view=${viewSummary(itemView)} state=${stateSummary()}"
                )
                onFocused?.invoke()
                return
            }
        }
        if (attemptsLeft <= 0) {
            log("retryFocus.giveUp", "position=$position state=${stateSummary()}")
            maybeAbortPendingLoadMoreFocusRetry(position)
            return
        }
        if (attemptsLeft == focusRetryMaxAttempts || attemptsLeft == 10 || attemptsLeft == 1) {
            log("retryFocus.wait", "position=$position attemptsLeft=$attemptsLeft state=${stateSummary()}")
        }
        recyclerView.postDelayedIfAlive(focusRetryDelayMillis) {
            retryFocusAdapterPosition(position, attemptsLeft - 1, onFocused)
        }
    }

    private fun maybeAbortPendingLoadMoreFocusRetry(position: Int) {
        if (pendingFocusAfterLoadMoreTargetPos != position) {
            return
        }
        if (pendingFocusAfterLoadMoreAnchorPos == RecyclerView.NO_POSITION) {
            return
        }
        clearPendingFocusAfterLoadMore()
    }

    private fun isAliveForFocusJump(): Boolean {
        if (!installed || !config.isEnabled() || !recyclerView.isAttachedToWindow) {
            return false
        }
        val focused = recyclerView.rootView?.findFocus()
        return focused == null || isDescendantOf(focused, recyclerView)
    }

    private fun isPartiallyVisibleInRecycler(itemView: View): Boolean {
        val parentHeight = recyclerView.height
        if (parentHeight <= 0) {
            return false
        }
        val parentTop = recyclerView.paddingTop
        val parentBottom = parentHeight - recyclerView.paddingBottom
        return itemView.bottom > parentTop && itemView.top < parentBottom
    }

    private fun spanCountForLayoutManager(): Int? {
        return when (val layoutManager = recyclerView.layoutManager) {
            is GridLayoutManager -> layoutManager.spanCount.coerceAtLeast(1)
            is LinearLayoutManager -> 1
            is StaggeredGridLayoutManager -> layoutManager.spanCount.coerceAtLeast(1)
            else -> null
        }
    }

    private fun rememberLastKnownFocus(position: Int) {
        lastKnownFocusedAdapterPos = position
        val spanCount = spanCountForLayoutManager()?.coerceAtLeast(1) ?: run {
            lastKnownFocusedSpanIndex = null
            return
        }
        lastKnownFocusedSpanIndex = when (val layoutManager = recyclerView.layoutManager) {
            is GridLayoutManager -> layoutManager.spanSizeLookup.getSpanIndex(position, spanCount)
            else -> position % spanCount
        }
        log("rememberFocus", "pos=$position span=$lastKnownFocusedSpanIndex state=${stateSummary()}")
    }

    private fun resolveAdapterPosition(view: View): Int? {
        val rootItem = recyclerView.findContainingItemView(view) ?: view
        val holder = recyclerView.findContainingViewHolder(rootItem) ?: return null
        return holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
            ?: holder.absoluteAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
            ?: holder.layoutPosition.takeIf { it != RecyclerView.NO_POSITION }
            ?: recyclerView.getChildLayoutPosition(rootItem).takeIf { it != RecyclerView.NO_POSITION }
    }

    private fun firstVisibleAdapterPosition(): Int? {
        return when (val layoutManager = recyclerView.layoutManager) {
            is GridLayoutManager -> layoutManager.findFirstVisibleItemPosition().takeIf { it != RecyclerView.NO_POSITION }
            is LinearLayoutManager -> layoutManager.findFirstVisibleItemPosition().takeIf { it != RecyclerView.NO_POSITION }
            is StaggeredGridLayoutManager -> {
                val first = IntArray(layoutManager.spanCount.coerceAtLeast(1))
                layoutManager.findFirstVisibleItemPositions(first)
                first.filter { it != RecyclerView.NO_POSITION }.minOrNull()
            }

            else -> null
        }
    }

    private fun lastVisibleAdapterPosition(): Int? {
        return when (val layoutManager = recyclerView.layoutManager) {
            is GridLayoutManager -> layoutManager.findLastVisibleItemPosition().takeIf { it != RecyclerView.NO_POSITION }
            is LinearLayoutManager -> layoutManager.findLastVisibleItemPosition().takeIf { it != RecyclerView.NO_POSITION }
            is StaggeredGridLayoutManager -> {
                val last = IntArray(layoutManager.spanCount.coerceAtLeast(1))
                layoutManager.findLastVisibleItemPositions(last)
                last.filter { it != RecyclerView.NO_POSITION }.maxOrNull()
            }

            else -> null
        }
    }

    private fun findVisiblePositionWithSpanIndex(
        layoutManager: GridLayoutManager,
        firstVisible: Int,
        lastVisible: Int,
        targetSpanIndex: Int,
        direction: Int,
        anchor: Int,
        desired: Int
    ): Int? {
        val spanCount = layoutManager.spanCount.coerceAtLeast(1)
        val lookup = layoutManager.spanSizeLookup
        if (direction == View.FOCUS_UP) {
            val directionalStart = minOf(lastVisible, anchor - 1, desired)
            for (position in directionalStart downTo firstVisible) {
                if (lookup.getSpanIndex(position, spanCount) == targetSpanIndex) {
                    return position
                }
            }
            for (position in minOf(lastVisible, desired) downTo firstVisible) {
                if (position < anchor && lookup.getSpanIndex(position, spanCount) == targetSpanIndex) {
                    return position
                }
            }
            return null
        }

        val directionalStart = maxOf(firstVisible, anchor + 1, desired)
        for (position in directionalStart..lastVisible) {
            if (lookup.getSpanIndex(position, spanCount) == targetSpanIndex) {
                return position
            }
        }
        for (position in maxOf(firstVisible, desired)..lastVisible) {
            if (position > anchor && lookup.getSpanIndex(position, spanCount) == targetSpanIndex) {
                return position
            }
        }
        return null
    }

    private fun preferredDownCandidatePosition(position: Int, itemCount: Int): Int? {
        if (position !in 0 until itemCount) {
            return null
        }
        return when (val layoutManager = recyclerView.layoutManager) {
            is GridLayoutManager -> {
                val spanCount = layoutManager.spanCount.coerceAtLeast(1)
                val preferred = position + spanCount
                if (preferred in 0 until itemCount &&
                    spanIndexForPosition(preferred) == spanIndexForPosition(position)
                ) {
                    preferred
                } else {
                    null
                }
            }

            else -> {
                val spanCount = spanCountForLayoutManager()?.coerceAtLeast(1) ?: 1
                when {
                    position + spanCount in 0 until itemCount -> position + spanCount
                    position + 1 in 0 until itemCount -> position + 1
                    else -> null
                }
            }
        }
    }

    private fun isValidDownCandidate(currentPosition: Int?, candidatePosition: Int?): Boolean {
        if (candidatePosition == null) {
            return false
        }
        if (currentPosition == null || currentPosition == RecyclerView.NO_POSITION) {
            return true
        }
        if (candidatePosition <= currentPosition) {
            return false
        }
        return when (recyclerView.layoutManager) {
            is GridLayoutManager -> spanIndexForPosition(candidatePosition) == spanIndexForPosition(currentPosition)
            else -> true
        }
    }

    private fun spanIndexForPosition(position: Int): Int? {
        if (position == RecyclerView.NO_POSITION) {
            return null
        }
        val spanCount = spanCountForLayoutManager()?.coerceAtLeast(1) ?: return null
        return when (val layoutManager = recyclerView.layoutManager) {
            is GridLayoutManager -> layoutManager.spanSizeLookup.getSpanIndex(position, spanCount)
            else -> position % spanCount
        }
    }

    private fun isDescendantOf(view: View, ancestor: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === ancestor) {
                return true
            }
            current = current.parent as? View
        }
        return false
    }

    private inline fun RecyclerView.postIfAlive(crossinline action: () -> Unit) {
        post {
            if (!installed || !config.isEnabled() || !isAttachedToWindow) {
                return@post
            }
            action()
        }
    }

    private inline fun RecyclerView.postDelayedIfAlive(
        delayMillis: Long,
        crossinline action: () -> Unit
    ) {
        postDelayed(
            {
                if (!installed || !config.isEnabled() || !isAttachedToWindow) {
                    return@postDelayed
                }
                action()
            },
            delayMillis
        )
    }

    private fun stateSummary(): String {
        return "focused=${viewSummary(recyclerView.rootView?.findFocus())}," +
            "rvFocused=${recyclerView.isFocused}," +
            "anchor=$pendingFocusAfterLoadMoreAnchorPos," +
            "target=$pendingFocusAfterLoadMoreTargetPos," +
            "lastPos=$lastKnownFocusedAdapterPos," +
            "lastSpan=$lastKnownFocusedSpanIndex," +
            "first=${firstVisibleAdapterPosition()}," +
            "last=${lastVisibleAdapterPosition()}"
    }

    private fun directionName(direction: Int): String {
        return when (direction) {
            View.FOCUS_UP -> "UP"
            View.FOCUS_DOWN -> "DOWN"
            View.FOCUS_LEFT -> "LEFT"
            View.FOCUS_RIGHT -> "RIGHT"
            else -> direction.toString()
        }
    }

    private fun viewSummary(view: View?): String {
        if (view == null) {
            return "null"
        }
        val position = resolveAdapterPosition(view)
        return "${view.javaClass.simpleName}@${Integer.toHexString(System.identityHashCode(view))}(pos=$position)"
    }

    private fun log(event: String, detail: String) {
        AppLog.d(TAG, "[${Integer.toHexString(System.identityHashCode(recyclerView))}] $event $detail")
    }
}

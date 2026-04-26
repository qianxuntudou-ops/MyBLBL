package com.tutu.myblbl.core.ui.focus.tv

import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.core.common.log.AppLog

class RecyclerViewFocusOperator(
    private val recyclerView: RecyclerView,
    private val adapter: TvFocusableAdapter
) {
    companion object {
        private const val TAG = "RVFocusOp"
    }

    private var focusToken = 0
    private var pendingFocusPosition = RecyclerView.NO_POSITION

    fun cancelPendingFocus() {
        focusToken++
        pendingFocusPosition = RecyclerView.NO_POSITION
    }

    fun focusPosition(
        position: Int,
        offsetTop: Int = 0,
        reason: String,
        onFocused: ((Int) -> Unit)? = null
    ): Boolean {
        if (!adapter.isFocusablePosition(position)) {
            AppLog.w(TAG, "focusPosition: pos=$position NOT focusable, reason=$reason")
            return false
        }
        if (!recyclerView.isAttachedToWindow) {
            AppLog.w(TAG, "focusPosition: pos=$position RV not attached, reason=$reason")
            return false
        }
        if (position != pendingFocusPosition) {
            focusToken++
        }
        pendingFocusPosition = position
        val token = focusToken
        if (requestAttachedPositionFocus(position, onFocused)) {
            pendingFocusPosition = RecyclerView.NO_POSITION
            return true
        }

        val layoutManager = recyclerView.layoutManager
        val alreadyVisible = isPositionVisible(position)
        AppLog.d(TAG, "focusPosition: pos=$position reason=$reason visible=$alreadyVisible attached=${recyclerView.isAttachedToWindow}")
        if (!alreadyVisible) {
            if (layoutManager is LinearLayoutManager) {
                layoutManager.scrollToPositionWithOffset(position, offsetTop)
            } else {
                recyclerView.scrollToPosition(position)
            }
        }

        scheduleAttachRetry(position, offsetTop, token, retryLeft = 2, onFocused = onFocused)
        return true
    }

    /**
     * ViewPager2 切换 Tab 后，RecyclerView 已测量布局（LayoutManager 认为条目可见），
     * 但 ViewHolder.itemView.isAttachedToWindow 可能还未触发。
     * 每帧重试一次，最多 [retryLeft] 次，避免焦点被静默吞掉。
     */
    private fun scheduleAttachRetry(
        position: Int,
        offsetTop: Int,
        token: Int,
        retryLeft: Int,
        onFocused: ((Int) -> Unit)?
    ) {
        recyclerView.post {
            if (token != focusToken) {
                AppLog.w(TAG, "focusPosition post: STALE token=$token current=$focusToken, pos=$position")
                return@post
            }
            if (!recyclerView.isAttachedToWindow) {
                AppLog.w(TAG, "focusPosition post: RV detached, pos=$position")
                return@post
            }
            if (requestAttachedPositionFocus(position, onFocused)) {
                pendingFocusPosition = RecyclerView.NO_POSITION
                return@post
            }
            // 条目对 LayoutManager 可见但 View 还未 attach——再等一帧
            val stillVisible = isPositionVisible(position)
            if (stillVisible && retryLeft > 0) {
                AppLog.d(TAG, "focusPosition post: visible but not attached yet, retry=$retryLeft pos=$position")
                scheduleAttachRetry(position, offsetTop, token, retryLeft - 1, onFocused)
                return@post
            }
            AppLog.w(TAG, "focusPosition post: direct focus failed, trying sameColumn then nearestVisible for pos=$position")
            val spanCount = (recyclerView.layoutManager as? GridLayoutManager)?.spanCount ?: 0
            if (spanCount > 0 && focusSameColumnVisible(position, spanCount, onFocused)) {
                pendingFocusPosition = RecyclerView.NO_POSITION
                return@post
            }
            focusNearestVisible(position, onFocused)
            pendingFocusPosition = RecyclerView.NO_POSITION
        }
    }

    private fun isPositionVisible(position: Int): Boolean {
        val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return false
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) return false
        return position in first..last
    }

    fun focusNearestVisible(
        preferredPosition: Int,
        onFocused: ((Int) -> Unit)? = null
    ): Boolean {
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return false
        val itemCount = adapter.focusableItemCount()
        if (itemCount <= 0) {
            return false
        }
        val first = layoutManager.findFirstVisibleItemPosition()
        val last = layoutManager.findLastVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) {
            return false
        }
        val visibleStart = first.coerceAtLeast(0)
        val visibleEnd = last.coerceAtMost(itemCount - 1)
        if (visibleStart > visibleEnd) {
            return false
        }
        val target = preferredPosition.coerceIn(visibleStart, visibleEnd)
        val candidates = buildList {
            add(target)
            var before = target - 1
            var after = target + 1
            while (before >= visibleStart || after <= visibleEnd) {
                if (after <= visibleEnd) add(after++)
                if (before >= visibleStart) add(before--)
            }
        }
        for (candidate in candidates) {
            if (requestAttachedPositionFocus(candidate, onFocused)) {
                return true
            }
        }
        return false
    }

    fun focusSameColumnVisible(
        preferredPosition: Int,
        spanCount: Int,
        onFocused: ((Int) -> Unit)? = null
    ): Boolean {
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return false
        if (layoutManager !is GridLayoutManager) return false
        val itemCount = adapter.focusableItemCount()
        if (itemCount <= 0 || spanCount <= 0) return false
        val first = layoutManager.findFirstVisibleItemPosition()
        val last = layoutManager.findLastVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) return false
        val column = layoutManager.spanSizeLookup.getSpanIndex(preferredPosition, spanCount)
        for (pos in last downTo first.coerceAtLeast(0)) {
            if (pos >= itemCount) continue
            val posColumn = layoutManager.spanSizeLookup.getSpanIndex(pos, spanCount)
            if (posColumn == column) {
                if (requestAttachedPositionFocus(pos, onFocused)) return true
            }
        }
        return false
    }

    private fun requestAttachedPositionFocus(
        position: Int,
        onFocused: ((Int) -> Unit)?
    ): Boolean {
        if (!adapter.isFocusablePosition(position)) {
            return false
        }
        val holder = recyclerView.findViewHolderForAdapterPosition(position)
        if (holder == null) {
            AppLog.d(TAG, "requestFocus: pos=$position NO holder (not attached)")
            return false
        }
        val itemView = holder.itemView
        if (itemView.visibility != View.VISIBLE) {
            AppLog.w(TAG, "requestFocus: pos=$position visibility=${itemView.visibility}")
            return false
        }
        if (!itemView.isAttachedToWindow) {
            AppLog.w(TAG, "requestFocus: pos=$position not attachedToWindow")
            return false
        }
        if (!isPartiallyVisible(itemView)) {
            AppLog.w(TAG, "requestFocus: pos=$position not partiallyVisible top=${itemView.top} bottom=${itemView.bottom} rvHeight=${recyclerView.height}")
            return false
        }
        val handled = itemView.requestFocus()
        if (handled) {
            onFocused?.invoke(position)
        } else {
            AppLog.w(TAG, "requestFocus: pos=$position requestFocus returned FALSE")
        }
        return handled
    }

    private fun isPartiallyVisible(itemView: View): Boolean {
        val parentHeight = recyclerView.height
        if (parentHeight <= 0) {
            return false
        }
        val parentTop = recyclerView.paddingTop
        val parentBottom = parentHeight - recyclerView.paddingBottom
        return itemView.bottom > parentTop && itemView.top < parentBottom
    }
}

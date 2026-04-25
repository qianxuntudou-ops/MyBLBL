package com.tutu.myblbl.core.ui.focus.tv

import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class RecyclerViewFocusOperator(
    private val recyclerView: RecyclerView,
    private val adapter: TvFocusableAdapter
) {
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
        if (layoutManager is LinearLayoutManager) {
            layoutManager.scrollToPositionWithOffset(position, offsetTop)
        } else {
            recyclerView.scrollToPosition(position)
        }

        recyclerView.post {
            if (token != focusToken || !recyclerView.isAttachedToWindow) {
                return@post
            }
            if (requestAttachedPositionFocus(position, onFocused)) {
                pendingFocusPosition = RecyclerView.NO_POSITION
                return@post
            }
            focusNearestVisible(position, onFocused)
            pendingFocusPosition = RecyclerView.NO_POSITION
        }
        return true
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

    private fun requestAttachedPositionFocus(
        position: Int,
        onFocused: ((Int) -> Unit)?
    ): Boolean {
        if (!adapter.isFocusablePosition(position)) {
            return false
        }
        val itemView = recyclerView.findViewHolderForAdapterPosition(position)?.itemView ?: return false
        if (itemView.visibility != View.VISIBLE || !itemView.isAttachedToWindow) {
            return false
        }
        if (!isPartiallyVisible(itemView)) {
            return false
        }
        val handled = itemView.requestFocus()
        if (handled) {
            onFocused?.invoke(position)
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

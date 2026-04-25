package com.tutu.myblbl.core.ui.focus.tv

import android.view.KeyEvent
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class TvListFocusController(
    private val recyclerView: RecyclerView,
    private val adapter: TvFocusableAdapter,
    private val strategy: TvFocusStrategy,
    private val canLoadMore: () -> Boolean,
    private val loadMore: () -> Unit
) {
    private val operator = RecyclerViewFocusOperator(recyclerView, adapter)
    private var currentAnchor: TvFocusAnchor? = null
    private var capturedAnchor: TvFocusAnchor? = null
    private var pendingMoveAfterLoadMore: TvFocusAnchor? = null

    fun onItemFocused(view: View, position: Int) {
        if (!adapter.isFocusablePosition(position)) {
            return
        }
        currentAnchor = createAnchor(view, position, TvFocusAnchor.Source.FOCUS)
    }

    fun handleKey(view: View, keyCode: Int, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) {
            return false
        }
        val direction = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
            else -> return false
        }
        if (direction != View.FOCUS_DOWN) {
            pendingMoveAfterLoadMore = null
        }
        val position = resolveAdapterPosition(view)
        if (position != RecyclerView.NO_POSITION && adapter.isFocusablePosition(position)) {
            currentAnchor = createAnchor(view, position, TvFocusAnchor.Source.FOCUS)
        }
        return move(direction)
    }

    fun onDataChanged(reason: TvDataChangeReason = TvDataChangeReason.REPLACE_PRESERVE_ANCHOR) {
        if (adapter.focusableItemCount() <= 0) {
            currentAnchor = null
            capturedAnchor = null
            pendingMoveAfterLoadMore = null
            operator.cancelPendingFocus()
            return
        }

        if (reason == TvDataChangeReason.USER_REFRESH) {
            clearAnchorForUserRefresh()
            return
        }

        if (reason == TvDataChangeReason.APPEND) {
            val pending = pendingMoveAfterLoadMore
            pendingMoveAfterLoadMore = null
            if (pending != null) {
                val target = strategy.nextPosition(pending, View.FOCUS_DOWN, adapter.focusableItemCount())
                if (target != null) {
                    focusPosition(target, pending.offsetTop, "append")
                }
            }
            return
        }

        if (hasValidFocusedItem()) {
            return
        }

        val anchor = currentAnchor ?: capturedAnchor
        if (anchor != null) {
            val resolved = resolveAnchorPosition(anchor)
            if (resolved != RecyclerView.NO_POSITION) {
                focusPosition(resolved, anchor.offsetTop, reason.name)
            }
        }
    }

    private fun hasValidFocusedItem(): Boolean {
        val focused = recyclerView.rootView?.findFocus() ?: return false
        val position = resolveAdapterPosition(focused)
        if (position == RecyclerView.NO_POSITION || !adapter.isFocusablePosition(position)) {
            return false
        }
        val itemView = recyclerView.findContainingItemView(focused) ?: focused
        return itemView.isAttachedToWindow && itemView.visibility == View.VISIBLE
    }

    fun focusPrimary(): Boolean {
        val itemCount = adapter.focusableItemCount()
        if (itemCount <= 0) {
            return false
        }
        val anchor = currentAnchor ?: capturedAnchor
        if (anchor != null) {
            val resolved = resolveAnchorPosition(anchor)
            if (resolved != RecyclerView.NO_POSITION) {
                return focusPosition(resolved, anchor.offsetTop, "primaryAnchor")
            }
        }
        val firstVisible = firstVisibleFocusablePosition()
        val target = if (firstVisible != RecyclerView.NO_POSITION) firstVisible else 0
        return focusPosition(target, 0, "primary")
    }

    fun requestFocusPosition(position: Int): Boolean {
        if (!adapter.isFocusablePosition(position)) {
            return false
        }
        val anchor = strategy.anchorFor(
            position = position,
            stableKey = adapter.stableKeyAt(position),
            offsetTop = 0
        )
        currentAnchor = anchor
        return focusPosition(position, anchor.offsetTop, "request")
    }

    fun captureCurrentAnchor() {
        val focused = recyclerView.rootView?.findFocus()
        val position = focused?.let(::resolveAdapterPosition) ?: RecyclerView.NO_POSITION
        capturedAnchor = if (focused != null && position != RecyclerView.NO_POSITION && adapter.isFocusablePosition(position)) {
            createAnchor(focused, position, TvFocusAnchor.Source.RETURN_RESTORE)
        } else {
            currentAnchor
        }
    }

    fun restoreCapturedAnchor(): Boolean {
        val anchor = capturedAnchor ?: currentAnchor ?: return false
        val position = resolveAnchorPosition(anchor)
        if (position == RecyclerView.NO_POSITION) {
            return false
        }
        return focusPosition(position, anchor.offsetTop, "returnRestore")
    }

    fun clearAnchorForUserRefresh() {
        currentAnchor = null
        capturedAnchor = null
        pendingMoveAfterLoadMore = null
        operator.cancelPendingFocus()
    }

    fun release() {
        clearAnchorForUserRefresh()
    }

    private fun move(direction: Int): Boolean {
        val itemCount = adapter.focusableItemCount()
        if (itemCount <= 0) {
            return false
        }
        val anchor = currentAnchor ?: anchorFromFocusedOrVisible() ?: return false
        val target = strategy.nextPosition(anchor, direction, itemCount)
        if (target != null) {
            return focusPosition(target, anchor.offsetTop, "move")
        }
        if (direction == View.FOCUS_DOWN && canLoadMore()) {
            pendingMoveAfterLoadMore = anchor.copy(source = TvFocusAnchor.Source.PENDING_LOAD_MORE)
            loadMore()
            return true
        }
        if (direction == View.FOCUS_DOWN && pendingMoveAfterLoadMore != null) {
            return true
        }
        return false
    }

    private fun focusPosition(position: Int, offsetTop: Int, reason: String): Boolean {
        return operator.focusPosition(position, offsetTop, reason) { focusedPosition ->
            currentAnchor = strategy.anchorFor(
                position = focusedPosition,
                stableKey = adapter.stableKeyAt(focusedPosition),
                offsetTop = offsetTop
            )
        }
    }

    private fun anchorFromFocusedOrVisible(): TvFocusAnchor? {
        val focused = recyclerView.rootView?.findFocus()
        val focusedPosition = focused?.let(::resolveAdapterPosition) ?: RecyclerView.NO_POSITION
        if (focused != null && focusedPosition != RecyclerView.NO_POSITION && adapter.isFocusablePosition(focusedPosition)) {
            return createAnchor(focused, focusedPosition, TvFocusAnchor.Source.FOCUS)
        }
        val visiblePosition = firstVisibleFocusablePosition()
        if (visiblePosition == RecyclerView.NO_POSITION) {
            return null
        }
        val visibleView = recyclerView.findViewHolderForAdapterPosition(visiblePosition)?.itemView
        val offset = visibleView?.let { it.top - recyclerView.paddingTop } ?: 0
        return strategy.anchorFor(
            position = visiblePosition,
            stableKey = adapter.stableKeyAt(visiblePosition),
            offsetTop = offset,
            source = TvFocusAnchor.Source.VISIBLE_ITEM
        )
    }

    private fun createAnchor(view: View, position: Int, source: TvFocusAnchor.Source): TvFocusAnchor {
        val itemView = recyclerView.findContainingItemView(view) ?: view
        val offsetTop = itemView.top - recyclerView.paddingTop
        return strategy.anchorFor(
            position = position,
            stableKey = adapter.stableKeyAt(position),
            offsetTop = offsetTop,
            source = source
        )
    }

    private fun resolveAnchorPosition(anchor: TvFocusAnchor): Int {
        val byKey = anchor.stableKey
            ?.let(adapter::findPositionByStableKey)
            ?.takeIf { it != RecyclerView.NO_POSITION && adapter.isFocusablePosition(it) }
        if (byKey != null) {
            return byKey
        }
        return anchor.adapterPosition
            .coerceIn(0, adapter.focusableItemCount() - 1)
            .takeIf(adapter::isFocusablePosition)
            ?: RecyclerView.NO_POSITION
    }

    private fun firstVisibleFocusablePosition(): Int {
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return RecyclerView.NO_POSITION
        val first = layoutManager.findFirstVisibleItemPosition()
        val last = layoutManager.findLastVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) {
            return RecyclerView.NO_POSITION
        }
        val max = adapter.focusableItemCount() - 1
        for (position in first.coerceAtLeast(0)..last.coerceAtMost(max)) {
            if (adapter.isFocusablePosition(position)) {
                return position
            }
        }
        return RecyclerView.NO_POSITION
    }

    private fun resolveAdapterPosition(view: View): Int {
        val itemView = recyclerView.findContainingItemView(view) ?: view
        val holder = recyclerView.findContainingViewHolder(itemView) ?: return RecyclerView.NO_POSITION
        return holder.absoluteAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
            ?: holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
            ?: holder.layoutPosition.takeIf { it != RecyclerView.NO_POSITION }
            ?: recyclerView.getChildAdapterPosition(itemView)
    }
}

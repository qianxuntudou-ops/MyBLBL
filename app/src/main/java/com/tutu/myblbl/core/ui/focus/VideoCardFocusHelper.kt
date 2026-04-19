package com.tutu.myblbl.core.ui.focus

import android.content.Context
import android.content.ContextWrapper
import android.view.KeyEvent
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.ui.activity.MainActivity

object VideoCardFocusHelper {

    fun bindSidebarExit(
        view: View,
        onTopEdgeUp: (() -> Boolean)? = null,
        onLeftEdge: (() -> Boolean)? = null,
        onRightEdge: (() -> Boolean)? = null,
        onBottomEdgeDown: (() -> Boolean)? = null,
        chainedListener: View.OnKeyListener? = null
    ) {
        view.setOnKeyListener { target, keyCode, event ->
            val handledBySidebar = handleSidebarNavigation(
                target, keyCode, event,
                onTopEdgeUp, onLeftEdge, onRightEdge, onBottomEdgeDown
            )
            if (handledBySidebar) {
                true
            } else {
                chainedListener?.onKey(target, keyCode, event) ?: false
            }
        }
    }

    private fun handleSidebarNavigation(
        target: View,
        keyCode: Int,
        event: KeyEvent,
        onTopEdgeUp: (() -> Boolean)?,
        onLeftEdge: (() -> Boolean)?,
        onRightEdge: (() -> Boolean)?,
        onBottomEdgeDown: (() -> Boolean)?
    ): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) {
            return false
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                val atLeftEdge = isAtLeftEdge(target)
                if (!atLeftEdge) {
                    return false
                }
                return onLeftEdge?.invoke()
                    ?: (target.context.findMainActivity()?.focusLeftFunctionArea() == true)
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val atRightEdge = isAtRightEdge(target)
                if (!atRightEdge) {
                    return false
                }
                return onRightEdge?.invoke() ?: true
            }

            KeyEvent.KEYCODE_DPAD_UP -> {
                val atTopEdge = isAtTopEdge(target)
                if (onTopEdgeUp == null || !atTopEdge) {
                    return false
                }
                return onTopEdgeUp()
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                val atBottomEdge = isAtBottomEdge(target)
                if (onBottomEdgeDown == null || !atBottomEdge) {
                    return false
                }
                return onBottomEdgeDown()
            }
        }
        return false
    }

    private fun keyName(keyCode: Int): String {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> "UP"
            KeyEvent.KEYCODE_DPAD_DOWN -> "DOWN"
            KeyEvent.KEYCODE_DPAD_LEFT -> "LEFT"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "RIGHT"
            else -> keyCode.toString()
        }
    }

    private fun isAtLeftEdge(view: View): Boolean {
        val recyclerView = view.findParentRecyclerView() ?: return false
        val layoutManager = recyclerView.layoutManager ?: return false
        val position = recyclerView.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION) {
            return false
        }
        return when (layoutManager) {
            is GridLayoutManager -> {
                layoutManager.spanSizeLookup.getSpanIndex(position, layoutManager.spanCount) == 0
            }
            is LinearLayoutManager -> layoutManager.orientation == RecyclerView.VERTICAL
            else -> false
        }
    }

    private fun isAtTopEdge(view: View): Boolean {
        val recyclerView = view.findParentRecyclerView() ?: return false
        val layoutManager = recyclerView.layoutManager ?: return false
        val position = recyclerView.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION) {
            return false
        }
        return when (layoutManager) {
            is GridLayoutManager -> {
                layoutManager.spanSizeLookup.getSpanGroupIndex(position, layoutManager.spanCount) == 0
            }

            is LinearLayoutManager -> {
                layoutManager.orientation == RecyclerView.VERTICAL && position == 0
            }

            else -> false
        }
    }

    private fun isAtRightEdge(view: View): Boolean {
        val recyclerView = view.findParentRecyclerView() ?: return false
        val layoutManager = recyclerView.layoutManager ?: return false
        val position = recyclerView.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION) {
            return false
        }
        return when (layoutManager) {
            is GridLayoutManager -> {
                val spanCount = layoutManager.spanCount
                val spanIndex = layoutManager.spanSizeLookup.getSpanIndex(position, spanCount)
                val spanSize = layoutManager.spanSizeLookup.getSpanSize(position)
                spanIndex + spanSize == spanCount
            }
            is LinearLayoutManager -> layoutManager.orientation == RecyclerView.VERTICAL
            else -> false
        }
    }

    private fun isAtBottomEdge(view: View): Boolean {
        val recyclerView = view.findParentRecyclerView() ?: return false
        val layoutManager = recyclerView.layoutManager ?: return false
        val adapter = recyclerView.adapter ?: return false
        val position = recyclerView.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION || adapter.itemCount <= 0) {
            return false
        }
        val lastPosition = adapter.itemCount - 1
        return when (layoutManager) {
            is GridLayoutManager -> {
                val currentGroup = layoutManager.spanSizeLookup.getSpanGroupIndex(position, layoutManager.spanCount)
                val lastGroup = layoutManager.spanSizeLookup.getSpanGroupIndex(lastPosition, layoutManager.spanCount)
                currentGroup == lastGroup
            }

            is LinearLayoutManager -> {
                layoutManager.orientation == RecyclerView.VERTICAL && position == lastPosition
            }

            else -> false
        }
    }

    private fun View.findParentRecyclerView(): RecyclerView? {
        var current = parent
        while (current != null) {
            if (current is RecyclerView) {
                return current
            }
            current = current.parent
        }
        return null
    }

    private fun Context.findMainActivity(): MainActivity? {
        var current = this
        while (current is ContextWrapper) {
            if (current is MainActivity) {
                return current
            }
            current = current.baseContext
        }
        return null
    }
}

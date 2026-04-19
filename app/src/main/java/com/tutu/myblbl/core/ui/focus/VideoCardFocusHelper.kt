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
        debugTag: String? = null
    ) {
        view.setOnKeyListener { target, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) {
                return@setOnKeyListener false
            }
            val position = target.findParentRecyclerView()?.getChildAdapterPosition(target) ?: RecyclerView.NO_POSITION
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    val atLeftEdge = isAtLeftEdge(target)
                    debugTag?.let {
                    }
                    if (!atLeftEdge) {
                        return@setOnKeyListener false
                    }
                    val handled = onLeftEdge?.invoke()
                        ?: (target.context.findMainActivity()?.focusLeftFunctionArea() == true)
                    debugTag?.let {
                    }
                    handled
                }

                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val atRightEdge = isAtRightEdge(target)
                    debugTag?.let {
                    }
                    if (!atRightEdge) {
                        return@setOnKeyListener false
                    }
                    val handled = onRightEdge?.invoke() ?: true
                    debugTag?.let {
                    }
                    handled
                }

                KeyEvent.KEYCODE_DPAD_UP -> {
                    val atTopEdge = isAtTopEdge(target)
                    debugTag?.let {
                    }
                    if (onTopEdgeUp == null || !atTopEdge) {
                        return@setOnKeyListener false
                    }
                    val handled = onTopEdgeUp()
                    debugTag?.let {
                    }
                    handled
                }

                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    val atBottomEdge = isAtBottomEdge(target)
                    debugTag?.let {
                    }
                    if (onBottomEdgeDown == null || !atBottomEdge) {
                        return@setOnKeyListener false
                    }
                    val handled = onBottomEdgeDown()
                    debugTag?.let {
                    }
                    handled
                }

                else -> false
            }
        }
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

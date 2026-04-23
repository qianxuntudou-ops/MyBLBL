package com.tutu.myblbl.core.ui.focus

import android.content.Context
import android.content.ContextWrapper
import android.view.KeyEvent
import android.view.View
import android.view.FocusFinder
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.R
import com.tutu.myblbl.ui.activity.MainActivity

object VideoCardFocusHelper {
    private val TAG_DETACH_LISTENER = R.id.tag_focus_detach_listener

    fun bindSidebarExit(
        view: View,
        onTopEdgeUp: (() -> Boolean)? = null,
        onLeftEdge: (() -> Boolean)? = null,
        onRightEdge: (() -> Boolean)? = null,
        onBottomEdgeDown: (() -> Boolean)? = null,
        chainedListener: View.OnKeyListener? = null
    ) {
        installDetachProtection(view)
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

    private fun installDetachProtection(view: View) {
        tryInstallDetachProtection(view)
        if (view.parent == null) {
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    v.removeOnAttachStateChangeListener(this)
                    tryInstallDetachProtection(v)
                }
                override fun onViewDetachedFromWindow(v: View) = Unit
            })
        }
    }

    private fun tryInstallDetachProtection(view: View) {
        val rv = view.findParentRecyclerView() ?: return
        if (rv.getTag(TAG_DETACH_LISTENER) != null) return
        val listener = object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(v: View) = Unit

            override fun onChildViewDetachedFromWindow(detached: View) {
                val focused = detached.rootView.findFocus() ?: return
                if (focused !== detached && !isDescendantOf(focused, detached)) return
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                val first = lm.findFirstVisibleItemPosition()
                val last = lm.findLastVisibleItemPosition()
                if (first == RecyclerView.NO_POSITION) return
                var pos = last
                while (pos >= first) {
                    val holder = rv.findViewHolderForAdapterPosition(pos)
                    if (holder != null && holder.itemView !== detached && holder.itemView.requestFocus()) {
                        return
                    }
                    pos--
                }
            }
        }
        rv.addOnChildAttachStateChangeListener(listener)
        rv.setTag(TAG_DETACH_LISTENER, true)
        rv.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                rv.removeOnChildAttachStateChangeListener(listener)
                rv.setTag(TAG_DETACH_LISTENER, null)
            }
        })
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
                RecyclerViewLoadMoreFocusController.fromView(target)?.let { controller ->
                    controller.notifyItemVerticalNavigation(target, View.FOCUS_UP)
                }
                val atTopEdge = isAtTopEdge(target)
                if (onTopEdgeUp == null || !atTopEdge) {
                    return false
                }
                return onTopEdgeUp()
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                val atBottomEdge = isAtBottomEdge(target)
                if (atBottomEdge && onBottomEdgeDown != null) {
                    return onBottomEdgeDown()
                }
                val loadMoreController = RecyclerViewLoadMoreFocusController.fromView(target)
                if (loadMoreController != null) {
                    return loadMoreController.handleItemDpadDown(target)
                }
                // No controller — handle focus navigation ourselves to prevent escape
                val rv = target.findParentRecyclerView()
                if (rv != null) {
                    val nextFocus = FocusFinder.getInstance().findNextFocus(rv, target, View.FOCUS_DOWN)
                    if (nextFocus != null && isDescendantOf(nextFocus, rv)) {
                        nextFocus.requestFocus()
                        return true
                    }
                    // No next focus in RV — consume event, don't let framework navigate away
                    return true
                }
                return true
            }
        }
        return false
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
                if (layoutManager.orientation == RecyclerView.HORIZONTAL) {
                    position == 0
                } else {
                    layoutManager.spanSizeLookup.getSpanIndex(position, layoutManager.spanCount) == 0
                }
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
        val adapter = recyclerView.adapter ?: return false
        val position = recyclerView.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION) {
            return false
        }
        return when (layoutManager) {
            is GridLayoutManager -> {
                if (layoutManager.orientation == RecyclerView.HORIZONTAL) {
                    position == adapter.itemCount - 1
                } else {
                    val spanCount = layoutManager.spanCount
                    val spanIndex = layoutManager.spanSizeLookup.getSpanIndex(position, spanCount)
                    val spanSize = layoutManager.spanSizeLookup.getSpanSize(position)
                    if (spanIndex + spanSize == spanCount) {
                        true
                    } else {
                        val currentGroup = layoutManager.spanSizeLookup
                            .getSpanGroupIndex(position, spanCount)
                        var nextPos = position + 1
                        while (nextPos < adapter.itemCount) {
                            val nextGroup = layoutManager.spanSizeLookup
                                .getSpanGroupIndex(nextPos, spanCount)
                            if (nextGroup != currentGroup) break
                            return false
                        }
                        true
                    }
                }
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

    private fun isDescendantOf(view: View, ancestor: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent as? View
        }
        return false
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

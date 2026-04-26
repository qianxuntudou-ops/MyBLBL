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
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.ui.activity.MainActivity

object VideoCardFocusHelper {
    private const val TAG = "VideoCardFocus"
    private val TAG_DETACH_LISTENER = R.id.tag_focus_detach_listener

    fun bindSidebarExit(
        view: View,
        onTopEdgeUp: (() -> Boolean)? = null,
        onLeftEdge: (() -> Boolean)? = null,
        onRightEdge: (() -> Boolean)? = null,
        onBottomEdgeDown: (() -> Boolean)? = null,
        handleListDpadDown: Boolean = true,
        chainedListener: View.OnKeyListener? = null
    ) {
        installDetachProtection(view)
        view.setOnKeyListener { target, keyCode, event ->
            val handledBySidebar = handleSidebarNavigation(
                target, keyCode, event,
                onTopEdgeUp, onLeftEdge, onRightEdge, onBottomEdgeDown, handleListDpadDown
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
                val detachedPos = rv.getChildAdapterPosition(detached)
                if (lm is GridLayoutManager && detachedPos != RecyclerView.NO_POSITION) {
                    val spanCount = lm.spanCount
                    val column = lm.spanSizeLookup.getSpanIndex(detachedPos, spanCount)
                    for (pos in last downTo first) {
                        val holder = rv.findViewHolderForAdapterPosition(pos)
                        if (holder != null && holder.itemView !== detached) {
                            val posColumn = lm.spanSizeLookup.getSpanIndex(pos, spanCount)
                            if (posColumn == column && holder.itemView.requestFocus()) {
                                return
                            }
                        }
                    }
                }
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
        onBottomEdgeDown: (() -> Boolean)?,
        handleListDpadDown: Boolean
    ): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) {
            return false
        }
        val rv = target.findParentRecyclerView()
        val pos = rv?.getChildAdapterPosition(target) ?: RecyclerView.NO_POSITION
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                val atLeftEdge = isAtLeftEdge(target)
                AppLog.d(TAG, "DPAD_LEFT pos=$pos atLeft=$atLeftEdge")
                if (!atLeftEdge) {
                    return false
                }
                val result = onLeftEdge?.invoke()
                    ?: (target.context.findMainActivity()?.focusLeftFunctionArea() == true)
                AppLog.d(TAG, "DPAD_LEFT pos=$pos edge→sidebar=$result")
                return result
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val atRightEdge = isAtRightEdge(target)
                AppLog.d(TAG, "DPAD_RIGHT pos=$pos atRight=$atRightEdge")
                if (!atRightEdge) {
                    return false
                }
                val result = onRightEdge?.invoke() ?: true
                AppLog.d(TAG, "DPAD_RIGHT pos=$pos edge→handled=$result")
                return result
            }

            KeyEvent.KEYCODE_DPAD_UP -> {
                RecyclerViewLoadMoreFocusController.fromView(target)?.let { controller ->
                    controller.notifyItemVerticalNavigation(target, View.FOCUS_UP)
                }
                val atTopEdge = isAtTopEdge(target)
                AppLog.d(TAG, "DPAD_UP pos=$pos atTop=$atTopEdge")
                if (atTopEdge && onTopEdgeUp != null) {
                    val result = onTopEdgeUp()
                    AppLog.d(TAG, "DPAD_UP pos=$pos topEdge→handled=$result")
                    return result
                }
                false
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                val atBottomEdge = isAtBottomEdge(target)
                AppLog.d(TAG, "DPAD_DOWN pos=$pos atBottom=$atBottomEdge handleList=$handleListDpadDown")
                if (atBottomEdge && onBottomEdgeDown != null) {
                    val result = onBottomEdgeDown()
                    AppLog.d(TAG, "DPAD_DOWN pos=$pos bottomEdge→handled=$result")
                    return result
                }
                if (!handleListDpadDown) {
                    AppLog.d(TAG, "DPAD_DOWN pos=$pos not handled by card, passing through")
                    return false
                }
                val loadMoreController = RecyclerViewLoadMoreFocusController.fromView(target)
                if (loadMoreController != null) {
                    val result = loadMoreController.handleItemDpadDown(target)
                    AppLog.d(TAG, "DPAD_DOWN pos=$pos loadMoreCtrl=$result")
                    return result
                }
                if (rv != null) {
                    val nextFocus = FocusFinder.getInstance().findNextFocus(rv, target, View.FOCUS_DOWN)
                    AppLog.d(TAG, "DPAD_DOWN pos=$pos FocusFinder next=${
                        nextFocus?.let {
                            val nPos = rv.getChildAdapterPosition(it)
                            "${it.javaClass.simpleName}(pos=$nPos)"
                        } ?: "null"
                    }")
                    if (nextFocus != null && isDescendantOf(nextFocus, rv)) {
                        nextFocus.requestFocus()
                        return true
                    }
                }
                AppLog.d(TAG, "DPAD_DOWN pos=$pos no next focus found, returning false")
                false
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

package com.tutu.myblbl.feature.search

import android.view.View
import android.view.ViewTreeObserver

class SearchFocusCoordinator(
    private val searchPanelRoots: () -> List<View>,
    private val resultPanelRoot: () -> View
) {

    private var lastSearchPanelFocusedView: View? = null
    private var lastResultPanelFocusedView: View? = null

    private val globalFocusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
        when {
            searchPanelRoots().any { isDescendantOf(newFocus, it) } -> {
                lastSearchPanelFocusedView = newFocus
            }

            isDescendantOf(newFocus, resultPanelRoot()) -> {
                lastResultPanelFocusedView = newFocus
            }
        }
    }

    fun register(root: View) {
        root.viewTreeObserver.addOnGlobalFocusChangeListener(globalFocusListener)
    }

    fun unregister(root: View?) {
        val observer = root?.viewTreeObserver ?: return
        if (observer.isAlive) {
            observer.removeOnGlobalFocusChangeListener(globalFocusListener)
        }
    }

    fun restoreSearchPanelFocus(
        anchorView: View? = null,
        focusCenterColumn: (View?) -> Boolean,
        focusHotColumn: (View?) -> Boolean,
        focusKeyboard: () -> Boolean
    ): Boolean {
        return requestFocusIfAvailable(lastSearchPanelFocusedView) ||
            focusCenterColumn(anchorView) ||
            focusHotColumn(anchorView) ||
            focusKeyboard()
    }

    fun restoreResultPanelFocus(
        anchorView: View? = null,
        focusCurrentResultContent: (View?) -> Boolean,
        focusResultHeader: (View?) -> Boolean
    ): Boolean {
        return requestFocusIfAvailable(lastResultPanelFocusedView) ||
            focusCurrentResultContent(anchorView) ||
            focusResultHeader(anchorView)
    }

    fun resolvePendingResultFocus(
        isResultPanelVisible: Boolean,
        pendingResultFocus: Boolean,
        focusCurrentResultContent: (View?) -> Boolean,
        focusResultHeader: (View?) -> Boolean,
        currentResultPageHasItems: () -> Boolean
    ): Boolean {
        if (!isResultPanelVisible || !pendingResultFocus) {
            return false
        }
        val anchorView = lastResultPanelFocusedView ?: lastSearchPanelFocusedView
        if (focusCurrentResultContent(anchorView)) {
            return true
        }
        return focusResultHeader(anchorView) && currentResultPageHasItems()
    }

    private fun requestFocusIfAvailable(view: View?): Boolean {
        return view != null &&
            view.visibility == View.VISIBLE &&
            view.isShown &&
            view.isFocusable &&
            view.requestFocus()
    }

    private fun isDescendantOf(view: View?, ancestor: View?): Boolean {
        var current = view
        while (current != null) {
            if (current === ancestor) {
                return true
            }
            current = current.parent as? View
        }
        return false
    }
}

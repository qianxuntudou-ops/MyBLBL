package com.tutu.myblbl.core.ui.refresh

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

object SwipeRefreshHelper {

    fun wrapRecyclerView(
        recyclerView: RecyclerView,
        onRefresh: () -> Unit
    ): SwipeRefreshLayout {
        return wrapRecyclerView(
            recyclerView = recyclerView,
            onRefresh = onRefresh,
            configure = null
        )
    }

    fun wrapRecyclerView(
        recyclerView: RecyclerView,
        onRefresh: () -> Unit,
        configure: (SwipeRefreshLayout.() -> Unit)? = null
    ): SwipeRefreshLayout {
        val parent = recyclerView.parent as? ViewGroup
            ?: throw IllegalStateException("RecyclerView must have a parent")
        val index = parent.indexOfChild(recyclerView)
        val lp = recyclerView.layoutParams
        parent.removeView(recyclerView)

        val context = recyclerView.context
        return SwipeRefreshLayout(context).apply {
            layoutParams = lp
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            addView(recyclerView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            setOnRefreshListener { onRefresh() }
            configure?.invoke(this)
            parent.addView(this, index)
        }
    }
}

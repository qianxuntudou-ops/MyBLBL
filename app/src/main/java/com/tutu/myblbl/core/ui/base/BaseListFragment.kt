package com.tutu.myblbl.core.ui.base

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.tutu.myblbl.databinding.FragmentBaseListBinding
import com.tutu.myblbl.core.ui.layout.WrapContentGridLayoutManager
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.core.ui.focus.SpatialFocusNavigator
import com.tutu.myblbl.core.ui.focus.TabContentFocusHelper

abstract class BaseListFragment<MODEL> : BaseFragment<FragmentBaseListBinding>() {

    companion object {
        private const val TAG = "MainEntryFocus"
    }

    protected var recyclerView: RecyclerView? = null
    protected var swipeRefreshLayout: SwipeRefreshLayout? = null
    protected var layoutManager: LinearLayoutManager? = null
    protected var adapter: BaseAdapter<MODEL, *>? = null

    protected var currentPage = 1
    protected var isLoading = false
    protected var hasMore = true
    protected val loadMoreThreshold = 12
    protected open val autoLoad: Boolean = true
    protected open val enableSwipeRefresh: Boolean = true

    abstract fun createAdapter(): BaseAdapter<MODEL, *>
    open fun loadData(page: Int) {}

    override fun getViewBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentBaseListBinding {
        return FragmentBaseListBinding.inflate(inflater, container!!)
    }

    override fun initView() {
        recyclerView = binding.recyclerView
        adapter = createAdapter()
        recyclerView?.adapter = adapter
        layoutManager = createLayoutManager()
        recyclerView?.layoutManager = layoutManager
        if (layoutManager is WrapContentGridLayoutManager) {
            val gridLM = layoutManager as WrapContentGridLayoutManager
            val adapterRef = adapter
            gridLM.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    if (adapterRef == null) return 1
                    return if (position == adapterRef.items.size && adapterRef.showLoadMore) {
                        getSpanCount()
                    } else {
                        1
                    }
                }
            }
        }
        recyclerView?.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                checkLoadMore()
            }
        })
        if (enableSwipeRefresh) {
            setupSwipeRefresh()
        }
    }

    override fun initData() {
        if (autoLoad) {
            refresh()
        }
    }

    private fun setupSwipeRefresh() {
        val rv = recyclerView ?: return
        val parent = rv.parent as? ViewGroup ?: return
        val index = parent.indexOfChild(rv)
        parent.removeView(rv)

        val context = rv.context
        swipeRefreshLayout = SwipeRefreshLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            addView(rv)
            setOnRefreshListener {
                refresh()
            }
        }
        parent.addView(swipeRefreshLayout, index)
    }

    protected fun setRefreshing(refreshing: Boolean) {
        swipeRefreshLayout?.isRefreshing = refreshing
    }

    open fun getSpanCount(): Int = 4

    open fun createLayoutManager(): LinearLayoutManager {
        return WrapContentGridLayoutManager(requireContext(), getSpanCount())
    }

    open fun refresh() {
        currentPage = 1
        loadData(1)
    }

    open fun checkLoadMore() {
        if (isLoading || !hasMore) return
        val lm = layoutManager ?: return
        val totalItemCount = lm.itemCount
        val lastVisiblePosition = lm.findLastVisibleItemPosition()
        if (lastVisiblePosition >= totalItemCount - loadMoreThreshold) {
            currentPage++
            loadData(currentPage)
        }
    }

    open fun scrollToTop() {
        recyclerView?.scrollToPosition(0)
    }

    open fun focusPrimaryContent(): Boolean {
        if (!isAdded || view == null) return false
        if (TabContentFocusHelper.requestVisibleFocus(buttonRetry, viewError)) {
            AppLog.d(TAG, "${javaClass.simpleName}.focusPrimaryContent stateTarget: handled=true")
            return true
        }
        val rv = recyclerView ?: return false
        val adp = adapter ?: return false

        adp.focusedView?.let { fv ->
            if (fv.isAttachedToWindow && fv.visibility == View.VISIBLE) {
                val handled = fv.requestFocus()
                val pos = findRecyclerViewChild(rv, fv)?.let { rv.getChildAdapterPosition(it) }
                    ?: RecyclerView.NO_POSITION
                AppLog.d(
                    TAG,
                    "${javaClass.simpleName}.focusPrimaryContent restoreFocusedView: handled=$handled pos=$pos view=${describeView(fv)}"
                )
                return handled
            }
        }

        val focusResult = TabContentFocusHelper.requestRecyclerPrimaryFocus(
            recyclerView = rv,
            itemCount = adp.contentCount()
        )
        if (focusResult.resolved) {
            AppLog.d(
                TAG,
                "${javaClass.simpleName}.focusPrimaryContent recycler: handled=${focusResult.handled} deferred=${focusResult.deferred} pos=${focusResult.position} source=${focusResult.source}"
            )
            return true
        }

        AppLog.d(
            TAG,
            "${javaClass.simpleName}.focusPrimaryContent failed: childCount=${rv.childCount} itemCount=${adp.contentCount()} focusedView=${describeView(adp.focusedView)}"
        )
        return false
    }

    open fun focusPrimaryContent(anchorView: View?, preferSpatialEntry: Boolean): Boolean {
        if (preferSpatialEntry) {
            val rv = recyclerView
            if (rv != null) {
                val handled = SpatialFocusNavigator.requestBestDescendant(
                    anchorView = anchorView,
                    root = rv,
                    direction = View.FOCUS_RIGHT,
                    fallback = null
                )
                AppLog.d(
                    TAG,
                    "${javaClass.simpleName}.focusPrimaryContent spatialEntry: handled=$handled anchor=${describeView(anchorView)}"
                )
                if (handled) {
                    return true
                }
            }
        }
        return focusPrimaryContent()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            val rv = recyclerView ?: return
            val currentFocusedView = activity?.currentFocus
            if (currentFocusedView != null && !currentFocusedView.isDescendantOf(rv)) {
                AppLog.d(
                    TAG,
                    "${javaClass.simpleName}.onHiddenChanged skipRestore: currentFocus=${describeView(currentFocusedView)}"
                )
                return
            }
            val fv = adapter?.focusedView
            if (fv != null && fv.isAttachedToWindow && fv.visibility == View.VISIBLE) {
                val lm = layoutManager ?: return
                val itemView = findRecyclerViewChild(rv, fv) ?: return
                val position = rv.getChildAdapterPosition(itemView)
                if (position == RecyclerView.NO_POSITION) return
                val first = lm.findFirstVisibleItemPosition()
                val last = lm.findLastVisibleItemPosition()
                if (position in first..last) {
                    RecyclerViewFocusRestoreHelper.requestFocusAtPosition(
                        recyclerView = rv,
                        position = position,
                        scrollIfMissing = false
                    )
                }
            }
        }
    }

    private fun findRecyclerViewChild(rv: RecyclerView, view: View): View? {
        var current: View? = view
        while (current != null) {
            val parent = current.parent
            if (parent === rv) return current
            if (parent !is View) return null
            current = parent
        }
        return null
    }

    override fun onDestroyView() {
        adapter?.clear()
        adapter = null
        layoutManager = null
        swipeRefreshLayout = null
        recyclerView = null
        super.onDestroyView()
    }

    private fun describeView(view: View?): String {
        if (view == null) {
            return "null"
        }
        return "${view.javaClass.simpleName}(hash=${System.identityHashCode(view)})"
    }

    private fun View.isDescendantOf(ancestor: View): Boolean {
        var current: View? = this
        while (current != null) {
            if (current === ancestor) {
                return true
            }
            current = current.parent as? View
        }
        return false
    }
}

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
import com.tutu.myblbl.core.ui.focus.RecyclerViewLoadMoreFocusController
import com.tutu.myblbl.core.ui.focus.SpatialFocusNavigator
import com.tutu.myblbl.core.ui.focus.TabContentFocusHelper
import com.tutu.myblbl.core.ui.focus.tv.GridTvFocusStrategy
import com.tutu.myblbl.core.ui.focus.tv.TvDataChangeReason
import com.tutu.myblbl.core.ui.focus.tv.TvFocusStrategy
import com.tutu.myblbl.core.ui.focus.tv.TvFocusableAdapter
import com.tutu.myblbl.core.ui.focus.tv.TvListFocusController

abstract class BaseListFragment<MODEL> : BaseFragment<FragmentBaseListBinding>() {

    companion object {
        val sharedVideoPool by lazy {
            RecyclerView.RecycledViewPool().apply {
                setMaxRecycledViews(0, 20)
            }
        }
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
    protected open val enableLoadMoreFocusController: Boolean = false
    protected open val enableTvListFocusController: Boolean = false
    private var pendingRecyclerIdleAction: (() -> Unit)? = null
    protected var loadMoreFocusController: RecyclerViewLoadMoreFocusController? = null
    protected var tvFocusController: TvListFocusController? = null
    private val restoreObserver = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() = onAdapterDataChangedForFocus(TvDataChangeReason.REPLACE_PRESERVE_ANCHOR)
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = onAdapterDataChangedForFocus(TvDataChangeReason.APPEND)
        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = onAdapterDataChangedForFocus(TvDataChangeReason.REMOVE_ITEM)
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = onAdapterDataChangedForFocus(TvDataChangeReason.REPLACE_PRESERVE_ANCHOR)
        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = onAdapterDataChangedForFocus(TvDataChangeReason.REPLACE_PRESERVE_ANCHOR)
    }

    abstract fun createAdapter(): BaseAdapter<MODEL, *>
    open fun loadData(page: Int) {}

    override fun getViewBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentBaseListBinding {
        return FragmentBaseListBinding.inflate(inflater, container!!)
    }

    override fun initView() {
        recyclerView = binding.recyclerView
        adapter = createAdapter()
        recyclerView?.adapter = adapter
        adapter?.registerAdapterDataObserver(restoreObserver)
        recyclerView?.setRecycledViewPool(sharedVideoPool)
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
        installTvListFocusControllerIfNeeded()
        recyclerView?.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                checkLoadMore()
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                if (newState != RecyclerView.SCROLL_STATE_IDLE) {
                    return
                }
                val action = pendingRecyclerIdleAction ?: return
                pendingRecyclerIdleAction = null
                recyclerView.post {
                    if (this@BaseListFragment.recyclerView === recyclerView && isAdded && view != null) {
                        action.invoke()
                    }
                }
            }
        })
        if (enableLoadMoreFocusController && !enableTvListFocusController) {
            installLoadMoreFocusController()
        }
        if (enableSwipeRefresh) {
            setupSwipeRefresh()
        }
    }

    override fun initData() {
        if (autoLoad) {
            refresh()
        }
    }

    override fun onPause() {
        captureListStateForReturnRestore()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        tvFocusController?.restoreCapturedAnchor()
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

    protected open fun createTvFocusStrategy(): TvFocusStrategy {
        return GridTvFocusStrategy { getSpanCount() }
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
        tvFocusController?.clearAnchorForUserRefresh()
        recyclerView?.scrollToPosition(0)
    }

    protected fun isRecyclerIdle(): Boolean {
        val rv = recyclerView ?: return true
        return rv.scrollState == RecyclerView.SCROLL_STATE_IDLE && !rv.isComputingLayout
    }

    protected fun runWhenRecyclerIdle(action: () -> Unit) {
        val rv = recyclerView
        if (rv == null || (rv.scrollState == RecyclerView.SCROLL_STATE_IDLE && !rv.isComputingLayout)) {
            action()
            return
        }
        pendingRecyclerIdleAction = action
    }

    protected fun setAdapterData(
        data: List<MODEL>,
        preserveScrollOffset: Boolean = false,
        onComplete: (() -> Unit)? = null
    ) {
        val adp = adapter ?: return
        if (!preserveScrollOffset) {
            adp.setData(data, onComplete)
            return
        }

        val rv = recyclerView
        val lm = layoutManager
        if (rv == null || lm == null || adp.contentCount() == 0) {
            adp.setData(data, onComplete)
            return
        }

        val anchorPosition = lm.findFirstVisibleItemPosition()
        val anchorView = lm.findViewByPosition(anchorPosition)
        val anchorOffset = if (anchorView != null) {
            anchorView.top - rv.paddingTop
        } else {
            0
        }

        adp.setData(data) {
            if (layoutManager === lm && anchorPosition != RecyclerView.NO_POSITION && data.isNotEmpty()) {
                val boundedAnchor = anchorPosition.coerceIn(0, data.lastIndex)
                lm.scrollToPositionWithOffset(boundedAnchor, anchorOffset)
            }
            onComplete?.invoke()
        }
    }

    open fun focusPrimaryContent(): Boolean {
        if (!isAdded || view == null) return false
        if (TabContentFocusHelper.requestVisibleFocus(buttonRetry, viewError)) {
            return true
        }
        if (tvFocusController?.focusPrimary() == true) {
            return true
        }
        val rv = recyclerView ?: return false
        val adp = adapter ?: return false

        val focusResult = TabContentFocusHelper.requestRecyclerPrimaryFocus(
            recyclerView = rv,
            itemCount = adp.contentCount()
        )
        if (focusResult.resolved) {
            return true
        }

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
            tvFocusController?.restoreCapturedAnchor()
        }
    }

    override fun onDestroyView() {
        loadMoreFocusController?.release()
        loadMoreFocusController = null
        tvFocusController?.release()
        tvFocusController = null
        adapter?.unregisterAdapterDataObserver(restoreObserver)
        adapter?.clear()
        adapter = null
        layoutManager = null
        swipeRefreshLayout = null
        recyclerView = null
        pendingRecyclerIdleAction = null
        super.onDestroyView()
    }

    private fun installLoadMoreFocusController() {
        val rv = recyclerView ?: return
        loadMoreFocusController?.release()
        loadMoreFocusController = RecyclerViewLoadMoreFocusController(
            recyclerView = rv,
            callbacks = object : RecyclerViewLoadMoreFocusController.Callbacks {
                override fun canLoadMore(): Boolean = hasMore && !isLoading

                override fun loadMore() {
                    if (isLoading || !hasMore) {
                        return
                    }
                    currentPage++
                    loadData(currentPage)
                }
            }
        ).also { it.install() }
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

    protected fun isPendingReturnRestore(): Boolean = false

    protected fun notifyTvListDataChanged(reason: TvDataChangeReason) {
        tvFocusController?.onDataChanged(reason)
    }

    protected fun clearTvFocusAnchorForUserRefresh() {
        tvFocusController?.clearAnchorForUserRefresh()
    }

    protected fun isTvListFocusEnabled(): Boolean = tvFocusController != null

    private fun onAdapterDataChangedForFocus(reason: TvDataChangeReason) {
        tvFocusController?.onDataChanged(reason)
    }

    private fun installTvListFocusControllerIfNeeded() {
        if (!enableTvListFocusController) {
            return
        }
        val rv = recyclerView ?: return
        val focusableAdapter = adapter as? TvFocusableAdapter ?: return
        tvFocusController = TvListFocusController(
            recyclerView = rv,
            adapter = focusableAdapter,
            strategy = createTvFocusStrategy(),
            canLoadMore = { hasMore },
            loadMore = {
                if (!isLoading && hasMore) {
                    currentPage++
                    loadData(currentPage)
                }
            }
        )
    }

    private fun captureListStateForReturnRestore() {
        tvFocusController?.captureCurrentAnchor()
    }
}

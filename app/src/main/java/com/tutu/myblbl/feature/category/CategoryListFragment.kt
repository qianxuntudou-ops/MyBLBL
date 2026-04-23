package com.tutu.myblbl.feature.category

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.ui.adapter.VideoAdapter
import com.tutu.myblbl.core.ui.base.BaseListFragment
import com.tutu.myblbl.core.ui.layout.WrapContentGridLayoutManager
import com.tutu.myblbl.core.common.content.ContentFilter
import com.tutu.myblbl.core.navigation.VideoRouteNavigator
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

class CategoryListFragment : BaseListFragment<VideoModel>() {
    companion object {
        private const val TAG = "CategoryFocus"
        private const val ARG_CATEGORY_ID = "category_id"
        private const val ARG_CATEGORY_NAME = "category_name"
        private const val CACHE_TTL_MS = 20 * 60 * 1000L

        fun newInstance(categoryId: Int, categoryName: String): CategoryListFragment {
            val fragment = CategoryListFragment()
            val args = Bundle()
            args.putInt(ARG_CATEGORY_ID, categoryId)
            args.putString(ARG_CATEGORY_NAME, categoryName)
            fragment.arguments = args
            return fragment
        }
    }

    private val viewModel: CategoryViewModel by viewModel()
    private var categoryId: Int = 0
    override val enableSwipeRefresh: Boolean = false
    override val autoLoad: Boolean = false
    override val enableLoadMoreFocusController: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        categoryId = arguments?.getInt(ARG_CATEGORY_ID) ?: 0
    }

    override fun createAdapter(): VideoAdapter {
        return VideoAdapter(
            onItemClick = ::onVideoClick,
            onTopEdgeUp = ::focusTopTab,
            onBottomEdgeDown = ::keepCurrentFocus
        )
    }

    override fun getSpanCount(): Int = 4

    override fun createLayoutManager() = object : WrapContentGridLayoutManager(requireContext(), getSpanCount()) {
        override fun onFocusSearchFailed(
            focused: View,
            direction: Int,
            recycler: RecyclerView.Recycler,
            state: RecyclerView.State
        ): View? {
            val result = super.onFocusSearchFailed(focused, direction, recycler, state)
            val fallback = if (
                result == null &&
                (direction == View.FOCUS_DOWN ||
                    direction == View.FOCUS_UP ||
                    direction == View.FOCUS_LEFT ||
                    direction == View.FOCUS_RIGHT)
            ) {
                focused
            } else {
                result
            }
            return fallback
        }
    }

    override fun loadData(page: Int) {
        if (viewModel.loading.value) {
            return
        }
        hasMore = false
        isLoading = true
        showLoading(true)
        viewModel.clearError()
        viewModel.loadCategoryVideos(categoryId, forceRefresh = true)
    }

    override fun checkLoadMore() {
    }

    override fun initView() {
        super.initView()
        adapter?.showLoadMore = false
        installFocusProtection()
    }

    private var childDetachListener: RecyclerView.OnChildAttachStateChangeListener? = null

    private fun installFocusProtection() {
        val rv = recyclerView ?: return
        childDetachListener = object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) = Unit

            override fun onChildViewDetachedFromWindow(detached: View) {
                val focused = activity?.currentFocus ?: return
                if (focused !== detached && !isDescendantOf(focused, detached)) return
                val lm = layoutManager ?: return
                val first = lm.findFirstVisibleItemPosition()
                val last = lm.findLastVisibleItemPosition()
                if (first == RecyclerView.NO_POSITION) return
                for (pos in last downTo first) {
                    val holder = rv.findViewHolderForAdapterPosition(pos)
                    if (holder != null && holder.itemView !== detached && holder.itemView.requestFocus()) {
                        return
                    }
                }
            }
        }
        childDetachListener?.let { rv.addOnChildAttachStateChangeListener(it) }
    }

    private fun isDescendantOf(view: android.view.View, ancestor: android.view.View): Boolean {
        var current: android.view.View? = view
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent as? android.view.View
        }
        return false
    }

    private fun viewId(view: android.view.View): String {
        val idName = try { view.context.resources.getResourceEntryName(view.id) } catch (_: Exception) { "${view.id}" }
        return "${view.javaClass.simpleName}($idName)"
    }

    private fun keyName(keyCode: Int): String = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> "UP"
        KeyEvent.KEYCODE_DPAD_DOWN -> "DOWN"
        KeyEvent.KEYCODE_DPAD_LEFT -> "LEFT"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "RIGHT"
        else -> keyCode.toString()
    }

    override fun onDestroyView() {
        childDetachListener?.let {
            recyclerView?.removeOnChildAttachStateChangeListener(it)
        }
        childDetachListener = null
        super.onDestroyView()
    }

    override fun initObserver() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.videos.collectLatest { rawVideos ->
                    val videos = ContentFilter.filterVideos(requireContext(), rawVideos)
                    isLoading = false
                    setRefreshing(false)
                    val currentItemCount = (adapter as? VideoAdapter)?.items?.size ?: 0
                    if (currentItemCount == videos.size && currentItemCount > 0) {
                        return@collectLatest
                    }
                    adapter?.setData(videos)
                    if (videos.isEmpty() && viewModel.hasLoaded.value && !viewModel.loading.value) {
                        showEmpty()
                    } else {
                        showContent()
                        showLoading(false)
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.loading.collectLatest { loading ->
                    if (loading && adapter?.itemCount == 0) {
                        showContent()
                        showLoading(true)
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.error.collectLatest { error ->
                    if (!error.isNullOrBlank()) {
                        isLoading = false
                        setRefreshing(false)
                        showError(error)
                        viewModel.clearError()
                    }
                }
            }
        }
    }

    private fun onVideoClick(video: VideoModel) {
        VideoRouteNavigator.openVideo(
            context = requireContext(),
            video = video,
            playQueue = com.tutu.myblbl.ui.activity.PlayerActivity.buildPlayQueue(
                (adapter as? VideoAdapter)?.getItemsSnapshot().orEmpty(),
                video
            )
        )
    }

    override fun onRetryClick() {
        refresh()
    }

    fun onTabSelected() {
        if (!isAdded || view == null || viewModel.loading.value || isLoading) {
            return
        }
        if (adapter?.itemCount == 0 || viewModel.isCacheStale(categoryId, CACHE_TTL_MS)) {
            loadData(1)
        }
    }

    override fun focusPrimaryContent(): Boolean {
        if (!isAdded || view == null) {
            return false
        }
        if (viewError?.visibility == View.VISIBLE && buttonRetry?.isShown == true) {
            val handled = buttonRetry?.requestFocus() == true
            return handled
        }
        val handled = super.focusPrimaryContent()
        return handled
    }

    private fun focusTopTab(): Boolean {
        val handled = (parentFragment as? CategoryFragment)?.focusCurrentTab() == true
        return handled
    }

    private fun keepCurrentFocus(): Boolean {
        val rv = recyclerView ?: return true
        val focused = activity?.currentFocus
        if (focused != null && focused.isAttachedToWindow && isDescendantOf(focused, rv)) {
            return true
        }
        rv.post {
            if (!isAdded || view == null) return@post
            val currentFocus = activity?.currentFocus
            if (currentFocus != null && isDescendantOf(currentFocus, rv)) return@post
            val lm = layoutManager ?: return@post
            val first = lm.findFirstVisibleItemPosition()
            val last = lm.findLastVisibleItemPosition()
            if (first == RecyclerView.NO_POSITION) return@post
            for (pos in last downTo first) {
                val holder = rv.findViewHolderForAdapterPosition(pos)
                if (holder?.itemView?.requestFocus() == true) return@post
            }
        }
        return true
    }


}

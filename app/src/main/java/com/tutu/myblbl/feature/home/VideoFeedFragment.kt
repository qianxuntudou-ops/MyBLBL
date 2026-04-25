package com.tutu.myblbl.feature.home

import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tutu.myblbl.R
import com.tutu.myblbl.core.common.ext.toast
import com.tutu.myblbl.core.navigation.VideoRouteNavigator
import com.tutu.myblbl.core.ui.base.BaseListFragment
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.ui.adapter.VideoAdapter
import com.tutu.myblbl.ui.fragment.main.MainNavigationViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

abstract class VideoFeedFragment : BaseListFragment<VideoModel>(), HomeTabPage {

    protected abstract val feedViewModel: VideoFeedViewModel
    protected abstract val secondaryTabPosition: Int
    protected open val dispatchHomeContentReady: Boolean = false
    protected open val toastNonEmptyError: Boolean = false

    private val mainNavigationViewModel: MainNavigationViewModel by activityViewModels()
    private var pendingScrollToTopAfterRefresh = false

    override val autoLoad: Boolean = false
    override val enableLoadMoreFocusController: Boolean = true

    override fun createAdapter(): VideoAdapter {
        return VideoAdapter(
            onItemClick = ::onVideoClick,
            onTopEdgeUp = ::focusTopTab,
            detectPortraitFromCover = false
        )
    }

    override fun getSpanCount(): Int = 4

    override fun loadData(page: Int) {
        isLoading = true
        feedViewModel.loadMore()
    }

    override fun refresh() {
        currentPage = 1
        hasMore = true
        pendingScrollToTopAfterRefresh = true
        isLoading = true
        feedViewModel.refresh()
    }

    override fun initView() {
        super.initView()
        adapter?.setShowLoadMore(true)
    }

    override fun initData() {
        showLoading(true)
        feedViewModel.loadInitial()
    }

    override fun initObserver() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                feedViewModel.uiState.collectLatest { state ->
                    renderState(state)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                mainNavigationViewModel.events.collectLatest { event ->
                    if (!isResumed || view == null) {
                        return@collectLatest
                    }
                    when (event) {
                        is MainNavigationViewModel.Event.MainTabReselected -> {
                            if (event.index == 0 && !isLoading) {
                                refresh()
                            }
                        }

                        is MainNavigationViewModel.Event.SecondaryTabReselected -> {
                            if (event.host == MainNavigationViewModel.SecondaryTabHost.HOME &&
                                event.position == secondaryTabPosition &&
                                !isLoading
                            ) {
                                refresh()
                            }
                        }

                        MainNavigationViewModel.Event.MenuPressed -> {
                            if (!isLoading) {
                                refresh()
                            }
                        }

                        MainNavigationViewModel.Event.BackPressed -> scrollToTop()
                        else -> Unit
                    }
                }
            }
        }
    }

    override fun onRetryClick() {
        refresh()
    }

    override fun focusPrimaryContent(): Boolean {
        if (!isAdded || view == null) {
            return false
        }
        if (viewError?.visibility == View.VISIBLE && buttonRetry?.isShown == true) {
            return buttonRetry?.requestFocus() == true
        }
        return super<BaseListFragment>.focusPrimaryContent()
    }

    override fun focusPrimaryContent(anchorView: View?, preferSpatialEntry: Boolean): Boolean {
        return super<BaseListFragment>.focusPrimaryContent(anchorView, preferSpatialEntry)
    }

    private fun renderState(state: FeedUiState<VideoModel>) {
        isLoading = state.loadingInitial || state.refreshing || state.appending
        hasMore = state.hasMore
        setRefreshing(state.refreshing)
        adapter?.setShowLoadMore(state.hasMore)

        if (state.loadingInitial && state.items.isEmpty()) {
            showLoading(true)
            return
        }

        state.errorMessage?.let { message ->
            isLoading = false
            setRefreshing(false)
            if ((adapter?.contentCount() ?: 0) == 0) {
                showLoading(false)
                showError(message.ifBlank { getString(R.string.net_error) })
            } else {
                loadMoreFocusController?.clearPendingFocusAfterLoadMore()
                if (toastNonEmptyError && message.isNotBlank()) {
                    requireContext().toast(message)
                }
            }
            return
        }

        val listChange = if (
            state.listChange == FeedListChange.NONE &&
            state.items.isNotEmpty() &&
            (adapter?.contentCount() ?: 0) == 0
        ) {
            FeedListChange.REPLACE
        } else {
            state.listChange
        }

        when (listChange) {
            FeedListChange.NONE -> Unit
            FeedListChange.REPLACE -> applyReplacedVideos(state.items)
            FeedListChange.APPEND -> applyAppendedVideos(state.items)
        }
    }

    private fun applyReplacedVideos(videos: List<VideoModel>) {
        val shouldDeferApply = (adapter?.contentCount() ?: 0) > 0 &&
            !pendingScrollToTopAfterRefresh &&
            !isRecyclerIdle()
        if (shouldDeferApply) {
            runWhenRecyclerIdle {
                if (!isAdded || view == null) {
                    return@runWhenRecyclerIdle
                }
                applyReplacedVideosNow(videos)
            }
            return
        }
        applyReplacedVideosNow(videos)
    }

    private fun applyReplacedVideosNow(videos: List<VideoModel>) {
        isLoading = false
        setRefreshing(false)
        val shouldPreserveScroll = (adapter?.contentCount() ?: 0) > 0 &&
            !pendingScrollToTopAfterRefresh
        setAdapterData(videos, preserveScrollOffset = shouldPreserveScroll)
        if (videos.isNotEmpty()) {
            showContent()
            showLoading(false)
            dispatchContentReadyIfNeeded()
            if (pendingScrollToTopAfterRefresh && !isPendingReturnRestore()) {
                scrollToTop()
            }
            pendingScrollToTopAfterRefresh = false
        } else {
            pendingScrollToTopAfterRefresh = false
            showEmpty()
        }
        feedViewModel.consumeListChange()
    }

    private fun applyAppendedVideos(items: List<VideoModel>) {
        isLoading = false
        setRefreshing(false)
        val currentCount = adapter?.contentCount() ?: 0
        val newItems = items.drop(currentCount)
        if (newItems.isNotEmpty()) {
            (adapter as? VideoAdapter)?.addData(newItems)
            showContent()
            showLoading(false)
            dispatchContentReadyIfNeeded()
        }
        loadMoreFocusController?.consumePendingFocusAfterLoadMore()
        feedViewModel.consumeListChange()
    }

    private fun dispatchContentReadyIfNeeded() {
        if (dispatchHomeContentReady) {
            mainNavigationViewModel.dispatch(MainNavigationViewModel.Event.HomeContentReady)
        }
    }

    private fun focusTopTab(): Boolean {
        return (parentFragment as? HomeFragment)?.focusCurrentTab() == true
    }

    private fun onVideoClick(video: VideoModel) {
        val ctx = context ?: return
        VideoRouteNavigator.openVideo(
            context = ctx,
            video = video,
            playQueue = com.tutu.myblbl.ui.activity.PlayerActivity.buildPlayQueue(
                (adapter as? VideoAdapter)?.getItemsSnapshot().orEmpty(),
                video
            )
        )
    }
}

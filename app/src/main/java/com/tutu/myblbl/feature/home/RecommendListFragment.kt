package com.tutu.myblbl.feature.home

import android.os.Bundle
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tutu.myblbl.R
import com.tutu.myblbl.event.AppEventHub
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.repository.cache.HomeCacheStore
import com.tutu.myblbl.ui.adapter.VideoAdapter
import com.tutu.myblbl.core.ui.base.BaseListFragment
import com.tutu.myblbl.ui.fragment.main.MainNavigationViewModel
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.core.common.content.ContentFilter
import com.tutu.myblbl.core.navigation.VideoRouteNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class RecommendListFragment : BaseListFragment<VideoModel>(), HomeTabPage {

    companion object {
        private const val TAG = "RecommendListFragment"
        private const val CACHE_KEY = "recommendCacheList"

        fun newInstance(): RecommendListFragment {
            return RecommendListFragment()
        }
    }

    private val appEventHub: AppEventHub by inject()
    private val viewModel: RecommendViewModel by viewModel()
    private val mainNavigationViewModel: MainNavigationViewModel by activityViewModels()
    private var loadingPage = 1
    private var waitingForFirstLoad = true
    private var cacheRestoreJob: Job? = null
    private var pendingScrollToTopAfterRefresh = false

    override val autoLoad: Boolean = false

    override fun createAdapter(): VideoAdapter {
        return VideoAdapter(
            onItemClick = ::onVideoClick,
            onTopEdgeUp = ::focusTopTab
        )
    }

    override fun getSpanCount(): Int = 4

    private fun focusTopTab(): Boolean {
        return (parentFragment as? HomeFragment)?.focusCurrentTab() == true
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

    override fun loadData(page: Int) {
        isLoading = true
        loadingPage = page
        viewModel.loadRecommendList(page, 24)
    }

    override fun refresh() {
        cacheRestoreJob?.cancel()
        currentPage = 1
        hasMore = true
        waitingForFirstLoad = true
        pendingScrollToTopAfterRefresh = true
        loadData(1)
    }

    override fun initView() {
        super.initView()
        adapter?.setShowLoadMore(true)
    }

    override fun initData() {
        showLoading(true)
        restoreCacheThenLoad()
    }

    private fun restoreCacheThenLoad() {
        cacheRestoreJob?.cancel()
        cacheRestoreJob = viewLifecycleOwner.lifecycleScope.launch {
            val hasCache = restoreCachedVideos()
            if (!hasCache) {
                showLoading(true)
            }
            loadData(1)
        }
    }

    override fun initObserver() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.videos.collectLatest { rawVideos ->
                    val videos = withContext(Dispatchers.Default) {
                        ContentFilter.filterVideos(requireContext(), rawVideos)
                    }
                    if (waitingForFirstLoad && videos.isEmpty()) {
                        return@collectLatest
                    }
                    waitingForFirstLoad = false
                    isLoading = false
                    setRefreshing(false)
                    if (loadingPage == 1 || (adapter as? VideoAdapter)?.items.isNullOrEmpty()) {
                        adapter?.setData(videos)
                    } else {
                        (adapter as? VideoAdapter)?.addData(videos)
                    }
                    if (videos.isNotEmpty()) {
                        showContent()
                        showLoading(false)
                        mainNavigationViewModel.dispatch(MainNavigationViewModel.Event.HomeContentReady)
                        if (loadingPage == 1) {
                            cacheVideos(videos)
                            if (pendingScrollToTopAfterRefresh && !isPendingReturnRestore()) {
                                scrollToTop()
                            }
                            pendingScrollToTopAfterRefresh = false
                        }
                    } else if (loadingPage == 1) {
                        pendingScrollToTopAfterRefresh = false
                        showEmpty()
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.hasMore.collectLatest { canLoadMore ->
                    hasMore = canLoadMore
                    adapter?.setShowLoadMore(canLoadMore)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.loading.collectLatest { loading ->
                    if (loading && adapter?.contentCount() == 0) {
                        showLoading(true)
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.error.collectLatest { error ->
                    error?.let {
                        AppLog.e(TAG, "error observer update: $it")
                        waitingForFirstLoad = false
                        isLoading = false
                        setRefreshing(false)
                        if (adapter?.contentCount() == 0) {
                            restoreCachedVideos(
                                onMiss = {
                                    showError(it.ifBlank { getString(R.string.net_error) })
                                }
                            )
                        }
                    }
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
                                event.position == 0 &&
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

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                appEventHub.events.collectLatest { event ->
                    if (event == AppEventHub.Event.UserSessionChanged && isResumed && !isLoading) {
                        refresh()
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

    private fun cacheVideos(videos: List<VideoModel>) {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                HomeCacheStore.writeVideos(CACHE_KEY, videos)
            }.onFailure { throwable ->
                AppLog.e(TAG, "cacheVideos failure: ${throwable.message}", throwable)
            }
        }
    }

    private suspend fun restoreCachedVideos(onMiss: (() -> Unit)? = null): Boolean {
        val cachedVideos = runCatching {
            HomeCacheStore.readVideos(CACHE_KEY)
        }.getOrElse { throwable ->
            AppLog.e(TAG, "restoreCachedVideos failure: ${throwable.message}", throwable)
            emptyList()
        }
        if (cachedVideos.isEmpty()) {
            onMiss?.invoke()
            return false
        }
        adapter?.setData(ContentFilter.filterVideos(requireContext(), cachedVideos))
        adapter?.setShowLoadMore(true)
        showContent()
        showLoading(false)
        mainNavigationViewModel.dispatch(MainNavigationViewModel.Event.HomeContentReady)
        return true
    }

}

package com.tutu.myblbl.feature.home

import android.os.Bundle
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tutu.myblbl.R
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.repository.cache.HomeCacheStore
import com.tutu.myblbl.ui.adapter.VideoAdapter
import com.tutu.myblbl.core.ui.base.BaseListFragment
import com.tutu.myblbl.ui.fragment.main.MainNavigationViewModel
import com.tutu.myblbl.core.common.content.ContentFilter
import com.tutu.myblbl.core.navigation.VideoRouteNavigator
import com.tutu.myblbl.core.common.ext.toast
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

class HotListFragment : BaseListFragment<VideoModel>(), HomeTabPage {

    companion object {
        private const val CACHE_KEY = "hotCacheList"

        fun newInstance(): HotListFragment {
            return HotListFragment()
        }
    }

    private val viewModel: HotViewModel by viewModel()
    private val mainNavigationViewModel: MainNavigationViewModel by activityViewModels()
    private var loadingPage = 1
    private var waitingForFirstLoad = true
    private var cacheRestoreJob: Job? = null

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
        viewModel.loadHotList(page, 24)
    }

    override fun refresh() {
        currentPage = 1
        waitingForFirstLoad = true
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
                    val videos = ContentFilter.filterVideos(requireContext(), rawVideos)
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
                        if (loadingPage == 1) {
                            cacheVideos(videos)
                            scrollToTop()
                        }
                    } else if (loadingPage == 1) {
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
                        waitingForFirstLoad = false
                        isLoading = false
                        setRefreshing(false)
                        if (adapter?.contentCount() == 0) {
                            restoreCachedVideos(
                                onMiss = {
                                    showError(it.ifBlank { getString(R.string.net_error) })
                                }
                            )
                        } else if (it.isNotBlank()) {
                            requireContext().toast(it)
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
                        is MainNavigationViewModel.Event.MainTabReselected ->
                            if (event.index == 0 && !isLoading) {
                                refresh()
                            }

                        is MainNavigationViewModel.Event.SecondaryTabReselected ->
                            if (event.host == MainNavigationViewModel.SecondaryTabHost.HOME &&
                                event.position == 1 &&
                                !isLoading
                            ) {
                                refresh()
                            }

                        MainNavigationViewModel.Event.MenuPressed ->
                            if (!isLoading) {
                                refresh()
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

    private fun cacheVideos(videos: List<VideoModel>) {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                HomeCacheStore.writeVideos(CACHE_KEY, videos)
            }
        }
    }

    private suspend fun restoreCachedVideos(onMiss: (() -> Unit)? = null): Boolean {
        val cachedVideos = runCatching {
            HomeCacheStore.readVideos(CACHE_KEY)
        }.getOrElse { emptyList() }
        if (cachedVideos.isEmpty()) {
            onMiss?.invoke()
            return false
        }
        adapter?.setData(ContentFilter.filterVideos(requireContext(), cachedVideos))
        adapter?.setShowLoadMore(true)
        showContent()
        showLoading(false)
        return true
    }

}

package com.tutu.myblbl.feature.home

import android.view.View
import androidx.core.os.bundleOf
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.tutu.myblbl.R
import com.tutu.myblbl.model.lane.HomeLaneSection
import com.tutu.myblbl.repository.HomeLaneRepository
import com.tutu.myblbl.repository.cache.HomeCacheStore
import com.tutu.myblbl.ui.adapter.HomeLaneAdapter
import com.tutu.myblbl.core.ui.base.BaseAdapter
import com.tutu.myblbl.core.ui.base.BaseListFragment
import com.tutu.myblbl.core.ui.base.RecyclerViewFocusRestoreHelper
import com.tutu.myblbl.ui.fragment.main.MainNavigationViewModel
import com.tutu.myblbl.feature.series.AllSeriesFragment
import com.tutu.myblbl.feature.series.SeriesDetailFragment
import com.tutu.myblbl.ui.dialog.MyFollowingDialog
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.core.ui.focus.SpatialFocusNavigator
import com.tutu.myblbl.core.ui.focus.TabContentFocusHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import org.koin.android.ext.android.inject

class HomeLaneFragment : BaseListFragment<HomeLaneSection>(), HomeTabPage {

    companion object {
        private const val TAG = "HomeLaneFragment"
        private const val ARG_TYPE = "type"

        const val TYPE_ANIMATION = HomeLaneRepository.TYPE_ANIMATION
        const val TYPE_CINEMA = HomeLaneRepository.TYPE_CINEMA

        fun newInstance(type: Int): HomeLaneFragment {
            return HomeLaneFragment().apply {
                arguments = bundleOf(ARG_TYPE to type)
            }
        }
    }

    private val mainNavigationViewModel: MainNavigationViewModel by activityViewModels()
    private val repository: HomeLaneRepository by inject()

    private var type: Int = TYPE_ANIMATION
    private var cursor: Long = 0
    private var loadingPage = 1
    private var pendingScrollToTopAfterRefresh = false
    private var timelineRequestVersion = 0
    private var cacheRestoreJob: Job? = null

    private val laneAdapter: HomeLaneAdapter?
        get() = adapter as? HomeLaneAdapter

    override val autoLoad: Boolean = false
    override val enableSwipeRefresh: Boolean = false

    override fun initArguments() {
        type = arguments?.getInt(ARG_TYPE, TYPE_ANIMATION) ?: TYPE_ANIMATION
    }

    override fun createAdapter(): BaseAdapter<HomeLaneSection, *> {
        return HomeLaneAdapter(
            onSeriesClick = { series ->
                if (series.seasonId > 0) {
                    openInHostContainer(
                        SeriesDetailFragment.newInstance(
                            seasonId = series.seasonId
                        )
                    )
                }
            },
            onMoreClick = { seasonType, moreUrl, entryTitle ->
                openInHostContainer(AllSeriesFragment.newInstance(seasonType, moreUrl, entryTitle))
            },
            onTimelineClick = { item ->
                if (item.seasonId > 0 || item.episodeId > 0) {
                    openInHostContainer(
                        SeriesDetailFragment.newInstance(
                            seasonId = item.seasonId,
                            epId = item.episodeId
                        )
                    )
                }
            },
            onTopEdgeUp = ::focusTopTab,
            defaultMoreSeasonType = type,
            onFollowSectionClick = { followType ->
                showMyFollowingDialog(followType)
            }
        )
    }

    override fun createLayoutManager(): LinearLayoutManager {
        return LinearLayoutManager(requireContext())
    }

    override fun initView() {
        super.initView()
        recyclerView?.setHasFixedSize(true)
        adapter?.setShowLoadMore(false)
    }

    override fun initData() {
        showLoading(true)
        restoreCacheThenLoad()
    }

    override fun initObserver() {
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
                            val shouldRefresh = event.host == MainNavigationViewModel.SecondaryTabHost.HOME &&
                                (
                                    (event.position == 2 && type == TYPE_ANIMATION) ||
                                        (event.position == 3 && type == TYPE_CINEMA)
                                    )
                            if (shouldRefresh && !isLoading) {
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

    private fun restoreCacheThenLoad() {
        cacheRestoreJob?.cancel()
        cacheRestoreJob = viewLifecycleOwner.lifecycleScope.launch {
            val hasCache = restoreCachedSections()
            if (!hasCache) {
                showLoading(true)
            }
            loadData(1)
        }
    }

    override fun onRetryClick() {
        refresh()
    }

    override fun loadData(page: Int) {
        if (isLoading) {
            return
        }
        if (!isAdded || view == null) {
            return
        }
        isLoading = true
        loadingPage = page
        if (page == 1) {
            cursor = 0
            hasMore = true
            timelineRequestVersion++
            laneAdapter?.setShowLoadMore(true)
            if (adapter?.contentCount() == 0) {
                showLoading(true)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repository.getHomeLanes(type = type, cursor = cursor, isRefresh = page == 1)
                .onSuccess { page ->
                    if (!isAdded || view == null) {
                        isLoading = false
                        return@onSuccess
                    }
                    showLoading(false)
                    isLoading = false
                    setRefreshing(false)
                    cursor = page.nextCursor
                    hasMore = page.hasMore
                    laneAdapter?.setShowLoadMore(page.hasMore)
                    if (loadingPage == 1 || adapter?.contentCount() == 0) {
                        adapter?.setData(page.sections)
                        if (page.sections.isNotEmpty()) {
                            cacheSections(page.sections)
                        }
                    } else {
                        val added = laneAdapter?.addData(page.sections) ?: false
                        if (page.sections.isNotEmpty() && !added) {
                            hasMore = false
                            laneAdapter?.setShowLoadMore(false)
                        }
                    }
                    if (loadingPage == 1 && pendingScrollToTopAfterRefresh) {
                        recyclerView?.post { scrollToTop() }
                    }
                    if (loadingPage == 1) {
                        pendingScrollToTopAfterRefresh = false
                    }
                    if (loadingPage == 1 && page.sections.isEmpty()) {
                        laneAdapter?.setShowLoadMore(false)
                        showEmpty()
                    } else {
                        showContent()
                        if (loadingPage == 1 &&
                            type == TYPE_ANIMATION &&
                            (adapter?.contentCount() ?: 0) > 0
                        ) {
                            loadTimelineSection()
                        }
                    }
                }
                .onFailure { error ->
                    if (!isAdded || view == null) {
                        isLoading = false
                        return@onFailure
                    }
                    showLoading(false)
                    isLoading = false
                    setRefreshing(false)
                    AppLog.e(
                        TAG,
                        "loadData failure: type=$type, page=$loadingPage, cursor=$cursor, message=${error.message}",
                        error
                    )
                    if (loadingPage == 1) {
                        pendingScrollToTopAfterRefresh = false
                    }
                    if (loadingPage == 1) {
                        restoreCachedSections(
                            onMiss = {
                                laneAdapter?.setShowLoadMore(false)
                                showError(error.message ?: getString(R.string.net_error))
                            }
                        )
                    } else if ((adapter?.contentCount() ?: 0) == 0) {
                        restoreCachedSections(
                            onMiss = {
                                laneAdapter?.setShowLoadMore(false)
                                showError(error.message ?: getString(R.string.net_error))
                            }
                        )
                    }
                }
        }
    }

    private fun loadTimelineSection() {
        val requestVersion = ++timelineRequestVersion
        viewLifecycleOwner.lifecycleScope.launch {
            repository.getAnimationTimelineSection()
                .onSuccess { section ->
                    if (!isAdded || view == null || requestVersion != timelineRequestVersion) {
                        return@onSuccess
                    }
                    if (section != null) {
                        laneAdapter?.insertTimelineSection(section)
                    }
                }
                .onFailure {
                    if (!isAdded || view == null || requestVersion != timelineRequestVersion) {
                        return@onFailure
                    }
                }
        }
    }

    override fun focusPrimaryContent(): Boolean {
        if (!isAdded || view == null) {
            return false
        }
        if (TabContentFocusHelper.requestVisibleFocus(buttonRetry, viewError)) {
            return true
        }
        val recycler = recyclerView ?: return false
        val restored = laneAdapter?.requestFocusedView() == true
        if (restored) {
            return true
        }
        if ((adapter?.contentCount() ?: 0) == 0) {
            return false
        }
        val result = TabContentFocusHelper.requestRecyclerPrimaryFocus(
            recyclerView = recycler,
            itemCount = adapter?.contentCount() ?: 0,
            focusRequester = { holder ->
                when (holder) {
                    is HomeLaneAdapter.ScrollableViewHolder -> holder.requestPrimaryFocus()
                    is HomeLaneAdapter.TimelineViewHolder -> holder.requestPrimaryFocus()
                    else -> false
                }
            }
        )
        return result.resolved
    }

    override fun focusPrimaryContent(anchorView: View?, preferSpatialEntry: Boolean): Boolean {
        if (preferSpatialEntry) {
            val recycler = recyclerView ?: return false
            val handled = SpatialFocusNavigator.requestBestDescendant(
                anchorView = anchorView,
                root = recycler,
                direction = View.FOCUS_RIGHT,
                fallback = null
            )
            if (handled) {
                return true
            }
        }
        return focusPrimaryContent()
    }

    override fun refresh() {
        pendingScrollToTopAfterRefresh = true
        super.refresh()
    }

    override fun onTabSelected() {
        if (!isAdded || view == null || isLoading) {
            return
        }
        if ((adapter?.contentCount() ?: 0) == 0) {
            loadData(1)
        }
    }

    private fun cacheSections(sections: List<HomeLaneSection>) {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                val cacheSections = sections.sectionsForCache()
                HomeCacheStore.writeSections(cacheKey(), cacheSections)
            }.onFailure { throwable ->
                AppLog.e(TAG, "cacheSections failure: type=$type, message=${throwable.message}", throwable)
            }
        }
    }

    private suspend fun restoreCachedSections(onMiss: (() -> Unit)? = null): Boolean {
        val cachedSections = runCatching {
            HomeCacheStore.readSections(cacheKey())
        }.getOrElse { throwable ->
            AppLog.e(TAG, "restoreCachedSections failure: type=$type, message=${throwable.message}", throwable)
            emptyList()
        }
        if (cachedSections.isEmpty()) {
            onMiss?.invoke()
            return false
        }
        adapter?.setData(cachedSections)
        adapter?.setShowLoadMore(false)
        showContent()
        showLoading(false)
        return true
    }

    override fun checkLoadMore() {
        if (isLoading || !hasMore) return
        val lm = layoutManager ?: return
        val totalItemCount = lm.itemCount
        val lastVisiblePosition = lm.findLastVisibleItemPosition()
        if (lastVisiblePosition >= totalItemCount - 2) {
            currentPage++
            loadData(currentPage)
        }
    }

    private fun cacheKey(): String {
        return "laneCacheList$type"
    }

    private fun List<HomeLaneSection>.sectionsForCache(): List<HomeLaneSection> {
        return if (type == TYPE_ANIMATION) {
            filter { it.timelineDays.isEmpty() }
        } else {
            this
        }
    }

    private fun focusTopTab(): Boolean {
        return (parentFragment as? HomeFragment)?.focusCurrentTab() == true
    }

    private fun showMyFollowingDialog(followType: Int) {
        if (!isAdded || view == null) return
        val dialog = MyFollowingDialog(
            context = requireContext(),
            type = followType,
            onSeriesClick = { series ->
                if (series.seasonId > 0) {
                    openInHostContainer(
                        SeriesDetailFragment.newInstance(
                            seasonId = series.seasonId
                        )
                    )
                }
            }
        )
        dialog.show()
    }

}

package com.tutu.myblbl.ui.fragment.main.home

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
import com.tutu.myblbl.ui.base.BaseAdapter
import com.tutu.myblbl.ui.base.BaseListFragment
import com.tutu.myblbl.ui.base.RecyclerViewFocusRestoreHelper
import com.tutu.myblbl.ui.fragment.main.MainNavigationViewModel
import com.tutu.myblbl.ui.fragment.series.AllSeriesFragment
import com.tutu.myblbl.ui.fragment.series.SeriesDetailFragment
import com.tutu.myblbl.utils.AppLog
import com.tutu.myblbl.utils.SpatialFocusNavigator
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import org.koin.android.ext.android.inject

class HomeLaneFragment : BaseListFragment<HomeLaneSection>(), HomeTabPage {

    companion object {
        private const val ENTRY_TAG = "MainEntryFocus"
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
            defaultMoreSeasonType = type
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
                        is MainNavigationViewModel.Event.MainTabReselected ->
                            if (event.index == 0 && !isLoading) {
                                refresh()
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

    private fun restoreCacheThenLoad() {
        cacheRestoreJob?.cancel()
        cacheRestoreJob = viewLifecycleOwner.lifecycleScope.launch {
            val hasCache = restoreCachedSections()
            AppLog.d(TAG, "initCache: type=$type, hasCache=$hasCache")
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
            AppLog.d(TAG, "loadData ignored because loading: type=$type, page=$page, cursor=$cursor")
            return
        }
        if (!isAdded || view == null) {
            AppLog.d(TAG, "loadData ignored because fragment not attached: type=$type")
            return
        }
        isLoading = true
        loadingPage = page
        AppLog.d(TAG, "loadData start: type=$type, page=$page, cursor=$cursor, hasMore=$hasMore, contentCount=${adapter?.contentCount()}")
        if (page == 1) {
            cursor = 0
            hasMore = true
            timelineRequestVersion++
            laneAdapter?.setShowLoadMore(true)
            if (adapter?.contentCount() == 0) {
                showLoading(true)
            }
        }
        AppLog.d(TAG, "loadData start: type=$type, page=$page, cursor=$cursor")

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
                    AppLog.d(
                        TAG,
                        "loadData success: type=$type, page=$loadingPage, sections=${page.sections.size}, nextCursor=${page.nextCursor}, hasMore=${page.hasMore}"
                    )
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
                            AppLog.d(TAG, "loadData all sections duplicated, stop loading more: type=$type")
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
        if (viewError?.visibility == View.VISIBLE && buttonRetry?.isShown == true) {
            val handled = buttonRetry?.requestFocus() == true
            AppLog.d(ENTRY_TAG, "HomeLaneFragment.focusPrimaryContent retry: handled=$handled")
            return handled
        }
        val recycler = recyclerView ?: return false
        val restored = laneAdapter?.requestFocusedView() == true
        if (restored) {
            AppLog.d(ENTRY_TAG, "HomeLaneFragment.focusPrimaryContent restoreFocusedView: handled=true")
            return true
        }
        if ((adapter?.contentCount() ?: 0) == 0) {
            AppLog.d(ENTRY_TAG, "HomeLaneFragment.focusPrimaryContent failed: itemCount=0")
            return false
        }
        val result = RecyclerViewFocusRestoreHelper.requestFocusAtPosition(
            recyclerView = recycler,
            position = 0,
            focusRequester = { holder ->
                when (holder) {
                    is HomeLaneAdapter.ScrollableViewHolder -> holder.requestPrimaryFocus()
                    is HomeLaneAdapter.TimelineViewHolder -> holder.requestPrimaryFocus()
                    else -> false
                }
            }
        )
        AppLog.d(
            ENTRY_TAG,
            "HomeLaneFragment.focusPrimaryContent pos0: handled=${result.handled} deferred=${result.deferred}"
        )
        return result.handled || result.deferred
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
            AppLog.d(
                ENTRY_TAG,
                "HomeLaneFragment.focusPrimaryContent spatialEntry: handled=$handled anchor=${anchorView?.javaClass?.simpleName ?: "null"}"
            )
            if (handled) {
                return true
            }
        }
        return focusPrimaryContent()
    }

    override fun refresh() {
        AppLog.d(TAG, "refresh called: type=$type")
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
                AppLog.d(TAG, "cacheSections success: type=$type, count=${cacheSections.size}")
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
        AppLog.d(TAG, "restoreCachedSections success: type=$type, count=${cachedSections.size}")
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

}

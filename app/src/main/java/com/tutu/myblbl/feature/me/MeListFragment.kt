@file:Suppress("SpellCheckingInspection")

package com.tutu.myblbl.feature.me

import android.os.Bundle
import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.reflect.TypeToken
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.FragmentMeTabListBinding
import com.tutu.myblbl.event.AppEventHub
import com.tutu.myblbl.model.video.HistoryVideoModel
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.ui.adapter.HistoryVideoAdapter
import com.tutu.myblbl.ui.adapter.VideoAdapter
import com.tutu.myblbl.ui.base.BaseFragment
import com.tutu.myblbl.ui.base.RecyclerViewFocusRestoreHelper
import com.tutu.myblbl.utils.FileCacheManager
import com.tutu.myblbl.ui.view.WrapContentGridLayoutManager
import com.tutu.myblbl.utils.AppLog
import com.tutu.myblbl.utils.ContentFilter
import com.tutu.myblbl.utils.SpatialFocusNavigator
import com.tutu.myblbl.utils.SwipeRefreshHelper
import com.tutu.myblbl.utils.VideoRouteNavigator
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class MeListFragment : BaseFragment<FragmentMeTabListBinding>(), MeTabPage {
    companion object {
        private const val TAG = "MainEntryFocus"
        const val TYPE_HISTORY = "history"
        const val TYPE_LATER = "later"
        private const val CACHE_TTL_MS = 10 * 60 * 1000L
        private const val HISTORY_CACHE_KEY = "historyCacheList"
        private const val LATER_CACHE_KEY = "watchLaterCacheList"

        private const val ARG_TYPE = "type"

        fun newInstance(type: String): MeListFragment {
            val fragment = MeListFragment()
            val args = Bundle()
            args.putString(ARG_TYPE, type)
            fragment.arguments = args
            return fragment
        }
    }

    private val appEventHub: AppEventHub by inject()
    private val viewModel: MeListViewModel by viewModel()
    private var videoAdapter: VideoAdapter? = null
    private var historyAdapter: HistoryVideoAdapter? = null
    private var swipeRefreshLayout: androidx.swiperefreshlayout.widget.SwipeRefreshLayout? = null
    private var type: String = TYPE_HISTORY
    private var currentPage = 1
    private val pageSize = 20
    private var lastFocusedHistoryPosition = RecyclerView.NO_POSITION
    private var lastFocusedHistoryKey: String? = null
    private var pendingRestoreFocus = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        type = arguments?.getString(ARG_TYPE) ?: TYPE_HISTORY
    }

    override fun getViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentMeTabListBinding {
        return FragmentMeTabListBinding.inflate(inflater, container, false)
    }

    override fun initView() {
        if (type == TYPE_HISTORY) {
            historyAdapter = HistoryVideoAdapter(
                onItemClick = ::onHistoryVideoClick,
                onTopEdgeUp = {
                    // 当在列表顶部按向上键时，先尝试触发刷新，如果已经在最顶部再切换到顶部tab
                    if (binding.recyclerView.computeVerticalScrollOffset() == 0 && !viewModel.loading.value) {
                        swipeRefreshLayout?.isRefreshing = true
                        refresh()
                        true
                    } else {
                        focusTopTab()
                    }
                },
                onItemFocused = { position -> lastFocusedHistoryPosition = position }
            )
        } else {
            videoAdapter = VideoAdapter(
                onItemClick = ::onVideoClick,
                onTopEdgeUp = {
                    // 当在列表顶部按向上键时，先尝试触发刷新，如果已经在最顶部再切换到顶部tab
                    if (binding.recyclerView.computeVerticalScrollOffset() == 0 && !viewModel.loading.value) {
                        swipeRefreshLayout?.isRefreshing = true
                        refresh()
                        true
                    } else {
                        focusTopTab()
                    }
                }
            ).apply {
                setShowLoadMore(false)
            }
        }

        val layoutManager = WrapContentGridLayoutManager(requireContext(), 4)
        binding.recyclerView.layoutManager = layoutManager
        binding.recyclerView.adapter = historyAdapter ?: videoAdapter
        binding.recyclerView.setHasFixedSize(true)
        binding.emptyContainer.visibility = View.GONE
        binding.progressBar.visibility = View.GONE
        binding.btnRetry.setOnClickListener {
            currentPage = 1
            loadData()
        }
        binding.btnRetry.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                keyCode == KeyEvent.KEYCODE_DPAD_UP
            ) {
                focusTopTab()
            } else {
                false
            }
        }
        setupLoadMore()
        swipeRefreshLayout = SwipeRefreshHelper.wrapRecyclerView(binding.recyclerView) {
            refresh()
        }
    }
    
    private fun setupLoadMore() {
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val layoutManager = binding.recyclerView.layoutManager as? WrapContentGridLayoutManager ?: return
                val totalItemCount = layoutManager.itemCount
                val lastVisibleItem = layoutManager.findLastVisibleItemPosition()
                
                if (!viewModel.loading.value && viewModel.hasMore.value && lastVisibleItem >= totalItemCount - 5) {
                    currentPage++
                    loadData()
                }
            }
        })
    }

    override fun initData() {
        restoreCachedContent()
        loadData()
    }

    override fun onResume() {
        super.onResume()
        if (pendingRestoreFocus) {
            pendingRestoreFocus = false
            restoreContentFocus()
        }
    }

    override fun initObserver() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collectLatest { state ->
                    if (type == TYPE_HISTORY) {
                        bindHistoryData(state.historyVideos)
                    } else {
                        bindLaterData(state.laterVideos)
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.loading.collectLatest { loading ->
                    val hasData = hasContentItems()
                    binding.progressBar.visibility = if (loading && !hasData) View.VISIBLE else View.GONE
                    if (loading && !hasData) {
                        binding.emptyContainer.visibility = View.GONE
                    }
                    updateContentState(!hasData)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.error.collectLatest { error ->
                    error?.let {
                        if (!hasContentItems()) {
                            showState(it, showRetry = shouldShowRetry(it))
                        }
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                appEventHub.events.collectLatest { event ->
                    if (isHidden || !isVisible) {
                        return@collectLatest
                    }
                    when (event) {
                        is AppEventHub.Event.PlaybackProgressUpdated -> {
                            if (type == TYPE_HISTORY) {
                                historyAdapter?.updateProgressFromPlayer(
                                    event.aid,
                                    event.cid,
                                    event.progressMs
                                )
                            }
                        }

                        AppEventHub.Event.UserSessionChanged -> {
                            if (type == TYPE_HISTORY || type == TYPE_LATER) {
                                refresh()
                            }
                        }

                        else -> Unit
                    }
                }
            }
        }
    }

    private fun loadData() {
        when (type) {
            TYPE_HISTORY -> viewModel.loadHistory(currentPage, pageSize)
            TYPE_LATER -> viewModel.loadLaterWatch()
        }
    }

    private fun onVideoClick(video: VideoModel) {
        pendingRestoreFocus = true
        VideoRouteNavigator.openVideo(
            context = requireContext(),
            video = video,
            playQueue = com.tutu.myblbl.ui.activity.PlayerActivity.buildPlayQueue(
                videoAdapter?.getItemsSnapshot().orEmpty(),
                video
            )
        )
    }

    private fun onHistoryVideoClick(video: HistoryVideoModel) {
        val mapped = video.toVideoModel()
        if (mapped.aid != 0L || mapped.bvid.isNotEmpty()) {
            lastFocusedHistoryPosition = historyAdapter?.getFocusedPosition() ?: RecyclerView.NO_POSITION
            lastFocusedHistoryKey = historyItemKey(video)
            pendingRestoreFocus = true
            VideoRouteNavigator.openHistory(
                context = requireContext(),
                historyVideo = video,
                playQueue = com.tutu.myblbl.ui.activity.PlayerActivity.buildPlayQueue(
                    historyAdapter?.getItemsSnapshot().orEmpty().map { it.toVideoModel() },
                    mapped
                )
            )
        }
    }

    private fun bindHistoryData(videos: List<HistoryVideoModel>) {
        swipeRefreshLayout?.isRefreshing = false
        val adapter = historyAdapter ?: return
        val filtered = videos.filter { !ContentFilter.isVideoBlocked(requireContext(), it.tagName, it.title, authorName = it.authorName) }
        adapter.setData(filtered)
        cacheHistoryVideos(videos)
        updateContentState(filtered.isEmpty())
        if (currentPage == 1 && !pendingRestoreFocus && filtered.isNotEmpty()) {
            binding.recyclerView.scrollToPosition(0)
        }
        if (pendingRestoreFocus && filtered.isNotEmpty()) {
            pendingRestoreFocus = false
            restoreContentFocus()
        }
    }

    private fun bindLaterData(videos: List<VideoModel>) {
        swipeRefreshLayout?.isRefreshing = false
        val adapter = videoAdapter ?: return
        val filtered = ContentFilter.filterVideos(requireContext(), videos)
        adapter.setData(filtered)
        cacheLaterVideos(videos)
        updateContentState(filtered.isEmpty())
    }

    private fun updateContentState(isEmpty: Boolean) {
        if (isEmpty && !viewModel.loading.value) {
            val errorMessage = viewModel.error.value
            if (!errorMessage.isNullOrBlank()) {
                showState(errorMessage, showRetry = shouldShowRetry(errorMessage))
            } else {
                showState(getEmptyMessage(), showRetry = false)
            }
        } else {
            showContent()
        }
    }

    override fun showContent() {
        binding.recyclerView.visibility = View.VISIBLE
        binding.emptyContainer.visibility = View.GONE
    }

    private fun showState(message: String, showRetry: Boolean) {
        binding.recyclerView.visibility = View.GONE
        binding.emptyContainer.visibility = View.VISIBLE
        binding.tvEmpty.text = message
        binding.btnRetry.visibility = if (showRetry) View.VISIBLE else View.GONE
        if (showRetry) {
            binding.btnRetry.post { binding.btnRetry.requestFocus() }
        }
    }

    override fun scrollToTop() {
        binding.recyclerView.smoothScrollToPosition(0)
    }

    override fun refresh() {
        currentPage = 1
        loadData()
    }

    override fun onTabSelected() {
        if (!isAdded || view == null || viewModel.loading.value) {
            return
        }
        if (!hasContentItems() || viewModel.shouldRefresh(CACHE_TTL_MS)) {
            currentPage = 1
            loadData()
        }
    }

    override fun onTabReselected() {
        if (!isAdded || view == null || viewModel.loading.value) {
            return
        }
        refresh()
    }

    override fun onHostEvent(event: MeTabPage.HostEvent): Boolean {
        when (event) {
            MeTabPage.HostEvent.SELECT_TAB4 -> onTabSelected()
            MeTabPage.HostEvent.CLICK_TAB4 -> refresh()
            MeTabPage.HostEvent.BACK_PRESSED -> scrollToTop()
            MeTabPage.HostEvent.KEY_MENU_PRESS -> refresh()
        }
        return true
    }

    override fun focusPrimaryContent(): Boolean {
        if (!isAdded || view == null) {
            return false
        }
        if (binding.emptyContainer.isVisible && binding.btnRetry.isShown) {
            val handled = binding.btnRetry.requestFocus()
            AppLog.d(TAG, "MeListFragment.focusPrimaryContent retry: type=$type handled=$handled")
            return handled
        }
        val lm = binding.recyclerView.layoutManager as? WrapContentGridLayoutManager ?: return false
        val firstVisible = lm.findFirstVisibleItemPosition()
        if (firstVisible != RecyclerView.NO_POSITION) {
            binding.recyclerView.findViewHolderForAdapterPosition(firstVisible)?.itemView?.let {
                val handled = it.requestFocus()
                AppLog.d(TAG, "MeListFragment.focusPrimaryContent firstVisible=$firstVisible type=$type handled=$handled")
                return handled
            }
        }
        val itemCount = binding.recyclerView.adapter?.itemCount ?: 0
        if (itemCount == 0) {
            AppLog.d(TAG, "MeListFragment.focusPrimaryContent failed: type=$type itemCount=0")
            return false
        }
        val result = RecyclerViewFocusRestoreHelper.requestFocusAtPosition(
            recyclerView = binding.recyclerView,
            position = 0
        )
        AppLog.d(
            TAG,
            "MeListFragment.focusPrimaryContent deferred: type=$type handled=${result.handled} deferred=${result.deferred}"
        )
        return true
    }

    override fun focusPrimaryContent(anchorView: View?, preferSpatialEntry: Boolean): Boolean {
        if (preferSpatialEntry) {
            val handled = SpatialFocusNavigator.requestBestDescendant(
                anchorView = anchorView,
                root = binding.recyclerView,
                direction = View.FOCUS_RIGHT,
                fallback = null
            )
            AppLog.d(TAG, "MeListFragment.focusPrimaryContent spatialEntry: type=$type handled=$handled")
            if (handled) {
                return true
            }
        }
        return focusPrimaryContent()
    }

    private fun focusTopTab(): Boolean {
        return (parentFragment as? MeFragment)?.focusCurrentTab() == true
    }

    private fun restoreContentFocus() {
        if (!isAdded || view == null || binding.recyclerView.visibility != View.VISIBLE) {
            return
        }
        binding.recyclerView.post {
            if (!isAdded || binding.recyclerView.visibility != View.VISIBLE) {
                return@post
            }
            if (type == TYPE_HISTORY) {
                val adapter = historyAdapter ?: return@post
                val targetPosition = lastFocusedHistoryKey
                    ?.let(adapter::findPositionByKey)
                    ?.takeIf { it != RecyclerView.NO_POSITION }
                    ?: lastFocusedHistoryPosition
                        .takeIf { it != RecyclerView.NO_POSITION }
                        ?.coerceIn(0, adapter.itemCount - 1)
                    ?: 0
                val result = RecyclerViewFocusRestoreHelper.requestFocusAtPosition(
                    recyclerView = binding.recyclerView,
                    position = targetPosition
                )
                AppLog.d(
                    TAG,
                    "restoreContentFocus history targetPosition=$targetPosition handled=${result.handled} deferred=${result.deferred}"
                )
            } else {
                val handled = videoAdapter?.focusedView?.requestFocus() == true
                if (!handled) {
                    focusPrimaryContent()
                }
            }
        }
    }

    private fun getEmptyMessage(): String {
        return when (type) {
            TYPE_HISTORY -> getString(R.string.history_empty)
            TYPE_LATER -> getString(R.string.later_watch_empty)
            else -> getString(R.string.empty)
        }
    }

    private fun shouldShowRetry(message: String): Boolean {
        return message != getString(R.string.need_sign_in)
    }

    private fun hasContentItems(): Boolean {
        return when (type) {
            TYPE_HISTORY -> (historyAdapter?.itemCount ?: 0) > 0
            TYPE_LATER -> (videoAdapter?.contentCount() ?: 0) > 0
            else -> (binding.recyclerView.adapter?.itemCount ?: 0) > 0
        }
    }

    private fun requestItemFocus(position: Int, retries: Int = 6) {
        val result = RecyclerViewFocusRestoreHelper.requestFocusAtPosition(
            recyclerView = binding.recyclerView,
            position = position
        )
        if (result.handled || retries <= 0) {
            return
        }
        binding.recyclerView.post { requestItemFocus(position, retries - 1) }
    }

    private fun historyItemKey(item: HistoryVideoModel): String {
        return when {
            item.bvid.isNotBlank() -> "bvid:${item.bvid}"
            (item.history?.oid ?: 0L) > 0L -> "aid:${item.history?.oid}"
            else -> "title:${item.title}|cover:${item.cover}"
        }
    }

    private fun restoreCachedContent() {
        when (type) {
            TYPE_HISTORY -> {
                val cachedVideos = runCatching {
                    val cacheType = object : TypeToken<List<HistoryVideoModel>>() {}.type
                    FileCacheManager.get<List<HistoryVideoModel>>(HISTORY_CACHE_KEY, cacheType).orEmpty()
                }.getOrElse { emptyList() }
                if (cachedVideos.isNotEmpty()) {
                    historyAdapter?.setData(cachedVideos.filter { !ContentFilter.isVideoBlocked(requireContext(), it.tagName, it.title, authorName = it.authorName) })
                    showContent()
                }
            }

            TYPE_LATER -> {
                val cachedVideos = runCatching {
                    val cacheType = object : TypeToken<List<VideoModel>>() {}.type
                    FileCacheManager.get<List<VideoModel>>(LATER_CACHE_KEY, cacheType).orEmpty()
                }.getOrElse { emptyList() }
                if (cachedVideos.isNotEmpty()) {
                    videoAdapter?.setData(ContentFilter.filterVideos(requireContext(), cachedVideos))
                    showContent()
                }
            }
        }
    }

    private fun cacheHistoryVideos(videos: List<HistoryVideoModel>) {
        if (videos.isEmpty()) {
            return
        }
        runCatching {
            FileCacheManager.put(HISTORY_CACHE_KEY, videos)
        }
    }

    private fun cacheLaterVideos(videos: List<VideoModel>) {
        if (videos.isEmpty()) {
            return
        }
        runCatching {
            FileCacheManager.put(LATER_CACHE_KEY, videos)
        }
    }

}

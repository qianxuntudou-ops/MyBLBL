package com.tutu.myblbl.feature.live

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.FragmentLiveBaseListBinding
import com.tutu.myblbl.model.live.LiveListWrapper
import com.tutu.myblbl.model.live.LiveRecommendSection
import com.tutu.myblbl.ui.activity.LivePlayerActivity
import com.tutu.myblbl.core.ui.base.BaseFragment
import com.tutu.myblbl.core.ui.image.ImageLoader
import com.tutu.myblbl.ui.fragment.main.MainNavigationViewModel
import com.tutu.myblbl.core.common.content.ContentFilter
import com.tutu.myblbl.core.ui.focus.SpatialFocusNavigator
import com.tutu.myblbl.core.ui.focus.TabContentFocusHelper
import com.tutu.myblbl.core.ui.refresh.SwipeRefreshHelper
import com.tutu.myblbl.core.common.ext.toast
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

class LiveRecommendFragment : BaseFragment<FragmentLiveBaseListBinding>(), LiveTabPage {
    companion object {
        private const val CACHE_TTL_MS = 10 * 60 * 1000L
        private const val PREFETCH_SECTION_COUNT = 2
        private const val PREFETCH_PER_SECTION = 8

        fun newInstance(): LiveRecommendFragment = LiveRecommendFragment()
    }

    private val viewModel: LiveRecommendViewModel by viewModel()
    private val mainNavigationViewModel: MainNavigationViewModel by activityViewModels()
    private lateinit var adapter: LiveRecommendAdapter
    private var swipeRefreshLayout: androidx.swiperefreshlayout.widget.SwipeRefreshLayout? = null

    override fun getViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentLiveBaseListBinding {
        return FragmentLiveBaseListBinding.inflate(inflater, container ?: android.widget.FrameLayout(inflater.context))
    }

    override fun initView() {
        adapter = LiveRecommendAdapter(
            onRoomClick = ::onRoomClick,
            onTopEdgeUp = ::focusTopTab,
            onLeftEdge = ::focusLeftNav
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        binding.recyclerView.itemAnimator = null
        swipeRefreshLayout = SwipeRefreshHelper.wrapRecyclerView(binding.recyclerView) {
            onExplicitRefresh()
        }
        binding.recyclerView.setHasFixedSize(true)
    }

    override fun initData() {
        viewModel.loadData()
    }

    override fun initObserver() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.recommendData.collectLatest { data ->
                    swipeRefreshLayout?.isRefreshing = false
                    val sections = buildSections(data)
                    prefetchFirstScreenCovers(sections)
                    adapter.setData(sections)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.error.collectLatest { error ->
                    if (!error.isNullOrBlank()) {
                        requireContext().toast(error)
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                mainNavigationViewModel.events.collectLatest { event ->
                    if (isHidden || !isVisible) {
                        return@collectLatest
                    }
                    if (event is MainNavigationViewModel.Event.SecondaryTabReselected &&
                        event.host == MainNavigationViewModel.SecondaryTabHost.LIVE &&
                        event.position == 0 &&
                        !viewModel.loading.value
                    ) {
                        onExplicitRefresh()
                    }
                }
            }
        }
    }

    override fun scrollToTop() {
        binding.recyclerView.smoothScrollToPosition(0)
    }

    override fun focusPrimaryContent(): Boolean {
        if (!isAdded || view == null || adapter.itemCount == 0) {
            return false
        }
        val result = TabContentFocusHelper.requestRecyclerPrimaryFocus(
            recyclerView = binding.recyclerView,
            itemCount = adapter.itemCount,
            focusRequester = { holder ->
                (holder as? LiveRecommendAdapter.ViewHolder)?.requestPrimaryFocus() == true
            }
        )
        return result.resolved
    }

    override fun focusPrimaryContent(anchorView: View?, preferSpatialEntry: Boolean): Boolean {
        if (preferSpatialEntry) {
            val handled = SpatialFocusNavigator.requestBestDescendant(
                anchorView = anchorView,
                root = binding.recyclerView,
                direction = View.FOCUS_RIGHT,
                fallback = null
            )
            if (handled) {
                return true
            }
        }
        return focusPrimaryContent()
    }

    override fun onReselected() {
        scrollToTop()
    }

    override fun onExplicitRefresh() {
        viewModel.loadData(forceRefresh = true)
    }

    override fun onTabSelected() {
        if (!isAdded || view == null || viewModel.loading.value) {
            return
        }
        if (adapter.itemCount == 0 || viewModel.shouldRefresh(CACHE_TTL_MS)) {
            viewModel.loadData()
        }
    }

    private fun onRoomClick(room: com.tutu.myblbl.model.live.LiveRoomItem) {
        LivePlayerActivity.start(requireContext(), room.roomId)
    }

    private fun focusTopTab(): Boolean {
        return (parentFragment as? LiveFragment)?.focusCurrentTab() == true
    }

    private fun focusLeftNav(): Boolean {
        return (activity as? com.tutu.myblbl.ui.activity.MainActivity)?.focusLeftFunctionArea() == true
    }

    /**
     * 列表是「纵向多个 section，每个 section 内部 4 列网格」的结构，第一屏通常只能完整显示
     * 第一个 section（4 列），其余 section 上半部分露出 1~2 行。提前把前 [PREFETCH_SECTION_COUNT]
     * 个 section 的前 [PREFETCH_PER_SECTION] 张房间封面塞进 Coil 缓存，
     * 避免列表 onBind 才发起网络请求导致首屏图片延迟。
     */
    private fun prefetchFirstScreenCovers(sections: List<LiveRecommendSection>) {
        if (sections.isEmpty() || !isAdded) return
        val urls = sections.asSequence()
            .take(PREFETCH_SECTION_COUNT)
            .flatMap { it.rooms.asSequence().take(PREFETCH_PER_SECTION) }
            .map { it.cover }
            .toList()
        if (urls.isEmpty()) return
        ImageLoader.prefetchVideoCovers(requireContext(), urls)
    }

    private fun buildSections(data: LiveListWrapper?): List<LiveRecommendSection> {
        if (data == null) {
            return emptyList()
        }

        val sections = mutableListOf<LiveRecommendSection>()
        val hotRooms = ContentFilter.filterLiveRooms(requireContext(), data.recommendRoomList.orEmpty())
        if (hotRooms.isNotEmpty()) {
            sections += LiveRecommendSection(
                title = getString(R.string.hot_live),
                rooms = hotRooms
            )
        }
        sections += data.roomList.orEmpty()
            .mapNotNull { wrapper ->
                val rooms = ContentFilter.filterLiveRooms(requireContext(), wrapper.list.orEmpty())
                if (rooms.isEmpty()) {
                    null
                } else {
                    LiveRecommendSection(
                        title = wrapper.moduleInfo?.title.orEmpty(),
                        rooms = rooms
                    )
                }
            }
        return sections
    }

}

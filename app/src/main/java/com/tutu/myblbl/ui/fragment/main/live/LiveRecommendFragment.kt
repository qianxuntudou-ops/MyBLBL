package com.tutu.myblbl.ui.fragment.main.live

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
import com.tutu.myblbl.ui.base.BaseFragment
import com.tutu.myblbl.ui.fragment.main.MainNavigationViewModel
import com.tutu.myblbl.utils.AppLog
import com.tutu.myblbl.utils.ContentFilter
import com.tutu.myblbl.utils.SpatialFocusNavigator
import com.tutu.myblbl.utils.SwipeRefreshHelper
import com.tutu.myblbl.utils.toast
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

class LiveRecommendFragment : BaseFragment<FragmentLiveBaseListBinding>(), LiveTabPage {
    companion object {
        private const val TAG = "MainEntryFocus"
        private const val CACHE_TTL_MS = 10 * 60 * 1000L

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
        swipeRefreshLayout = SwipeRefreshHelper.wrapRecyclerView(binding.recyclerView) {
            onExplicitRefresh()
        }
        binding.recyclerView.setHasFixedSize(true)
    }

    override fun initData() {
        AppLog.d(TAG, "[LiveRecommend] initData")
        viewModel.loadData()
    }

    override fun initObserver() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.recommendData.collectLatest { data ->
                    AppLog.d(TAG, "[LiveRecommend] recommendData collected: data=${data != null}")
                    swipeRefreshLayout?.isRefreshing = false
                    val sections = buildSections(data)
                    AppLog.d(TAG, "[LiveRecommend] sections.size=${sections.size}, adapter.itemCount before=${adapter.itemCount}")
                    adapter.setData(sections)
                    AppLog.d(TAG, "[LiveRecommend] adapter.itemCount after=${adapter.itemCount}")
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
            AppLog.d(TAG, "LiveRecommendFragment.focusPrimaryContent failed: itemCount=${adapter.itemCount}")
            return false
        }
        val lm = binding.recyclerView.layoutManager as? LinearLayoutManager ?: return false
        val firstVisible = lm.findFirstVisibleItemPosition()
        if (firstVisible != RecyclerView.NO_POSITION) {
            (binding.recyclerView.findViewHolderForAdapterPosition(firstVisible) as? LiveRecommendAdapter.ViewHolder)?.let {
                val handled = it.requestPrimaryFocus()
                AppLog.d(TAG, "LiveRecommendFragment.focusPrimaryContent firstVisible=$firstVisible handled=$handled")
                return handled
            }
        }
        binding.recyclerView.scrollToPosition(0)
        binding.recyclerView.post {
            (binding.recyclerView.findViewHolderForAdapterPosition(0) as? LiveRecommendAdapter.ViewHolder)
                ?.requestPrimaryFocus()
        }
        AppLog.d(TAG, "LiveRecommendFragment.focusPrimaryContent deferred to position 0")
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
            AppLog.d(TAG, "LiveRecommendFragment.focusPrimaryContent spatialEntry: handled=$handled")
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

    private fun buildSections(data: LiveListWrapper?): List<LiveRecommendSection> {
        AppLog.d(TAG, "buildSections: data=${data != null}, recommendRoomList.size=${data?.recommendRoomList?.size ?: "null"}, roomList.size=${data?.roomList?.size ?: "null"}")
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

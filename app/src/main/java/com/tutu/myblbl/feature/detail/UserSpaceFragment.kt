package com.tutu.myblbl.feature.detail

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.FragmentUserSpaceBinding
import com.tutu.myblbl.event.AppEventHub
import com.tutu.myblbl.model.user.CheckRelationModel
import com.tutu.myblbl.model.user.UserSpaceInfo
import com.tutu.myblbl.network.session.NetworkSessionGateway
import com.tutu.myblbl.repository.UserRepository
import com.tutu.myblbl.ui.adapter.UserSpaceHeaderAdapter
import com.tutu.myblbl.ui.adapter.VideoAdapter
import com.tutu.myblbl.core.ui.base.BaseAdapter
import com.tutu.myblbl.core.ui.base.BaseFragment
import com.tutu.myblbl.feature.user.FollowUserListFragment
import com.tutu.myblbl.core.ui.layout.WrapContentGridLayoutManager
import com.tutu.myblbl.core.ui.decoration.GridSpacingItemDecoration
import com.tutu.myblbl.core.common.content.ContentFilter
import com.tutu.myblbl.core.ui.focus.tv.GridTvFocusStrategy
import com.tutu.myblbl.core.ui.focus.tv.OffsetTvFocusableAdapter
import com.tutu.myblbl.core.ui.focus.tv.TvDataChangeReason
import com.tutu.myblbl.core.ui.focus.tv.TvListFocusController
import com.tutu.myblbl.core.navigation.VideoRouteNavigator
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class UserSpaceFragment : BaseFragment<FragmentUserSpaceBinding>() {

    private enum class FocusArea {
        BACK,
        FOLLOW,
        FOLLOWING,
        FOLLOWER,
        CONTENT
    }

    companion object {
        private const val ARG_MID = "mid"
        private const val SPAN_COUNT = 4

        fun newInstance(mid: Long): UserSpaceFragment {
            return UserSpaceFragment().apply {
                arguments = bundleOf(ARG_MID to mid)
            }
        }
    }

    private var mid: Long = 0
    private var userSpaceInfo: UserSpaceInfo? = null

    private val appEventHub: AppEventHub by inject()
    private val sessionGateway: NetworkSessionGateway by inject()
    private val userRepository: UserRepository by inject()
    private lateinit var headerAdapter: UserSpaceHeaderAdapter
    private lateinit var videoAdapter: VideoAdapter
    private lateinit var concatAdapter: ConcatAdapter

    private var currentPage = 1
    private var isLoading = false
    private var hasMore = true
    private var relationAttribute = 0
    private var lastFocusedArea = FocusArea.BACK
    private var lastFocusedVideoPosition = RecyclerView.NO_POSITION
    private var hasRequestedInitialFocus = false
    private var tvFocusController: TvListFocusController? = null

    override fun onResume() {
        super.onResume()
        restoreFocus()
    }

    override fun getViewBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentUserSpaceBinding {
        return FragmentUserSpaceBinding.inflate(inflater, container, false)
    }

    override fun initArguments() {
        mid = arguments?.getLong(ARG_MID) ?: 0
    }

    override fun initView() {
        headerAdapter = UserSpaceHeaderAdapter(
            onFollowClick = ::doFollow,
            onFollowingClick = { openFollowList(FollowUserListFragment.TYPE_FOLLOWING) },
            onFollowerClick = { openFollowList(FollowUserListFragment.TYPE_FOLLOWER) },
            onHeaderFocusChanged = { target ->
                lastFocusedArea = when (target) {
                    UserSpaceHeaderAdapter.FocusTarget.FOLLOW -> FocusArea.FOLLOW
                    UserSpaceHeaderAdapter.FocusTarget.FOLLOWING -> FocusArea.FOLLOWING
                    UserSpaceHeaderAdapter.FocusTarget.FOLLOWER -> FocusArea.FOLLOWER
                }
            },
            onMoveToContent = {
                requestVideoFocus(lastFocusedVideoPosition)
            }
        )
        videoAdapter = VideoAdapter(
            onTopEdgeUp = ::focusHeaderFromContent,
            onItemFocused = { position ->
                lastFocusedArea = FocusArea.CONTENT
                lastFocusedVideoPosition = position
            },
            onItemFocusedWithView = { view, position ->
                tvFocusController?.onItemFocused(view, headerAdapter.itemCount + position)
            },
            onItemDpad = { view, keyCode, event ->
                tvFocusController?.handleKey(view, keyCode, event) == true
            },
            onItemsChanged = {
                tvFocusController?.onDataChanged(TvDataChangeReason.REMOVE_ITEM)
            }
        )
        concatAdapter = ConcatAdapter(
            ConcatAdapter.Config.Builder()
                .setIsolateViewTypes(false)
                .build(),
            headerAdapter,
            videoAdapter
        )

        val layoutManager = WrapContentGridLayoutManager(requireContext(), SPAN_COUNT)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return when {
                    position == 0 -> SPAN_COUNT
                    concatAdapter.getItemViewType(position) == BaseAdapter.LOAD_MORE_TYPE -> SPAN_COUNT
                    else -> 1
                }
            }
        }
        binding.recyclerViewVideos.layoutManager = layoutManager
        binding.recyclerViewVideos.adapter = concatAdapter
        if (binding.recyclerViewVideos.itemDecorationCount == 0) {
            binding.recyclerViewVideos.addItemDecoration(
                GridSpacingItemDecoration(
                    SPAN_COUNT,
                    resources.getDimensionPixelSize(R.dimen.px20),
                    includeEdge = true
                )
            )
        }
        binding.recyclerViewVideos.setHasFixedSize(true)
        binding.recyclerViewVideos.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val gridLayoutManager = recyclerView.layoutManager as? WrapContentGridLayoutManager ?: return
                val lastVisibleItem = gridLayoutManager.findLastVisibleItemPosition()
                if (!isLoading && hasMore && lastVisibleItem >= concatAdapter.itemCount - 6) {
                    currentPage++
                    loadUserVideos()
                }
            }
        })
        installTvFocusController()

        videoAdapter.setOnItemClickListener { _, item ->
            if (item.aid != 0L || item.bvid.isNotBlank()) {
                VideoRouteNavigator.openVideo(
                    context = requireContext(),
                    video = item,
                    playQueue = com.tutu.myblbl.ui.activity.PlayerActivity.buildPlayQueue(
                        videoAdapter.getItemsSnapshot(),
                        item
                    )
                )
            }
        }

        binding.buttonBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        binding.buttonBack.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) {
                return@setOnKeyListener false
            }
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_RIGHT -> focusHeaderFromContent()
                else -> false
            }
        }
        binding.buttonBack.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                lastFocusedArea = FocusArea.BACK
            }
        }
    }

    override fun initData() {
        loadUserInfo()
        loadUserStat()
        loadUserVideos()
        requestHeaderFocus(FocusArea.BACK)
    }

    override fun initObserver() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                appEventHub.events.collectLatest { event ->
                    if (event == AppEventHub.Event.UserSessionChanged && !isHidden) {
                        loadUserInfo()
                        loadUserStat()
                        refreshUserVideos()
                    }
                }
            }
        }
    }

    private fun loadUserInfo() {
        if (mid == 0L) return

        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val response = userRepository.getUserSpace(mid)
                binding.progressBar.visibility = View.GONE
                if (response.isSuccess) {
                    response.data?.let { info ->
                        userSpaceInfo = info
                        updateUserInfo(info)
                        if (sessionGateway.isLoggedIn() && sessionGateway.getUserInfo()?.mid != mid) {
                            loadRelationState()
                        }
                    }
                } else if (response.code != -101) {
                    Toast.makeText(requireContext(), response.errorMessage, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(requireContext(), "加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadUserVideos() {
        if (mid == 0L || isLoading || !hasMore) return

        isLoading = true
        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            val result = userRepository.getUserDynamic(mid, currentPage, 20)

            result.onSuccess { response ->
                binding.progressBar.visibility = View.GONE
                isLoading = false

                if (response.isSuccess) {
                    response.data?.let { page ->
                        val items = ContentFilter.filterVideos(requireContext(), page.archives)
                        hasMore = page.hasMore
                        if (currentPage == 1) {
                            videoAdapter.setData(items)
                            tvFocusController?.onDataChanged(TvDataChangeReason.REPLACE_PRESERVE_ANCHOR)
                        } else {
                            videoAdapter.addData(items)
                            tvFocusController?.onDataChanged(TvDataChangeReason.APPEND)
                        }
                        headerAdapter.updateVideoCount(page.totalCount)
                        if (!hasRequestedInitialFocus && currentPage == 1) {
                            hasRequestedInitialFocus = true
                            requestHeaderFocus(FocusArea.BACK)
                        } else if (lastFocusedArea == FocusArea.CONTENT) {
                            requestVideoFocus(lastFocusedVideoPosition)
                        } else {
                            Unit
                        }
                    }
                } else {
                    rollbackPage()
                    Toast.makeText(requireContext(), response.message, Toast.LENGTH_SHORT).show()
                }
            }.onFailure { e ->
                binding.progressBar.visibility = View.GONE
                isLoading = false
                rollbackPage()
                Toast.makeText(requireContext(), "加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateUserInfo(info: UserSpaceInfo) {
        val isSelf = sessionGateway.getUserInfo()?.mid == mid
        headerAdapter.updateUserInfo(info, showFollow = !isSelf)
        if (isSelf || !sessionGateway.isLoggedIn()) {
            relationAttribute = 0
            renderFollowButton()
        }
    }

    private fun loadUserStat() {
        if (mid == 0L) {
            headerAdapter.updateStat(null, null)
            return
        }

        lifecycleScope.launch {
            try {
                val isSelf = sessionGateway.getUserInfo()?.mid == mid
                val response = if (isSelf) {
                    userRepository.getUserStat()
                } else {
                    userRepository.getRelationStat(mid)
                }
                val stat = response.data
                if (response.isSuccess && stat != null) {
                    headerAdapter.updateStat(stat.following, stat.follower)
                } else {
                    headerAdapter.updateStat(null, null)
                }
            } catch (_: Exception) {
                headerAdapter.updateStat(null, null)
            }
        }
    }

    private fun openFollowList(type: Int) {
        if (mid > 0L) {
            openInHostContainer(FollowUserListFragment.newInstance(mid, type))
        }
    }

    private fun refreshUserVideos() {
        currentPage = 1
        hasMore = true
        loadUserVideos()
    }

    private fun rollbackPage() {
        if (currentPage > 1) {
            currentPage--
        }
    }

    private fun loadRelationState() {
        if (!sessionGateway.isLoggedIn() || sessionGateway.getUserInfo()?.mid == mid) {
            return
        }
        lifecycleScope.launch {
            userRepository.checkUserRelation(mid)
                .onSuccess { response ->
                    if (response.isSuccess && response.data != null) {
                        updateRelationState(response.data)
                    }
                }
        }
    }

    private fun updateRelationState(relation: CheckRelationModel) {
        relationAttribute = relation.attribute
        renderFollowButton()
    }

    private fun renderFollowButton() {
        val labelRes = when {
            relationAttribute == 6 -> R.string.follow_as_friend
            relationAttribute == 2 -> R.string.followed
            else -> R.string.follow
        }
        headerAdapter.updateFollowState(
            labelRes = labelRes,
            backgroundRes = if (isFollowing()) {
                R.drawable.button_common_2
            } else {
                R.drawable.button_common
            }
        )
    }

    private fun doFollow() {
        if (!checkLogin()) return

        val action = if (isFollowing()) 2 else 1

        lifecycleScope.launch {
            val result = userRepository.modifyRelation(mid, action)
            result.onSuccess { response ->
                if (response.isSuccess) {
                    relationAttribute = if (action == 1) 2 else 0
                    renderFollowButton()
                    loadRelationState()
                    loadUserStat()
                    Toast.makeText(
                        requireContext(),
                        if (action == 1) "关注成功" else "已取消关注",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(requireContext(), response.message, Toast.LENGTH_SHORT).show()
                }
            }.onFailure { e ->
                Toast.makeText(requireContext(), "操作失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun checkLogin(): Boolean {
        if (!sessionGateway.isLoggedIn()) {
            return false
        }
        return true
    }

    private fun isFollowing(): Boolean {
        return relationAttribute == 2 || relationAttribute == 6
    }

    private fun focusHeaderFromContent(): Boolean {
        val orderedAreas = buildList {
            if (headerAdapter.hasVisibleFollowAction()) {
                add(FocusArea.FOLLOW)
            }
            add(FocusArea.FOLLOWING)
            add(FocusArea.FOLLOWER)
        }
        for (area in orderedAreas) {
            if (requestHeaderFocus(area, retries = 6, fallbackToBack = false)) {
                return true
            }
        }
        return binding.buttonBack.requestFocus()
    }

    private fun restoreFocus() {
        if (!isAdded) return
        binding.root.post {
            if (!isAdded) return@post
            if (lastFocusedArea == FocusArea.CONTENT && requestVideoFocus(lastFocusedVideoPosition)) {
                return@post
            }
            requestHeaderFocus(lastFocusedArea)
        }
    }

    private fun requestHeaderFocus(
        area: FocusArea,
        retries: Int = 6,
        fallbackToBack: Boolean = true
    ): Boolean {
        if (area == FocusArea.BACK) {
            return binding.buttonBack.requestFocus()
        }
        val target = when (area) {
            FocusArea.FOLLOW -> UserSpaceHeaderAdapter.FocusTarget.FOLLOW
            FocusArea.FOLLOWING -> UserSpaceHeaderAdapter.FocusTarget.FOLLOWING
            FocusArea.FOLLOWER -> UserSpaceHeaderAdapter.FocusTarget.FOLLOWER
            else -> null
        } ?: return binding.buttonBack.requestFocus()
        if (headerAdapter.requestFocus(target)) {
            return true
        }
        if (retries > 0) {
            binding.recyclerViewVideos.scrollToPosition(0)
            binding.recyclerViewVideos.post {
                requestHeaderFocus(area, retries - 1, fallbackToBack)
            }
            return false
        }
        return if (fallbackToBack) {
            binding.buttonBack.requestFocus()
        } else {
            false
        }
    }

    private fun requestVideoFocus(position: Int, retries: Int = 6): Boolean {
        val itemCount = videoAdapter.contentCount()
        if (itemCount == 0) {
            return false
        }
        val targetPosition = position
            .takeIf { it != RecyclerView.NO_POSITION }
            ?.coerceIn(0, itemCount - 1)
            ?: 0

        val absolutePosition = headerAdapter.itemCount + targetPosition
        if (tvFocusController?.requestFocusPosition(absolutePosition) == true) {
            return true
        }
        val holder = binding.recyclerViewVideos.findViewHolderForAdapterPosition(absolutePosition)
        if (holder?.itemView?.requestFocus() == true) {
            return true
        }

        if (retries > 0) {
            binding.recyclerViewVideos.scrollToPosition(absolutePosition)
            binding.recyclerViewVideos.post { requestVideoFocus(targetPosition, retries - 1) }
        }
        return false
    }

    private fun installTvFocusController() {
        tvFocusController?.release()
        tvFocusController = TvListFocusController(
            recyclerView = binding.recyclerViewVideos,
            adapter = OffsetTvFocusableAdapter(videoAdapter) { headerAdapter.itemCount },
            strategy = GridTvFocusStrategy { SPAN_COUNT },
            canLoadMore = { !isLoading && hasMore },
            loadMore = {
                if (!isLoading && hasMore) {
                    currentPage++
                    loadUserVideos()
                }
            }
        )
    }

    override fun onDestroyView() {
        tvFocusController?.release()
        tvFocusController = null
        super.onDestroyView()
    }
}

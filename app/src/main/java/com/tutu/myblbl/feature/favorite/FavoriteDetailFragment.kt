package com.tutu.myblbl.feature.favorite

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.FragmentFavoriteDetailBinding
import com.tutu.myblbl.event.AppEventHub
import com.tutu.myblbl.network.session.NetworkSessionGateway
import com.tutu.myblbl.repository.FavoriteRepository
import com.tutu.myblbl.ui.adapter.FavoriteHistoryAdapter
import com.tutu.myblbl.core.ui.base.BaseFragment
import com.tutu.myblbl.core.ui.layout.WrapContentGridLayoutManager
import com.tutu.myblbl.core.ui.decoration.GridSpacingItemDecoration
import com.tutu.myblbl.core.common.content.ContentFilter
import com.tutu.myblbl.core.navigation.VideoRouteNavigator
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class FavoriteDetailFragment : BaseFragment<FragmentFavoriteDetailBinding>() {

    companion object {
        private const val ARG_FOLDER_ID = "folder_id"
        private const val ARG_TITLE = "title"

        fun newInstance(folderId: Long, title: String): FavoriteDetailFragment {
            return FavoriteDetailFragment().apply {
                arguments = bundleOf(
                    ARG_FOLDER_ID to folderId,
                    ARG_TITLE to title
                )
            }
        }
    }

    private var folderId: Long = 0
    private var title: String = ""

    private val appEventHub: AppEventHub by inject()
    private val sessionGateway: NetworkSessionGateway by inject()
    private val favoriteRepository: FavoriteRepository by inject()
    private lateinit var favoriteAdapter: FavoriteHistoryAdapter

    private var currentPage = 1
    private var isLoading = false
    private var hasMore = true
    private var lastFocusedPosition = RecyclerView.NO_POSITION
    private var pendingRestoreFocus = false
    private var hasRequestedInitialFocus = false

    override fun getViewBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentFavoriteDetailBinding {
        return FragmentFavoriteDetailBinding.inflate(inflater, container, false)
    }

    override fun initArguments() {
        folderId = arguments?.getLong(ARG_FOLDER_ID) ?: 0
        title = arguments?.getString(ARG_TITLE) ?: ""
    }

    override fun initView() {
        binding.tvTitle.text = title

        favoriteAdapter = FavoriteHistoryAdapter(
            onItemClick = { item ->
                val video = item.toVideoModel()
                if (video.aid != 0L || video.bvid.isNotEmpty()) {
                    lastFocusedPosition = favoriteAdapter.getFocusedPosition()
                    pendingRestoreFocus = true
                    VideoRouteNavigator.openHistory(
                        context = requireContext(),
                        historyVideo = item,
                        playQueue = com.tutu.myblbl.ui.activity.PlayerActivity.buildPlayQueue(
                            favoriteAdapter.getItemsSnapshot().map { it.toVideoModel() },
                            video
                        )
                    )
                }
            },
            onItemFocused = { position ->
                lastFocusedPosition = position
            }
        )
        binding.recyclerViewVideos.layoutManager = WrapContentGridLayoutManager(requireContext(), 4)
        binding.recyclerViewVideos.adapter = favoriteAdapter
        if (binding.recyclerViewVideos.itemDecorationCount == 0) {
            binding.recyclerViewVideos.addItemDecoration(
                GridSpacingItemDecoration(4, resources.getDimensionPixelSize(com.tutu.myblbl.R.dimen.px20), true)
            )
        }
        binding.recyclerViewVideos.setHasFixedSize(true)
        binding.recyclerViewVideos.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val layoutManager = recyclerView.layoutManager as? WrapContentGridLayoutManager ?: return
                val lastVisibleItem = layoutManager.findLastVisibleItemPosition()
                if (!isLoading && hasMore && lastVisibleItem >= layoutManager.itemCount - 5) {
                    currentPage++
                    loadFavoriteVideos()
                }
            }
        })

        binding.buttonBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        binding.buttonBack.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                lastFocusedPosition = RecyclerView.NO_POSITION
            }
        }
    }

    override fun initData() {
        loadFavoriteInfo()
        loadFavoriteVideos()
    }

    override fun onResume() {
        super.onResume()
        if (pendingRestoreFocus) {
            pendingRestoreFocus = false
            restoreFocus()
        }
    }

    override fun initObserver() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                appEventHub.events.collectLatest { event ->
                    if (event == AppEventHub.Event.UserSessionChanged && !isHidden && isVisible) {
                        currentPage = 1
                        hasMore = true
                        loadFavoriteInfo()
                        loadFavoriteVideos()
                    }
                }
            }
        }
    }

    private fun loadFavoriteInfo() {
        if (folderId == 0L) return

        lifecycleScope.launch {
            favoriteRepository.getFavoriteFolderInfo(folderId)
                .onSuccess { response ->
                    if (response.isSuccess && response.data != null) {
                        binding.tvTitle.text = response.data.title.ifEmpty { title }
                    }
                }
        }
    }

    private fun loadFavoriteVideos() {
        if (folderId == 0L || isLoading || !hasMore) return

        if (!sessionGateway.isLoggedIn()) {
            hasMore = false
            favoriteAdapter.setData(emptyList())
            showEmpty(getString(R.string.need_sign_in))
            requestBackFocus()
            return
        }

        isLoading = true
        val hasExistingItems = favoriteAdapter.itemCount > 0
        if (currentPage == 1 && !hasExistingItems) {
            binding.progressBar.visibility = View.VISIBLE
        }

        lifecycleScope.launch {
            val result = favoriteRepository.getFavoriteFolderDetail(folderId, currentPage, 20)

            binding.progressBar.visibility = android.view.View.GONE
            isLoading = false

            result.onSuccess { response ->
                if (response.isSuccess) {
                    val detail = response.data
                    val medias = detail?.medias.orEmpty()
                    hasMore = detail?.hasMore == true
                    detail?.info?.title
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { binding.tvTitle.text = it }

                    if (currentPage == 1 && medias.isEmpty()) {
                        favoriteAdapter.setData(emptyList())
                        showEmpty(getString(R.string.favorite_folder_content_empty))
                    } else {
                        showListContent()
                        val filtered = medias.filter { !ContentFilter.isVideoBlocked(requireContext(), it.tagName, it.title, authorName = it.authorName) }
                        if (currentPage == 1) {
                            favoriteAdapter.setData(filtered)
                        } else if (filtered.isNotEmpty()) {
                            favoriteAdapter.addData(filtered)
                        }
                        if (!hasRequestedInitialFocus && currentPage == 1) {
                            hasRequestedInitialFocus = true
                            requestBackFocus()
                        } else if (lastFocusedPosition != RecyclerView.NO_POSITION) {
                            restoreFocus()
                        }
                    }
                } else {
                    rollbackPage()
                    handleLoadError(response.errorMessage)
                }
            }.onFailure { e ->
                rollbackPage()
                handleLoadError("加载失败: ${e.message}")
            }
        }
    }

    private fun showListContent() {
        binding.tvEmpty.visibility = View.GONE
        binding.recyclerViewVideos.visibility = View.VISIBLE
    }

    private fun showEmpty(message: String) {
        binding.tvEmpty.visibility = View.VISIBLE
        binding.tvEmpty.text = message
        binding.recyclerViewVideos.visibility = View.GONE
    }

    private fun handleLoadError(message: String) {
        if (currentPage == 1 && favoriteAdapter.itemCount == 0) {
            showEmpty(message)
            return
        }
        showListContent()
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun rollbackPage() {
        if (currentPage > 1) {
            currentPage--
        }
    }

    private fun restoreFocus() {
        if (!isAdded) return
        binding.recyclerViewVideos.post {
            if (!isAdded) return@post
            if (binding.recyclerViewVideos.isVisible && favoriteAdapter.itemCount > 0 && lastFocusedPosition != RecyclerView.NO_POSITION) {
                val targetPosition = lastFocusedPosition.coerceIn(0, favoriteAdapter.itemCount - 1)
                binding.recyclerViewVideos.scrollToPosition(targetPosition)
                requestItemFocus(targetPosition)
            } else {
                requestBackFocus()
            }
        }
    }

    private fun requestBackFocus() {
        if (!isAdded) return
        binding.buttonBack.post {
            if (isAdded && !binding.buttonBack.hasFocus()) {
                binding.buttonBack.requestFocus()
            }
        }
    }

    private fun requestItemFocus(position: Int, retries: Int = 6) {
        val holder = binding.recyclerViewVideos.findViewHolderForAdapterPosition(position)
        if (holder?.itemView?.requestFocus() == true) {
            return
        }
        if (retries > 0) {
            binding.recyclerViewVideos.post { requestItemFocus(position, retries - 1) }
        }
    }
}

package com.tutu.myblbl.feature.detail

import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import com.tutu.myblbl.R
import com.tutu.myblbl.core.common.content.ContentFilter
import com.tutu.myblbl.core.navigation.VideoRouteNavigator
import com.tutu.myblbl.core.ui.base.BaseListFragment
import com.tutu.myblbl.core.ui.decoration.GridSpacingItemDecoration
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.repository.VideoRepository
import com.tutu.myblbl.ui.adapter.VideoAdapter
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class ChannelVideoFragment : BaseListFragment<VideoModel>(), com.tutu.myblbl.ui.activity.MainActivity.OnVideoBlockedListener {

    companion object {
        private const val ARG_TITLE = "title"
        private const val ARG_TAG_ID = "tagId"

        fun newInstance(title: String, tagId: Long): ChannelVideoFragment {
            return ChannelVideoFragment().apply {
                arguments = bundleOf(
                    ARG_TITLE to title,
                    ARG_TAG_ID to tagId
                )
            }
        }
    }

    private var title: String = ""
    private var tagId: Long = 0L
    private var offset: String = ""
    private val videoRepository: VideoRepository by inject()

    override val autoLoad: Boolean = false
    override val enableTvListFocusController: Boolean = true
    override val enableSwipeRefresh: Boolean = false

    override fun getSpanCount(): Int = 4

    override fun initArguments() {
        title = arguments?.getString(ARG_TITLE).orEmpty()
        tagId = arguments?.getLong(ARG_TAG_ID) ?: 0L
    }

    override fun isTopBarVisible(): Boolean = true

    override fun createAdapter(): VideoAdapter {
        return VideoAdapter(
            onItemClick = ::onVideoClick,
            onItemFocusedWithView = { view, position ->
                tvFocusController?.onItemFocused(view, position)
            },
            onItemDpad = { view, keyCode, event ->
                tvFocusController?.handleKey(view, keyCode, event) == true
            }
        )
    }

    override fun initView() {
        super.initView()
        setMainTitle(title)
        recyclerView?.addItemDecoration(
            GridSpacingItemDecoration(getSpanCount(), resources.getDimensionPixelSize(R.dimen.px20), true)
        )
    }

    override fun initData() {
        showLoading(true)
        loadData(1)
    }

    override fun loadData(page: Int) {
        if (isLoading || tagId <= 0) return
        isLoading = true
        lifecycleScope.launch {
            videoRepository.getChannelVideos(
                channelId = tagId,
                offset = offset,
                pageSize = 30
            ).onSuccess { result ->
                isLoading = false
                hasMore = result.hasMore
                offset = result.offset
                adapter?.setShowLoadMore(hasMore)
                val hasExistingItems = (adapter?.contentCount() ?: 0) > 0
                val videos = ContentFilter.filterVideos(requireContext(), result.videos)
                if (videos.isEmpty() && !hasExistingItems) {
                    showLoading(false)
                    showEmpty()
                } else {
                    showLoading(false)
                    showContent()
                    if (!hasExistingItems) {
                        setAdapterData(videos)
                    } else {
                        (adapter as? VideoAdapter)?.addData(videos)
                    }
                }
            }.onFailure {
                isLoading = false
                showLoading(false)
                if ((adapter?.contentCount() ?: 0) == 0) {
                    showError(it.message)
                }
            }
        }
    }

    override fun onRetryClick() {
        offset = ""
        showLoading(true)
        loadData(1)
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

    override fun onVideoBlocked(aid: Long, bvid: String) {
        (adapter as? VideoAdapter)?.removeByVideoId(aid, bvid)
    }
}

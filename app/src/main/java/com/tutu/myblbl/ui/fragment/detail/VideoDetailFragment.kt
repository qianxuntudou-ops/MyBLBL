@file:Suppress("SpellCheckingInspection")

package com.tutu.myblbl.ui.fragment.detail

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.flexbox.FlexboxLayout
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.CellSeriesLaneBinding
import com.tutu.myblbl.databinding.CellVideoDetailHeadBinding
import com.tutu.myblbl.databinding.FragmentVideoDetailBinding
import com.tutu.myblbl.event.AppEventHub
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.model.video.VideoPvModel
import com.tutu.myblbl.model.video.detail.Tag
import com.tutu.myblbl.model.video.detail.UgcEpisode
import com.tutu.myblbl.model.video.detail.UgcSeason
import com.tutu.myblbl.model.video.detail.VideoDetailModel
import com.tutu.myblbl.model.video.detail.VideoView
import com.tutu.myblbl.network.session.NetworkSessionGateway
import com.tutu.myblbl.repository.FavoriteRepository
import com.tutu.myblbl.repository.VideoRepository
import com.tutu.myblbl.ui.adapter.EpisodeListAdapter
import com.tutu.myblbl.ui.adapter.VideoAdapter
import com.tutu.myblbl.ui.base.BaseFragment
import com.tutu.myblbl.ui.dialog.OwnerDetailDialog
import com.tutu.myblbl.ui.dialog.PlayerActionDialog
import com.tutu.myblbl.feature.search.SearchNewFragment
import com.tutu.myblbl.utils.serializableCompat
import com.tutu.myblbl.utils.ContentFilter
import com.tutu.myblbl.utils.ImageLoader
import com.tutu.myblbl.utils.TimeUtils
import com.tutu.myblbl.utils.VideoRouteNavigator
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import java.util.Locale

class VideoDetailFragment : BaseFragment<FragmentVideoDetailBinding>() {

    companion object {
        private const val ARG_VIDEO = "video"
        private const val ARG_AID = "aid"
        private const val ARG_BVID = "bvid"

        fun newInstance(video: VideoModel): VideoDetailFragment {
            return VideoDetailFragment().apply {
                arguments = bundleOf(ARG_VIDEO to video)
            }
        }

        fun newInstance(aid: Long): VideoDetailFragment {
            return VideoDetailFragment().apply {
                arguments = bundleOf(ARG_AID to aid)
            }
        }

        fun newInstance(bvid: String): VideoDetailFragment {
            return VideoDetailFragment().apply {
                arguments = bundleOf(ARG_BVID to bvid)
            }
        }
    }

    private var videoModel: VideoModel? = null
    private var videoView: VideoView? = null
    private var aid: Long? = null
    private var bvid: String? = null

    private val appEventHub: AppEventHub by inject()
    private val sessionGateway: NetworkSessionGateway by inject()
    private val videoRepository: VideoRepository by inject()
    private val favoriteRepository: FavoriteRepository by inject()

    private lateinit var headBinding: CellVideoDetailHeadBinding
    private lateinit var pagesBinding: CellSeriesLaneBinding
    private lateinit var ugcSeasonBinding: CellSeriesLaneBinding
    private lateinit var relatedBinding: CellSeriesLaneBinding

    private lateinit var relatedAdapter: VideoAdapter
    private var pagesAdapter: EpisodeListAdapter? = null
    private var ugcEpisodeAdapter: EpisodeListAdapter? = null
    private var ugcEpisodes: List<UgcEpisode> = emptyList()
    private var ugcReverseOrder = false

    private var isLiked = false
    private var isFavorited = false
    private var isCoined = false

    private var actionDialog: PlayerActionDialog? = null
    private var ownerDetailDialog: OwnerDetailDialog? = null

    override fun getViewBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentVideoDetailBinding {
        return FragmentVideoDetailBinding.inflate(inflater, container, false)
    }

    override fun initArguments() {
        arguments?.let { args ->
            videoModel = args.serializableCompat(ARG_VIDEO)
            aid = if (args.containsKey(ARG_AID)) args.getLong(ARG_AID) else null
            bvid = if (args.containsKey(ARG_BVID)) args.getString(ARG_BVID) else null
        }
    }

    override fun initView() {
        headBinding = CellVideoDetailHeadBinding.bind(binding.headLayout.root)
        pagesBinding = CellSeriesLaneBinding.bind(binding.layoutPages.root)
        ugcSeasonBinding = CellSeriesLaneBinding.bind(binding.layoutUgcSeason.root)
        relatedBinding = CellSeriesLaneBinding.bind(binding.layoutRelated.root)

        initRelatedSection()

        binding.buttonBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        headBinding.buttonUploader.setOnClickListener {
            showOwnerDetailDialog()
        }

        headBinding.buttonPlay.setOnClickListener {
            playVideo()
        }

        headBinding.buttonLike.setOnClickListener {
            showActionDialog()
        }

        headBinding.buttonCoin.setOnClickListener {
            showActionDialog()
        }

        headBinding.buttonFavorite.setOnClickListener {
            showActionDialog()
        }

        initHeadFocusChain()
    }

    private fun initRelatedSection() {
        relatedAdapter = VideoAdapter()
        relatedBinding.topTitle.text = getString(R.string.related_video)
        relatedBinding.buttonOrder.isVisible = false
        relatedBinding.recyclerView.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        relatedBinding.recyclerView.adapter = relatedAdapter
        relatedAdapter.setOnItemClickListener { _, item ->
            if (item.aid != 0L || item.bvid.isNotBlank()) {
                VideoRouteNavigator.openVideo(
                    context = requireContext(),
                    video = item,
                    playQueue = com.tutu.myblbl.ui.activity.PlayerActivity.buildPlayQueue(
                        relatedAdapter.getItemsSnapshot(),
                        item
                    )
                )
            }
        }
    }

    private fun initPagesSection(pages: List<VideoPvModel>) {
        if (pages.isEmpty()) {
            pagesBinding.root.isVisible = false
            return
        }
        pagesBinding.root.isVisible = true
        pagesBinding.topTitle.text = getString(R.string.video_detail_pages_title, pages.size)
        pagesBinding.buttonOrder.isVisible = false
        pagesAdapter = EpisodeListAdapter()
        pagesBinding.recyclerView.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        pagesBinding.recyclerView.adapter = pagesAdapter
        val currentBvid = videoView?.bvid ?: videoModel?.bvid ?: ""
        val currentAid = videoView?.aid ?: videoModel?.aid ?: 0L
        pagesAdapter?.setData(pages.mapIndexed { index, pv ->
            VideoModel(
                aid = currentAid,
                bvid = currentBvid,
                title = pv.part.ifBlank { "P${index + 1}" },
                cid = pv.cid,
                duration = pv.duration
            )
        })
        pagesAdapter?.setOnItemClickListener { _, model ->
            VideoRouteNavigator.openVideo(
                context = requireContext(),
                video = model,
                playQueue = com.tutu.myblbl.ui.activity.PlayerActivity.buildPlayQueue(
                    pagesAdapter?.getItemsSnapshot().orEmpty(),
                    model
                ),
                forcePlayer = true
            )
        }
    }

    private fun initUgcSeasonSection(ugcSeason: UgcSeason) {
        ugcEpisodes = ugcSeason.sections?.flatMap { it.episodes ?: emptyList() } ?: emptyList()
        if (ugcEpisodes.isEmpty()) {
            ugcSeasonBinding.root.isVisible = false
            return
        }
        ugcSeasonBinding.root.isVisible = true
        ugcSeasonBinding.topTitle.text = ugcSeason.title
        ugcSeasonBinding.buttonOrder.isVisible = true
        ugcSeasonBinding.buttonOrder.setOnClickListener {
            ugcReverseOrder = !ugcReverseOrder
            ugcSeasonBinding.textOrder.text = if (ugcReverseOrder) "正序" else "倒序"
            bindUgcEpisodes()
        }
        ugcEpisodeAdapter = EpisodeListAdapter()
        ugcSeasonBinding.recyclerView.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        ugcSeasonBinding.recyclerView.adapter = ugcEpisodeAdapter
        bindUgcEpisodes()
        ugcEpisodeAdapter?.setOnItemClickListener { _, model ->
            VideoRouteNavigator.openVideo(
                context = requireContext(),
                video = model,
                playQueue = com.tutu.myblbl.ui.activity.PlayerActivity.buildPlayQueue(
                    ugcEpisodeAdapter?.getItemsSnapshot().orEmpty(),
                    model
                )
            )
        }
    }

    private fun bindUgcEpisodes() {
        val videos = ugcEpisodes.mapNotNull { it.arc }
        val ordered = if (ugcReverseOrder) videos.reversed() else videos
        ugcEpisodeAdapter?.setData(ordered)
    }

    private fun initHeadFocusChain() {
        headBinding.buttonPlay.nextFocusLeftId = headBinding.buttonUploader.id
        headBinding.buttonUploader.nextFocusRightId = headBinding.buttonPlay.id
        headBinding.buttonUploader.nextFocusUpId = binding.buttonBack.id
    }

    override fun initData() {
        loadVideoDetail()
    }

    override fun initObserver() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                appEventHub.events.collectLatest { event ->
                    if (event !is AppEventHub.Event.PlaybackProgressUpdated) {
                        return@collectLatest
                    }
                    val currentVideo = videoModel
                    if (currentVideo == null || event.aid != currentVideo.aid) {
                        return@collectLatest
                    }
                    videoModel = currentVideo.copy(
                        cid = event.cid,
                        historyProgress = event.progressMs.coerceAtLeast(0L)
                    )
                    videoView = videoView?.copy(cid = event.cid)
                }
            }
        }
    }

    private fun loadVideoDetail() {
        binding.progressBar.isVisible = true
        binding.viewError.isVisible = false

        val currentAid = videoModel?.aid ?: aid
        val currentBvid = videoModel?.bvid ?: bvid

        lifecycleScope.launch {
            binding.progressBar.isVisible = false
            runCatching {
                videoRepository.getVideoDetail(currentAid, currentBvid)
            }.onSuccess { response ->
                if (response.isSuccess) {
                    response.data?.let(::updateUI)
                } else {
                    showErrorView(response.message)
                }
            }.onFailure { e ->
                showErrorView(e.message ?: "加载失败")
            }
        }
    }

    private fun showErrorView(message: String) {
        binding.viewError.isVisible = true
        binding.textError.text = message
        binding.imageError.setImageResource(R.drawable.net_error)
    }

    private fun updateUI(detail: VideoDetailModel) {
        val view = detail.view ?: return
        videoView = view

        if (videoModel == null) {
            videoModel = VideoModel(
                aid = view.aid,
                bvid = view.bvid,
                title = view.title,
                pic = view.pic,
                owner = view.owner,
                stat = view.stat,
                cid = view.cid,
                duration = view.duration
            )
        }

        binding.textPageTitle.text = view.title

        updateHeadUI(view, detail.tags)

        detail.related?.let { rawRelated ->
            val related = ContentFilter.filterVideos(requireContext(), rawRelated)
            if (related.isNotEmpty()) {
                relatedBinding.root.isVisible = true
                relatedAdapter.setData(related)
            }
        }

        view.pages?.let { pages ->
            if (pages.size > 1) {
                initPagesSection(pages)
            }
        }

        view.ugcSeason?.let { ugcSeason ->
            val allEpisodes = ugcSeason.sections?.flatMap { it.episodes ?: emptyList() } ?: emptyList()
            if (allEpisodes.isNotEmpty()) {
                initUgcSeasonSection(ugcSeason)
            }
        }

        refreshActionState()
    }

    private fun updateHeadUI(view: VideoView, tags: List<Tag>?) {
        ImageLoader.loadVideoCover(
            imageView = headBinding.imageCover,
            url = view.pic,
            placeholder = R.drawable.default_video,
            error = R.drawable.default_video
        )

        headBinding.textTitle.text = view.title

        val stat = view.stat
        val subtitleText = buildString {
            stat?.let { s ->
                append(formatCount(s.view))
                append("播放")
                append(" · ")
                append(formatCount(s.danmaku))
                append("弹幕")
            }
        }
        headBinding.textSubtitle.text = subtitleText

        val owner = view.owner
        if (owner != null) {
            headBinding.textName.text = owner.name
            ImageLoader.loadCircle(
                imageView = headBinding.imageAvatar,
                url = owner.face,
                placeholder = R.drawable.default_avatar,
                error = R.drawable.default_avatar
            )
        }

        val descText = buildString {
            append(view.desc)
            if (view.pubDate > 0) {
                append("\n\n")
                append(TimeUtils.formatTime(view.pubDate))
            }
        }
        headBinding.textDescription.text = descText

        updateTagLayout(tags)

        updateActionButtons()
    }

    private fun updateTagLayout(tags: List<Tag>?) {
        headBinding.viewFlexLayout.removeAllViews()
        if (tags.isNullOrEmpty()) {
            headBinding.viewFlexLayout.isVisible = false
            return
        }
        headBinding.viewFlexLayout.isVisible = true
        tags.take(6).forEach { tag ->
            val displayName = tag.tagName
            val tagView = createTagView(displayName)
            tagView.setOnClickListener {
                val effectiveTagId = tag.tagId.takeIf { it > 0 } ?: tag.id
                if (tag.tagType == "new_channel" && effectiveTagId > 0) {
                    openInHostContainer(
                        ChannelVideoFragment.newInstance(displayName, effectiveTagId)
                    )
                } else {
                    val keyword = displayName
                    mainActivity?.openSearch(keyword) ?: openInHostContainer(
                        SearchNewFragment.newInstance(keyword)
                    )
                }
            }
            headBinding.viewFlexLayout.addView(tagView)
        }
    }

    private fun createTagView(text: String): androidx.appcompat.widget.AppCompatTextView {
        val paddingH = resources.getDimensionPixelSize(R.dimen.px20)
        val paddingV = resources.getDimensionPixelSize(R.dimen.px10)
        return androidx.appcompat.widget.AppCompatTextView(requireContext()).apply {
            this.text = text
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.subTextColor))
            setBackgroundResource(R.drawable.cell_background)
            setPadding(paddingH, paddingV, paddingH, paddingV)
            layoutParams = FlexboxLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { lp ->
                lp.setMargins(paddingH / 2, paddingV / 2, paddingH / 2, paddingV / 2)
            }
            isClickable = true
            isFocusable = true
        }
    }

    private fun updateActionButtons() {
        headBinding.buttonLike.alpha = if (isLiked) 1f else 0.7f
        headBinding.buttonCoin.alpha = if (isCoined) 1f else 0.7f
        headBinding.buttonFavorite.alpha = if (isFavorited) 1f else 0.7f
    }

    private fun refreshActionState() {
        if (!sessionGateway.isLoggedIn()) {
            return
        }
        val currentAid = videoView?.aid ?: videoModel?.aid ?: return
        val currentBvid = videoView?.bvid ?: videoModel?.bvid

        lifecycleScope.launch {
            runCatching { videoRepository.hasLike(currentAid, currentBvid) }
                .onSuccess { response ->
                    if (response.isSuccess) {
                        isLiked = response.data == 1
                        updateActionButtons()
                    }
                }
            runCatching { videoRepository.hasGiveCoin(currentAid, currentBvid) }
                .onSuccess { response ->
                    if (response.isSuccess) {
                        isCoined = (response.data?.multiply ?: 0) > 0
                        updateActionButtons()
                    }
                }
            favoriteRepository.checkFavorite(currentAid)
                .onSuccess { response ->
                    if (response.isSuccess) {
                        isFavorited = response.data?.favoured == true
                        updateActionButtons()
                    }
                }
        }
    }

    private fun playVideo() {
        val targetVideo = videoView?.toPlaybackVideoModel() ?: videoModel ?: return
        VideoRouteNavigator.openVideo(
            context = requireContext(),
            video = targetVideo,
            forcePlayer = true
        )
    }

    private fun showActionDialog() {
        val currentAid = videoView?.aid ?: videoModel?.aid ?: return
        val currentBvid = videoView?.bvid ?: videoModel?.bvid ?: return
        val ownerMid = videoView?.owner?.mid ?: videoModel?.owner?.mid ?: 0L

        actionDialog?.dismiss()
        actionDialog = PlayerActionDialog(
            context = requireContext(),
            aid = currentAid,
            bvid = currentBvid,
            ownerMid = ownerMid
        ).apply {
            setOnDismissListener {
                refreshActionState()
            }
            show()
        }
    }

    private fun showOwnerDetailDialog() {
        val owner = videoView?.owner ?: videoModel?.owner ?: return
        val currentAid = videoView?.aid ?: videoModel?.aid ?: 0L
        val currentBvid = videoView?.bvid ?: videoModel?.bvid ?: ""

        ownerDetailDialog?.dismiss()
        ownerDetailDialog = OwnerDetailDialog(
            context = requireContext(),
            owner = owner,
            onOpenSpace = { mid ->
                openInHostContainer(UserSpaceFragment.newInstance(mid))
            },
            onPlayVideo = { video, playQueue ->
                VideoRouteNavigator.openVideo(
                    context = requireContext(),
                    video = video,
                    playQueue = playQueue
                )
            },
            currentAid = currentAid,
            currentBvid = currentBvid
        ).apply {
            show()
        }
    }

    private fun formatCount(count: Long): String {
        return when {
            count >= 100000000 -> String.format(Locale.getDefault(), "%.1f亿", count / 100000000.0)
            count >= 10000 -> String.format(Locale.getDefault(), "%.1f万", count / 10000.0)
            count >= 1000 -> String.format(Locale.getDefault(), "%.1f千", count / 1000.0)
            else -> count.toString()
        }
    }

    override fun onDestroyView() {
        actionDialog?.dismiss()
        actionDialog = null
        ownerDetailDialog?.dismiss()
        ownerDetailDialog = null
        super.onDestroyView()
    }

    private fun VideoView.toPlaybackVideoModel(): VideoModel {
        return VideoModel(
            aid = aid,
            bvid = bvid,
            title = title,
            pic = pic,
            cid = cid,
            duration = duration,
            pubDate = pubDate,
            owner = owner,
            stat = stat
        )
    }
}

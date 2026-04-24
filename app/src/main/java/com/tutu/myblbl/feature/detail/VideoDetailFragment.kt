@file:Suppress("SpellCheckingInspection")

package com.tutu.myblbl.feature.detail

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.tutu.myblbl.R
import com.tutu.myblbl.core.common.content.ContentFilter
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.core.common.settings.AppSettingsDataStore
import com.tutu.myblbl.core.common.time.TimeUtils
import com.tutu.myblbl.core.navigation.VideoRouteNavigator
import com.tutu.myblbl.core.ui.navigation.navigateBackFromUi
import com.tutu.myblbl.databinding.FragmentSeriesDetailBinding
import com.tutu.myblbl.event.AppEventHub
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.model.video.VideoPvModel
import com.tutu.myblbl.model.video.detail.Tag
import com.tutu.myblbl.model.video.detail.UgcEpisode
import com.tutu.myblbl.model.video.detail.UgcSeason
import com.tutu.myblbl.model.video.detail.VideoDetailModel
import com.tutu.myblbl.model.video.detail.VideoView
import com.tutu.myblbl.model.user.CheckRelationModel
import com.tutu.myblbl.network.session.NetworkSessionGateway
import com.tutu.myblbl.repository.FavoriteRepository
import com.tutu.myblbl.repository.UserRepository
import com.tutu.myblbl.repository.VideoRepository
import com.tutu.myblbl.ui.activity.MainActivity
import com.tutu.myblbl.ui.adapter.VideoDetailContentAdapter
import com.tutu.myblbl.ui.dialog.OwnerDetailDialog
import com.tutu.myblbl.ui.dialog.PlayerActionDialog
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import java.util.Locale

class VideoDetailFragment : androidx.fragment.app.Fragment() {

    private val TAG = "VideoDetail"

    companion object {
        private const val ARG_AID = "aid"
        private const val ARG_BVID = "bvid"

        fun newInstance(video: VideoModel): VideoDetailFragment {
            return VideoDetailFragment().apply {
                arguments = bundleOf(
                    ARG_AID to video.aid.takeIf { it > 0L },
                    ARG_BVID to video.bvid.takeIf { it.isNotBlank() }
                )
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

    private var _binding: FragmentSeriesDetailBinding? = null
    private val binding get() = _binding!!
    private lateinit var contentAdapter: VideoDetailContentAdapter
    private var mainActivity: MainActivity? = null

    private var videoModel: VideoModel? = null
    private var videoView: VideoView? = null
    private var aid: Long? = null
    private var bvid: String? = null

    private val appEventHub: AppEventHub by inject()
    private val sessionGateway: NetworkSessionGateway by inject()
    private val videoRepository: VideoRepository by inject()
    private val favoriteRepository: FavoriteRepository by inject()
    private val userRepository: UserRepository by inject()
    private val appSettings: AppSettingsDataStore by inject()

    private var isLiked = false
    private var isCoined = false
    private var isFavorited = false

    private var ugcEpisodes: List<UgcEpisode> = emptyList()
    private var ugcReverseOrder = true

    private var actionDialog: PlayerActionDialog? = null
    private var ownerDetailDialog: OwnerDetailDialog? = null

    override fun onAttach(context: android.content.Context) {
        super.onAttach(context)
        if (context is MainActivity) {
            mainActivity = context
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let { args ->
            aid = if (args.containsKey(ARG_AID)) args.getLong(ARG_AID) else null
            bvid = if (args.containsKey(ARG_BVID)) args.getString(ARG_BVID) else null
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSeriesDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupViews()
        initObserver()
        loadVideoDetail()
    }

    private fun setupViews() {
        binding.buttonBack1.setOnClickListener {
            navigateBackFromUi()
        }

        contentAdapter = VideoDetailContentAdapter(
            onPlayClick = { playVideo() },
            onUploaderClick = { showOwnerDetailDialog() },
            onLikeClick = { toggleLike() },
            onCoinClick = { giveCoin() },
            onFavoriteClick = { toggleFavorite() },
            onTagClick = { tag -> onTagClicked(tag) },
            onPageClick = { model -> onPageVideoClicked(model) },
            onUgcEpisodeClick = { model -> onUgcEpisodeClicked(model) },
            onUgcOrderToggle = { toggleUgcOrder() },
            onRelatedVideoClick = { model -> onRelatedVideoClicked(model) },
            onDescriptionClick = { desc -> showDescriptionDialog(desc) },
            onFollowClick = { toggleFollow() },
            onTripleAction = { tripleAction() }
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = contentAdapter
        binding.recyclerView.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
    }

    private fun initObserver() {
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
        val currentAid = aid
        val currentBvid = bvid
        AppLog.d(TAG, "loadVideoDetail: start, aid=$currentAid, bvid=$currentBvid")

        view?.post {
            dumpProgressBars(view, "AFTER_LOAD_START")
        }

        lifecycleScope.launch {
            runCatching {
                videoRepository.getVideoDetail(currentAid, currentBvid)
            }.onSuccess { response ->
                AppLog.d(TAG, "loadVideoDetail: response received, code=${response.code}, isSuccess=${response.isSuccess}")
                if (response.isSuccess) {
                    AppLog.d(TAG, "loadVideoDetail: show content, data=${response.data != null}")
                    response.data?.let(::updateUI)
                    view?.post {
                        dumpProgressBars(view, "AFTER_UPDATE_UI")
                    }
                } else {
                    AppLog.w(TAG, "loadVideoDetail: API error code=${response.code}, msg=${response.message}")
                    showError(response.message)
                }
            }.onFailure { e ->
                AppLog.e(TAG, "loadVideoDetail: failed", e)
                showError(e.message ?: "加载失败")
            }
        }
    }

    private fun updateUI(detail: VideoDetailModel) {
        val view = detail.view ?: return
        AppLog.d(TAG, "updateUI: title=${view.title}, tags=${detail.tags?.size}, pages=${view.pages?.size}, related=${detail.related?.size}")
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
                duration = view.duration,
                isUpowerExclusive = view.isUpowerExclusive,
                isChargingArc = view.isChargingArc,
                elecArcType = view.elecArcType,
                elecArcBadge = view.elecArcBadge,
                privilegeType = view.privilegeType
            )
        }
        if (aid == null) aid = view.aid
        if (bvid.isNullOrBlank()) bvid = view.bvid

        ugcEpisodes = view.ugcSeason?.sections?.flatMap { it.episodes ?: emptyList() } ?: emptyList()

        val rows = buildRows(detail)
        contentAdapter.submitList(rows)

        refreshActionState()
        loadRelationState()
    }

    private fun buildRows(detail: VideoDetailModel): List<VideoDetailContentAdapter.Row> {
        val view = videoView ?: return emptyList()
        val rows = mutableListOf<VideoDetailContentAdapter.Row>()

        rows.add(
            VideoDetailContentAdapter.Row.Header(
                view = view,
                tags = detail.tags ?: emptyList(),
                isLiked = isLiked,
                isCoined = isCoined,
                isFavorited = isFavorited
            )
        )

        if (ugcEpisodes.isNotEmpty()) {
            val videos = ugcEpisodes.mapNotNull { episode ->
                episode.arc?.let { arc ->
                    arc.copy(
                        pic = episode.displayCover.ifBlank { arc.coverUrl },
                        aid = episode.displayAid,
                        bvid = episode.displayBvid,
                        cid = episode.cid.takeIf { it > 0 } ?: arc.cid
                    )
                }
            }
            val ordered = if (ugcReverseOrder) videos.reversed() else videos
            if (ordered.isNotEmpty()) {
                val rawTitle = view.ugcSeason?.title.orEmpty()
                val seasonTitle = if (rawTitle.isNotBlank()) "合集（$rawTitle）：" else "合集"
                rows.add(
                    VideoDetailContentAdapter.Row.UgcSeason(
                        title = seasonTitle,
                        items = ordered,
                        isReverse = ugcReverseOrder,
                        currentAid = view.aid
                    )
                )
            }
        }

        detail.related?.let { rawRelated ->
            val related = ContentFilter.filterVideos(requireContext(), rawRelated)
            if (related.isNotEmpty()) {
                rows.add(VideoDetailContentAdapter.Row.Related(items = related))
            }
        }

        return rows
    }

    private fun refreshActionState() {
        if (!sessionGateway.isLoggedIn()) {
            updateActionStateAndRefresh()
            return
        }
        val currentAid = videoView?.aid ?: videoModel?.aid ?: return
        val currentBvid = videoView?.bvid ?: videoModel?.bvid

        lifecycleScope.launch {
            runCatching { videoRepository.hasLike(currentAid, currentBvid) }
                .onSuccess { response ->
                    if (response.isSuccess) {
                        isLiked = response.data == 1
                        updateActionStateAndRefresh()
                    }
                }
            runCatching { videoRepository.hasGiveCoin(currentAid, currentBvid) }
                .onSuccess { response ->
                    if (response.isSuccess) {
                        isCoined = (response.data?.multiply ?: 0) > 0
                        updateActionStateAndRefresh()
                    }
                }
            favoriteRepository.checkFavorite(currentAid)
                .onSuccess { response ->
                    if (response.isSuccess) {
                        isFavorited = response.data?.favoured == true
                        updateActionStateAndRefresh()
                    }
                }
        }
    }

    private fun updateActionStateAndRefresh() {
        val headerIndex = contentAdapter.currentList.indexOfFirst { it is VideoDetailContentAdapter.Row.Header }
        if (headerIndex >= 0) {
            val newList = contentAdapter.currentList.toMutableList()
            val oldHeader = newList[headerIndex] as VideoDetailContentAdapter.Row.Header
            newList[headerIndex] = oldHeader.copy(isLiked = isLiked, isCoined = isCoined, isFavorited = isFavorited)
            contentAdapter.submitList(newList)
        }
    }

    private fun loadRelationState() {
        val ownerMid = videoView?.owner?.mid ?: return
        if (!sessionGateway.isLoggedIn() || sessionGateway.getUserInfo()?.mid == ownerMid) return
        lifecycleScope.launch {
            userRepository.checkUserRelation(ownerMid)
                .onSuccess { response ->
                    if (response.isSuccess && response.data != null) {
                        val holder = binding.recyclerView.findViewHolderForAdapterPosition(0)
                        (holder as? VideoDetailContentAdapter.VideoDetailHeadViewHolder)
                            ?.updateFollowState(response.data.attribute)
                    }
                }
        }
    }

    private fun toggleFollow() {
        if (!sessionGateway.isLoggedIn()) {
            toast(getString(R.string.need_sign_in))
            return
        }
        val ownerMid = videoView?.owner?.mid ?: return
        val holder = binding.recyclerView.findViewHolderForAdapterPosition(0)
            as? VideoDetailContentAdapter.VideoDetailHeadViewHolder ?: return
        val currentAttr = holder.currentRelationAttribute()
        val isFollowing = currentAttr == 2 || currentAttr == 6
        val action = if (isFollowing) 2 else 1
        lifecycleScope.launch {
            userRepository.modifyRelation(ownerMid, action)
                .onSuccess { response ->
                    if (response.isSuccess) {
                        val newAttr = if (action == 1) 2 else 0
                        holder.updateFollowState(newAttr)
                        toast(if (action == 1) "关注成功" else "已取消关注")
                    } else {
                        toast(response.errorMessage)
                    }
                }
                .onFailure { toast(it.message ?: "操作失败") }
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

    private fun onPageVideoClicked(model: VideoModel) {
        val pages = videoView?.pages ?: return
        VideoRouteNavigator.openVideo(
            context = requireContext(),
            video = model,
            playQueue = com.tutu.myblbl.ui.activity.PlayerActivity.buildPlayQueue(
                pages.mapIndexed { index, pv ->
                    VideoModel(
                        aid = videoView?.aid ?: 0L,
                        bvid = videoView?.bvid ?: "",
                        pic = videoView?.pic ?: "",
                        title = pv.part.ifBlank { "P${index + 1}" },
                        cid = pv.cid,
                        duration = pv.duration
                    )
                },
                model
            ),
            forcePlayer = true
        )
    }

    private fun onUgcEpisodeClicked(model: VideoModel) {
        if (model.aid == (videoView?.aid ?: videoModel?.aid)) return
        aid = model.aid.takeIf { it > 0 }
        bvid = model.bvid.takeIf { it.isNotBlank() }
        videoModel = null
        videoView = null
        isLiked = false
        isCoined = false
        isFavorited = false
        loadVideoDetail()
    }

    private fun onRelatedVideoClicked(model: VideoModel) {
        if (model.aid != 0L || model.bvid.isNotBlank()) {
            val relatedRow = contentAdapter.currentList
                .find { it is VideoDetailContentAdapter.Row.Related } as? VideoDetailContentAdapter.Row.Related
            VideoRouteNavigator.openVideo(
                context = requireContext(),
                video = model,
                playQueue = com.tutu.myblbl.ui.activity.PlayerActivity.buildPlayQueue(
                    relatedRow?.items ?: emptyList(),
                    model
                )
            )
        }
    }

    private fun toggleUgcOrder() {
        ugcReverseOrder = !ugcReverseOrder
        val view = videoView ?: return
        val ugcSeasonIndex = contentAdapter.currentList.indexOfFirst {
            it is VideoDetailContentAdapter.Row.UgcSeason
        }
        if (ugcSeasonIndex >= 0) {
            val videos = ugcEpisodes.mapNotNull { episode ->
                episode.arc?.let { arc ->
                    arc.copy(
                        pic = episode.displayCover.ifBlank { arc.coverUrl },
                        aid = episode.displayAid,
                        bvid = episode.displayBvid,
                        cid = episode.cid.takeIf { it > 0 } ?: arc.cid
                    )
                }
            }
            val ordered = if (ugcReverseOrder) videos.reversed() else videos
            val rawTitle = view.ugcSeason?.title.orEmpty()
            val seasonTitle = if (rawTitle.isNotBlank()) "合集（$rawTitle）：" else "合集"
            val newList = contentAdapter.currentList.toMutableList()
            newList[ugcSeasonIndex] = VideoDetailContentAdapter.Row.UgcSeason(
                title = seasonTitle,
                items = ordered,
                isReverse = ugcReverseOrder,
                currentAid = view.aid
            )
            contentAdapter.submitList(newList)
        }
    }

    private fun onTagClicked(tag: Tag) {
        val effectiveTagId = tag.tagId.takeIf { it > 0 } ?: tag.id
        if (tag.tagType == "new_channel" && effectiveTagId > 0) {
            openInHostContainer(
                ChannelVideoFragment.newInstance(tag.tagName, effectiveTagId)
            )
        } else {
            openInHostContainer(
                com.tutu.myblbl.feature.search.SearchNewFragment.newInstance(tag.tagName)
            )
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
            currentVideoId = currentBvid
        ).apply {
            show()
        }
    }

    private fun showDescriptionDialog(description: CharSequence) {
        val dialogBinding = com.tutu.myblbl.databinding.DialogDescriptionBinding
            .inflate(LayoutInflater.from(requireContext()))
        dialogBinding.textDescription.text = description
        androidx.appcompat.app.AppCompatDialog(requireContext(), R.style.DialogTheme).apply {
            setContentView(dialogBinding.root)
            setCanceledOnTouchOutside(true)
            setOnCancelListener { /* back key or touch outside */ }
            show()
        }
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

    private fun toggleLike() {
        if (!sessionGateway.isLoggedIn()) {
            toast(getString(R.string.need_sign_in))
            return
        }
        val currentAid = videoView?.aid ?: videoModel?.aid ?: return
        val currentBvid = videoView?.bvid ?: videoModel?.bvid
        lifecycleScope.launch {
            runCatching {
                videoRepository.like(currentAid, currentBvid, if (isLiked) 2 else 1)
            }.onSuccess { response ->
                if (response.isSuccess) {
                    isLiked = !isLiked
                    updateActionButtonsDirectly()
                    toast(if (isLiked) getString(R.string.liked_) else getString(R.string.like))
                } else {
                    toast(response.message)
                }
            }.onFailure {
                toast(it.message ?: "操作失败")
            }
        }
    }

    private fun giveCoin() {
        if (!sessionGateway.isLoggedIn()) {
            toast(getString(R.string.need_sign_in))
            return
        }
        if (isCoined) {
            toast(getString(R.string.give_coin_))
            return
        }
        val currentAid = videoView?.aid ?: videoModel?.aid ?: return
        val currentBvid = videoView?.bvid ?: videoModel?.bvid
        val coinCount = appSettings.getCachedString("give_coin_number")?.toIntOrNull()?.coerceIn(1, 2) ?: 2
        lifecycleScope.launch {
            runCatching {
                videoRepository.giveCoin(currentAid, currentBvid, multiply = coinCount)
            }.onSuccess { response ->
                if (response.isSuccess) {
                    isCoined = true
                    updateActionButtonsDirectly()
                    toast("投币成功")
                } else {
                    toast(response.message)
                }
            }.onFailure {
                toast(it.message ?: "操作失败")
            }
        }
    }

    private fun toggleFavorite() {
        if (!sessionGateway.isLoggedIn()) {
            toast(getString(R.string.need_sign_in))
            return
        }
        val currentAid = videoView?.aid ?: videoModel?.aid ?: return
        lifecycleScope.launch {
            val currentUserMid = sessionGateway.getUserInfo()?.mid?.takeIf { it > 0L }
                ?: videoView?.owner?.mid ?: videoModel?.owner?.mid ?: 0L
            if (currentUserMid <= 0L) {
                toast("收藏夹信息未加载完成")
                return@launch
            }
            val folderResult = favoriteRepository.getFavoriteFolders(currentUserMid)
            val folders = folderResult.getOrNull()?.data?.list.orEmpty()
            val defaultFolder = folders.firstOrNull()
            if (defaultFolder == null) {
                toast("暂无可用收藏夹")
                return@launch
            }
            val folderId = defaultFolder.id.toString()
            val result = if (isFavorited) {
                favoriteRepository.removeFavorite(currentAid, folderId)
            } else {
                favoriteRepository.addFavorite(currentAid, folderId)
            }
            result.onSuccess { response ->
                if (response.isSuccess) {
                    isFavorited = !isFavorited
                    updateActionButtonsDirectly()
                    toast(if (isFavorited) getString(R.string.collection_) else "取消收藏")
                } else {
                    toast(response.errorMessage)
                }
            }.onFailure { toast(it.message ?: "操作失败") }
        }
    }

    private fun tripleAction() {
        if (!sessionGateway.isLoggedIn()) {
            toast(getString(R.string.need_sign_in))
            return
        }
        val currentAid = videoView?.aid ?: videoModel?.aid ?: return
        val currentBvid = videoView?.bvid ?: videoModel?.bvid
        if (!isLiked) toggleLike()
        if (!isCoined) {
            val coinCount = appSettings.getCachedString("give_coin_number")?.toIntOrNull()?.coerceIn(1, 2) ?: 2
            lifecycleScope.launch {
                runCatching { videoRepository.giveCoin(currentAid, currentBvid, multiply = coinCount) }
                    .onSuccess { response ->
                        if (response.isSuccess) {
                            isCoined = true
                            updateActionButtonsDirectly()
                        } else {
                            toast(response.message)
                        }
                    }.onFailure { toast(it.message ?: "投币失败") }
            }
        }
        if (!isFavorited) toggleFavorite()
    }

    private fun updateActionButtonsDirectly() {
        val holder = binding.recyclerView.findViewHolderForAdapterPosition(0)
            as? VideoDetailContentAdapter.VideoDetailHeadViewHolder ?: return
        holder.updateActionButtons(isLiked, isCoined, isFavorited)
    }

    private fun toast(message: String) {
        if (isAdded) {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showError(message: String?) {
        toast(message ?: "加载失败")
    }

    private fun openInHostContainer(
        fragment: androidx.fragment.app.Fragment,
        addToBackStack: Boolean = true
    ) {
        mainActivity?.openInHostContainer(fragment, addToBackStack)
    }

    override fun onDestroyView() {
        actionDialog?.dismiss()
        actionDialog = null
        ownerDetailDialog?.dismiss()
        ownerDetailDialog = null
        super.onDestroyView()
        _binding = null
        mainActivity = null
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
            stat = stat,
            isUpowerExclusive = isUpowerExclusive,
            isChargingArc = isChargingArc,
            elecArcType = elecArcType,
            elecArcBadge = elecArcBadge,
            privilegeType = privilegeType
        )
    }

    private fun dumpProgressBars(root: View?, label: String) {
        if (root == null) return
        val activityRoot = activity?.findViewById<View>(android.R.id.content) ?: root
        val stack = ArrayDeque<View>()
        stack.add(activityRoot)
        var found = 0
        while (stack.isNotEmpty()) {
            val v = stack.removeFirst()
            if (v is ProgressBar) {
                found++
                AppLog.d(TAG, "$label: ProgressBar id=${v.id}, vis=${v.visibility}, w=${v.width}, h=${v.height}, parent=${v.parent?.javaClass?.simpleName}")
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    stack.add(v.getChildAt(i))
                }
            }
        }
        if (found == 0) {
            AppLog.d(TAG, "$label: NO ProgressBar found in view hierarchy")
        }
    }
}

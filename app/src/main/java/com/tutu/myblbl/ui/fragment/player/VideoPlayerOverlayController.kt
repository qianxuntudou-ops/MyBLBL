package com.tutu.myblbl.ui.fragment.player

import android.view.View
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.R
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.model.video.detail.VideoDetailModel
import com.tutu.myblbl.ui.adapter.VideoAdapter
import com.tutu.myblbl.ui.dialog.OwnerDetailDialog
import com.tutu.myblbl.ui.dialog.PlayerActionDialog
import com.tutu.myblbl.ui.dialog.VideoInfoDialog
import com.tutu.myblbl.ui.fragment.detail.UserSpaceFragment
import com.tutu.myblbl.ui.view.WrapContentGridLayoutManager
import com.tutu.myblbl.ui.view.player.MyPlayerView

@UnstableApi
class VideoPlayerOverlayController(
    private val fragment: Fragment,
    private val playerView: MyPlayerView,
    private val overlayCoordinator: PlayerOverlayCoordinator,
    private val sessionCoordinator: PlayerSessionCoordinator,
    private val playerProvider: () -> androidx.media3.common.Player?,
    private val latestVideoInfoProvider: () -> VideoDetailModel?,
    private val relatedAdapter: VideoAdapter,
    private val viewRelated: View,
    private val recyclerViewRelated: RecyclerView,
    private val textMoreTitle: TextView,
    private val onPlayEpisode: (Int) -> Unit,
    private val onPlayRelatedVideo: (VideoModel, List<VideoModel>) -> Unit,
    private val onOpenFragmentFromHost: (Fragment, String) -> Unit,
    private val onHideNextPreview: () -> Unit,
    private val isViewActive: () -> Boolean
) {

    fun showChooseEpisodeDialog() {
        val episodes = sessionCoordinator.getEpisodes()
        val selectedEpisodeIndex = sessionCoordinator.getSelectedEpisodeIndex()
        if (episodes.isEmpty()) {
            Toast.makeText(fragment.requireContext(), "当前暂无可选分集", Toast.LENGTH_SHORT).show()
            return
        }
        overlayCoordinator.rememberFocusRestoreTarget(PlayerOverlayCoordinator.FocusTarget.EPISODE_BUTTON)
        keepControllerVisibleForOverlay()
        playerProvider()?.pause()

        val dialog = AppCompatDialog(fragment.requireContext(), R.style.DialogTheme)
        dialog.setContentView(R.layout.dialog_choose_episode)
        dialog.setCanceledOnTouchOutside(true)

        val recyclerView = dialog.findViewById<RecyclerView>(R.id.recyclerView)
        val titleView = dialog.findViewById<TextView>(R.id.top_title)
        val moreInfoButton = dialog.findViewById<TextView>(R.id.button_more_info)
        val catalogSource = episodes.firstOrNull()?.source ?: VideoPlayerViewModel.EpisodeCatalogSource.PAGES
        val currentVideoInfo = resolveCurrentVideoInfo()

        titleView?.text = fragment.getString(R.string.choose_episode)
        val showMoreInfo = catalogSource == VideoPlayerViewModel.EpisodeCatalogSource.PAGES && currentVideoInfo != null
        moreInfoButton?.isVisible = showMoreInfo
        moreInfoButton?.setOnClickListener(
            if (showMoreInfo) {
                View.OnClickListener {
                    showVideoInfoDialog(
                        restorePlayerFocus = false,
                        onDismiss = {
                            moreInfoButton.post { moreInfoButton.requestFocus() }
                        }
                    )
                }
            } else {
                null
            }
        )

        val episodeDialogAdapter = PlayerEpisodePanelAdapter { index ->
            dialog.dismiss()
            onHideNextPreview()
            onPlayEpisode(index)
        }.apply {
            submitList(episodes)
            setSelectedIndex(selectedEpisodeIndex)
        }

        recyclerView?.apply {
            layoutManager = WrapContentGridLayoutManager(fragment.requireContext(), 2)
            adapter = episodeDialogAdapter
            post {
                if (selectedEpisodeIndex in episodes.indices) {
                    scrollToPosition(selectedEpisodeIndex)
                    focusEpisodeItem(this, selectedEpisodeIndex)
                } else {
                    requestFocus()
                }
            }
        }

        dialog.setOnDismissListener {
            if (isViewActive()) {
                restoreControllerAfterOverlay()
            }
        }
        dialog.show()
    }

    fun showRelatedPanel() {
        overlayCoordinator.onRelatedPanelShown()
        keepControllerVisibleForOverlay()
        textMoreTitle.text = fragment.getString(R.string.related_video)
        recyclerViewRelated.layoutManager =
            GridLayoutManager(fragment.requireContext(), 1, RecyclerView.HORIZONTAL, false)
        recyclerViewRelated.adapter = relatedAdapter
        if (viewRelated.isVisible) {
            recyclerViewRelated.requestFocus()
            return
        }
        viewRelated.clearAnimation()
        viewRelated.visibility = View.VISIBLE
        AnimationUtils.loadAnimation(fragment.requireContext(), R.anim.slide_up).apply {
            setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(animation: Animation?) = Unit

                override fun onAnimationEnd(animation: Animation?) {
                    recyclerViewRelated.post { recyclerViewRelated.requestFocus() }
                }

                override fun onAnimationRepeat(animation: Animation?) = Unit
            })
            viewRelated.startAnimation(this)
        }
    }

    fun hideContentPanel(restoreFocus: Boolean = true) {
        overlayCoordinator.onRelatedPanelHidden()
        if (!viewRelated.isVisible) {
            if (restoreFocus && isViewActive()) {
                restoreControllerAfterOverlay()
            }
            return
        }
        viewRelated.clearAnimation()
        AnimationUtils.loadAnimation(fragment.requireContext(), R.anim.slide_down).apply {
            setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(animation: Animation?) = Unit

                override fun onAnimationEnd(animation: Animation?) {
                    viewRelated.visibility = View.GONE
                    if (restoreFocus && isViewActive()) {
                        restoreControllerAfterOverlay()
                    }
                }

                override fun onAnimationRepeat(animation: Animation?) = Unit
            })
            viewRelated.startAnimation(this)
        }
    }

    fun showVideoInfoDialog(
        restorePlayerFocus: Boolean = true,
        onDismiss: (() -> Unit)? = null
    ) {
        val video = resolveCurrentVideoInfo()
        if (video == null) {
            Toast.makeText(fragment.requireContext(), "当前视频信息未加载完成", Toast.LENGTH_SHORT).show()
            return
        }
        if (restorePlayerFocus) {
            keepControllerVisibleForOverlay()
            playerView.rememberCurrentFocusTarget()
        }
        VideoInfoDialog(
            context = fragment.requireContext(),
            coverUrl = video.coverUrl,
            title = video.title,
            description = video.desc
        ).apply {
            setOnDismissListener {
                if (restorePlayerFocus && isViewActive()) {
                    playerView.showController()
                    playerView.restoreRememberedFocus()
                    playerView.resetControllerHideCallbacks()
                }
                onDismiss?.invoke()
            }
            show()
        }
    }

    private fun resolveCurrentVideoInfo(): VideoModel? {
        val detailView = latestVideoInfoProvider()?.view
        val selectedEpisode = sessionCoordinator.getSelectedEpisode()
        val currentVideo = sessionCoordinator.getCurrentVideo()
        if (detailView == null && currentVideo == null) {
            return null
        }
        return VideoModel(
            aid = currentVideo?.aid ?: detailView?.aid ?: selectedEpisode?.aid ?: 0L,
            bvid = currentVideo?.bvid
                ?.takeIf { it.isNotBlank() }
                ?: detailView?.bvid
                    ?.takeIf { it.isNotBlank() }
                ?: selectedEpisode?.bvid.orEmpty(),
            cid = detailView?.cid ?: selectedEpisode?.cid ?: currentVideo?.cid ?: 0L,
            title = detailView?.title
                ?.takeIf { it.isNotBlank() }
                ?: currentVideo?.title
                    ?.takeIf { it.isNotBlank() }
                ?: selectedEpisode?.title.orEmpty(),
            pic = currentVideo?.coverUrl
                ?.takeIf { it.isNotBlank() }
                ?: detailView?.pic
                    ?.takeIf { it.isNotBlank() }
                ?: selectedEpisode?.cover
                    ?.takeIf { it.isNotBlank() }
                ?: currentVideo?.pic.orEmpty(),
            cover = currentVideo?.cover
                ?.takeIf { it.isNotBlank() }
                ?: detailView?.pic
                    ?.takeIf { it.isNotBlank() }
                ?: selectedEpisode?.cover.orEmpty(),
            desc = detailView?.desc
                ?.takeIf { it.isNotBlank() }
                ?: currentVideo?.desc
                    ?.takeIf { it.isNotBlank() }
                ?: "",
            pubDate = currentVideo?.pubDate ?: detailView?.pubDate ?: 0L,
            createTime = currentVideo?.createTime ?: detailView?.createTime ?: 0L,
            owner = currentVideo?.owner ?: detailView?.owner,
            stat = currentVideo?.stat ?: detailView?.stat
        )
    }

    fun showPlayerActionDialog() {
        val view = latestVideoInfoProvider()?.view
        val aid = view?.aid ?: 0L
        val bvid = view?.bvid.orEmpty()
        if (aid <= 0L && bvid.isBlank()) {
            Toast.makeText(fragment.requireContext(), "当前视频信息未加载完成", Toast.LENGTH_SHORT).show()
            return
        }
        overlayCoordinator.rememberFocusRestoreTarget(PlayerOverlayCoordinator.FocusTarget.MORE_BUTTON)
        keepControllerVisibleForOverlay()
        PlayerActionDialog(
            context = fragment.requireContext(),
            aid = aid,
            bvid = bvid,
            ownerMid = view?.owner?.mid ?: 0L
        ).apply {
            setOnDismissListener {
                if (isViewActive()) {
                    restoreControllerAfterOverlay()
                }
            }
            show()
        }
    }

    fun showOwnerDetailDialog() {
        val view = latestVideoInfoProvider()?.view
        val owner = view?.owner
        if (owner == null || owner.mid <= 0L) {
            Toast.makeText(fragment.requireContext(), "UP主信息未加载完成", Toast.LENGTH_SHORT).show()
            return
        }
        overlayCoordinator.rememberFocusRestoreTarget(PlayerOverlayCoordinator.FocusTarget.OWNER_BUTTON)
        keepControllerVisibleForOverlay()
        OwnerDetailDialog(
            context = fragment.requireContext(),
            owner = owner,
            onOpenSpace = { mid ->
                onOpenFragmentFromHost(UserSpaceFragment.newInstance(mid), "user_space")
            },
            onPlayVideo = { video, playQueue ->
                hideContentPanel(restoreFocus = false)
                onHideNextPreview()
                onPlayRelatedVideo(video, playQueue)
            },
            currentAid = view.aid,
            currentBvid = view.bvid
        ).apply {
            setOnDismissListener {
                if (isViewActive()) {
                    restoreControllerAfterOverlay()
                }
            }
            show()
        }
    }

    private fun keepControllerVisibleForOverlay() {
        playerView.showController()
        playerView.removeControllerHideCallbacks()
    }

    private fun restoreControllerAfterOverlay() {
        playerView.showController()
        overlayCoordinator.restoreFocus(playerView)
        playerView.resetControllerHideCallbacks()
    }

    private fun focusEpisodeItem(recyclerView: RecyclerView, position: Int, retries: Int = 6) {
        val holder = recyclerView.findViewHolderForAdapterPosition(position)
        if (holder?.itemView != null) {
            holder.itemView.requestFocus()
            return
        }
        if (retries > 0) {
            recyclerView.post { focusEpisodeItem(recyclerView, position, retries - 1) }
        } else {
            recyclerView.requestFocus()
        }
    }
}

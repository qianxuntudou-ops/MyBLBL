package com.tutu.myblbl.core.navigation

import android.content.Context
import android.content.ContextWrapper
import com.tutu.myblbl.model.video.HistoryVideoModel
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.ui.activity.LivePlayerActivity
import com.tutu.myblbl.ui.activity.MainActivity
import com.tutu.myblbl.ui.activity.PlayerActivity
import com.tutu.myblbl.feature.detail.VideoDetailFragment
import com.tutu.myblbl.core.common.ext.isOpenDetailFirstEnabled
import com.tutu.myblbl.core.common.log.AppLog

object VideoRouteNavigator {

    private const val TAG = "VideoRouteNav"

    fun openVideo(
        context: Context,
        video: VideoModel,
        playQueue: List<VideoModel> = emptyList(),
        seekPositionMs: Long = 0L,
        startEpisodeIndex: Int = -1,
        forcePlayer: Boolean = false
    ) {
        AppLog.d(TAG, "openVideo: bvid=${video.bvid}, aid=${video.aid}, title=${video.title}, forcePlayer=$forcePlayer")
        if (!forcePlayer && shouldOpenVideoDetailFirst(context, video)) {
            val hostActivity = findMainActivityHost(context)
            AppLog.d(TAG, "openVideo: detailFirst=true, host=$hostActivity")
            if (hostActivity != null) {
                hostActivity.openInHostContainer(VideoDetailFragment.newInstance(video))
                return
            }
        }
        AppLog.d(TAG, "openVideo: starting PlayerActivity")
        PlayerActivity.start(
            context = context,
            video = video,
            seekPositionMs = seekPositionMs.takeIf { it > 0L }
                ?: video.historyProgress * 1000L,
            playQueue = playQueue,
            startEpisodeIndex = startEpisodeIndex
        )
    }

    fun openHistory(
        context: Context,
        historyVideo: HistoryVideoModel,
        playQueue: List<VideoModel> = emptyList(),
        forcePlayer: Boolean = false
    ) {
        resolveLiveRoomId(historyVideo)?.let { roomId ->
            LivePlayerActivity.start(context, roomId)
            return
        }
        openVideo(
            context = context,
            video = historyVideo.toVideoModel(),
            playQueue = playQueue,
            forcePlayer = forcePlayer
        )
    }

    private fun shouldOpenVideoDetailFirst(context: Context, video: VideoModel): Boolean {
        if (!context.isOpenDetailFirstEnabled()) {
            return false
        }
        if (video.isLive || video.roomId > 0L || video.historyBusiness == "live") {
            return false
        }
        return video.aid > 0L || video.bvid.isNotBlank()
    }

    private fun resolveLiveRoomId(historyVideo: HistoryVideoModel): Long? {
        val history = historyVideo.history ?: return null
        if (history.business != "live") {
            return null
        }
        return history.oid.takeIf { it > 0L }
    }

    private fun findMainActivityHost(context: Context): MainActivity? {
        var current: Context? = context
        while (current != null) {
            when (current) {
                is MainActivity -> {
                    if (!current.isFinishing && !current.isDestroyed) {
                        return current
                    }
                    return null
                }
                is ContextWrapper -> current = current.baseContext
                else -> return null
            }
        }
        return null
    }
}

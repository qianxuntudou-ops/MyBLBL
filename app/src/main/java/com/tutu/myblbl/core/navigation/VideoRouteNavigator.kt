package com.tutu.myblbl.core.navigation

import android.content.Context
import android.content.ContextWrapper
import com.tutu.myblbl.model.video.HistoryVideoModel
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.ui.activity.LivePlayerActivity
import com.tutu.myblbl.ui.activity.MainActivity
import com.tutu.myblbl.ui.activity.PlayerActivity
import com.tutu.myblbl.feature.detail.VideoDetailFragment
import com.tutu.myblbl.feature.series.SeriesDetailFragment
import com.tutu.myblbl.core.common.ext.isOpenDetailFirstEnabled
import java.net.URL

object VideoRouteNavigator {

    fun openVideo(
        context: Context,
        video: VideoModel,
        playQueue: List<VideoModel> = emptyList(),
        seekPositionMs: Long = 0L,
        startEpisodeIndex: Int = -1,
        forcePlayer: Boolean = false
    ) {
        if (!forcePlayer && shouldOpenVideoDetailFirst(context, video)) {
            val hostActivity = findMainActivityHost(context)
            if (hostActivity != null) {
                hostActivity.openInHostContainer(VideoDetailFragment.newInstance(video))
                return
            }
        }
        PlayerActivity.start(
            context = context,
            video = video,
            seekPositionMs = seekPositionMs,
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
        resolveSeriesRoute(historyVideo)?.let { route ->
            findMainActivityHost(context)?.openInHostContainer(
                SeriesDetailFragment.newInstance(route.seasonId, route.epId)
            )
            return
        }
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

    private fun resolveSeriesRoute(historyVideo: HistoryVideoModel): SeriesRoute? {
        val rawUri = historyVideo.uri.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val path = URL(rawUri).path.orEmpty()
            val segment = path.substringAfterLast('/', "")
            when {
                segment.startsWith("ss") -> {
                    val seasonId = segment.removePrefix("ss").takeWhile(Char::isDigit).toLongOrNull()
                    seasonId?.let { SeriesRoute(seasonId = it, epId = historyVideo.history?.epid ?: 0L) }
                }
                segment.startsWith("ep") -> {
                    val epId = segment.removePrefix("ep").takeWhile(Char::isDigit).toLongOrNull() ?: 0L
                    if (epId > 0L) {
                        SeriesRoute(
                            seasonId = historyVideo.toVideoModel().playbackSeasonId,
                            epId = epId
                        )
                    } else {
                        null
                    }
                }
                else -> null
            }
        }.getOrNull()
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

    private data class SeriesRoute(
        val seasonId: Long,
        val epId: Long
    )
}

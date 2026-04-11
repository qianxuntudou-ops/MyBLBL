package com.tutu.myblbl.feature.player

import android.os.SystemClock
import com.tutu.myblbl.model.video.VideoModel
import java.io.Serializable

data class PlayerLaunchContext(
    val aid: Long = 0L,
    val bvid: String = "",
    val cid: Long = 0L,
    val epId: Long = 0L,
    val seasonId: Long = 0L,
    val seekPositionMs: Long = 0L,
    val requestElapsedMs: Long = 0L,
    val initialVideo: VideoModel? = null,
    val playQueue: ArrayList<VideoModel> = arrayListOf(),
    val startEpisodeIndex: Int = -1
) : Serializable {

    companion object {
        fun create(
            aid: Long = 0L,
            bvid: String = "",
            cid: Long = 0L,
            epId: Long = 0L,
            seasonId: Long = 0L,
            seekPositionMs: Long = 0L,
            initialVideo: VideoModel? = null,
            playQueue: List<VideoModel> = emptyList(),
            startEpisodeIndex: Int = -1
        ): PlayerLaunchContext {
            val resolvedVideo = initialVideo ?: buildFallbackVideo(
                aid = aid,
                bvid = bvid,
                cid = cid,
                epId = epId,
                seasonId = seasonId
            )
            val resolvedAid = aid.takeIf { it > 0L } ?: resolvedVideo?.aid ?: 0L
            val resolvedBvid = bvid.takeIf { it.isNotBlank() } ?: resolvedVideo?.bvid.orEmpty()
            val resolvedCid = cid.takeIf { it > 0L } ?: resolvedVideo?.cid ?: 0L
            val resolvedEpId = epId.takeIf { it > 0L } ?: resolvedVideo?.epid ?: 0L
            val resolvedSeasonId = seasonId.takeIf { it > 0L }
                ?: resolvedVideo?.playbackSeasonId
                ?: 0L
            return PlayerLaunchContext(
                aid = resolvedAid,
                bvid = resolvedBvid,
                cid = resolvedCid,
                epId = resolvedEpId,
                seasonId = resolvedSeasonId,
                seekPositionMs = seekPositionMs.coerceAtLeast(0L),
                requestElapsedMs = SystemClock.elapsedRealtime(),
                initialVideo = resolvedVideo,
                playQueue = ArrayList(playQueue),
                startEpisodeIndex = startEpisodeIndex
            )
        }

        private fun buildFallbackVideo(
            aid: Long,
            bvid: String,
            cid: Long,
            epId: Long,
            seasonId: Long
        ): VideoModel? {
            if (aid <= 0L && bvid.isBlank() && cid <= 0L && epId <= 0L && seasonId <= 0L) {
                return null
            }
            return VideoModel(
                aid = aid,
                bvid = bvid,
                cid = cid,
                epid = epId,
                sid = seasonId
            )
        }
    }
}

package com.tutu.myblbl.ui.activity

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.fragment.app.commit
import androidx.media3.common.util.UnstableApi
import com.tutu.myblbl.R
import com.tutu.myblbl.ui.base.BaseActivity
import com.tutu.myblbl.databinding.ActivityPlayerBinding
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.feature.player.PlayerLaunchContext
import com.tutu.myblbl.feature.player.VideoPlayerFragment
import com.tutu.myblbl.utils.AppLog
import java.io.Serializable

@OptIn(UnstableApi::class)
class PlayerActivity : BaseActivity<ActivityPlayerBinding>() {

    companion object {
        private const val TAG = "PlayerActivity"
        private const val EXTRA_LAUNCH_CONTEXT = "player_launch_context"

        fun start(
            context: Context,
            aid: Long = 0L,
            bvid: String = "",
            cid: Long = 0L,
            epId: Long = 0L,
            seasonId: Long = 0L,
            seekPositionMs: Long = 0L,
            initialVideo: VideoModel? = null,
            playQueue: List<VideoModel> = emptyList(),
            startEpisodeIndex: Int = -1
        ) {
            val launchContext = PlayerLaunchContext.create(
                aid = aid,
                bvid = bvid,
                cid = cid,
                epId = epId,
                seasonId = seasonId,
                seekPositionMs = seekPositionMs,
                initialVideo = initialVideo,
                playQueue = playQueue,
                startEpisodeIndex = startEpisodeIndex
            )
            findMainActivityHost(context)?.let { hostActivity ->
                hostActivity.openVideoPlayer(launchContext = launchContext)
                return
            }
            context.startActivity(Intent(context, PlayerActivity::class.java).apply {
                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                putExtra(EXTRA_LAUNCH_CONTEXT, launchContext)
            })
        }

        fun start(
            context: Context,
            video: VideoModel,
            seekPositionMs: Long = 0L,
            playQueue: List<VideoModel> = emptyList(),
            startEpisodeIndex: Int = -1
        ) {
            start(
                context = context,
                aid = video.aid,
                bvid = video.bvid,
                cid = video.cid,
                epId = video.epid,
                seasonId = video.playbackSeasonId,
                seekPositionMs = seekPositionMs,
                initialVideo = video,
                playQueue = playQueue,
                startEpisodeIndex = startEpisodeIndex
            )
        }

        fun start(
            context: Context,
            bvid: String,
            cid: Long,
            epId: Long = 0L,
            seasonId: Long = 0L,
            seekPositionMs: Long = 0L
        ) {
            start(
                context = context,
                bvid = bvid,
                cid = cid,
                epId = epId,
                seasonId = seasonId,
                seekPositionMs = seekPositionMs
            )
        }

        fun buildPlayQueue(items: List<VideoModel>, current: VideoModel): ArrayList<VideoModel> {
            if (items.isEmpty()) {
                return arrayListOf()
            }
            val currentIndex = items.indexOfFirst { isSameVideo(it, current) }
            val queueSource = when {
                currentIndex >= 0 && currentIndex < items.lastIndex -> items.subList(currentIndex + 1, items.size)
                currentIndex >= 0 -> emptyList()
                else -> items.filterNot { isSameVideo(it, current) }
            }
            return ArrayList(queueSource.filter(::isPlayableVideo))
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

        private fun isPlayableVideo(video: VideoModel): Boolean {
            return video.aid > 0L ||
                video.bvid.isNotBlank() ||
                video.epid > 0L ||
                video.playbackSeasonId > 0L
        }

        private fun isSameVideo(left: VideoModel, right: VideoModel): Boolean {
            return when {
                left.epid > 0L && right.epid > 0L -> left.epid == right.epid
                left.bvid.isNotBlank() && right.bvid.isNotBlank() -> left.bvid == right.bvid
                left.aid > 0L && right.aid > 0L -> left.aid == right.aid
                left.cid > 0L && right.cid > 0L -> left.cid == right.cid
                else -> left.title == right.title && left.coverUrl == right.coverUrl
            }
        }
    }

    override fun getViewBinding(): ActivityPlayerBinding =
        ActivityPlayerBinding.inflate(layoutInflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launchContext = intent.serializableExtraCompat<PlayerLaunchContext>(EXTRA_LAUNCH_CONTEXT)
            ?: PlayerLaunchContext.create()
        AppLog.d(
            TAG,
            "onCreate: aid=${launchContext.aid}, bvid=${launchContext.bvid}, cid=${launchContext.cid}, epId=${launchContext.epId}, seasonId=${launchContext.seasonId}, seek=${launchContext.seekPositionMs}, queue=${launchContext.playQueue.size}, startEpisode=${launchContext.startEpisodeIndex}"
        )
        if (
            launchContext.aid <= 0L &&
            launchContext.bvid.isBlank() &&
            launchContext.epId <= 0L &&
            launchContext.seasonId <= 0L
        ) {
            return finish()
        }

        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                replace(
                    R.id.player_container,
                    VideoPlayerFragment.newInstance(launchContext)
                )
            }
        }
    }

    private inline fun <reified T : Serializable> Intent.serializableExtraCompat(key: String): T? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getSerializableExtra(key, T::class.java)
        } else {
            @Suppress("DEPRECATION")
            getSerializableExtra(key) as? T
        }
    }
}

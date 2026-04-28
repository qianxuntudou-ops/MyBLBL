package com.tutu.myblbl.feature.player

import android.animation.AnimatorListenerAdapter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.tutu.myblbl.feature.player.extractor.FlvHevcExtractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.flv.FlvExtractor
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.databinding.FragmentLivePlayerBinding
import com.tutu.myblbl.core.ui.navigation.navigateBackFromUi
import com.tutu.myblbl.core.ui.system.ViewUtils
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

@UnstableApi
class LivePlayerFragment : Fragment() {

    companion object {
        private const val TAG = "LivePlayerFragment"
        const val ARG_ROOM_ID = "room_id"

        fun newInstance(roomId: Long): LivePlayerFragment {
            return LivePlayerFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_ROOM_ID, roomId)
                }
            }
        }
    }

    private var _binding: FragmentLivePlayerBinding? = null
    private val binding get() = _binding!!

    private val viewModel: LivePlayerViewModel by viewModel()
    private val okHttpClient: OkHttpClient by inject()

    private var player: ExoPlayer? = null
    private var roomId: Long = 0L

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            AppLog.e(TAG, "live player error: ${error.message}", error)
            binding.textError.text = error.message ?: "直播播放失败"
            binding.textError.visibility = View.VISIBLE
            syncPlaybackEnvironment()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            syncPlaybackEnvironment()
            when (playbackState) {
                Player.STATE_BUFFERING -> binding.playerView.pauseDanmaku()
                Player.STATE_READY -> {
                    if (player?.playWhenReady == true) binding.playerView.resumeDanmaku()
                }
                Player.STATE_ENDED -> binding.playerView.stopDanmaku()
                Player.STATE_IDLE -> binding.playerView.pauseDanmaku()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            syncPlaybackEnvironment()
            if (isPlaying) {
                binding.playerView.resumeDanmaku()
            } else if (player?.playbackState != Player.STATE_BUFFERING) {
                binding.playerView.pauseDanmaku()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLivePlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        arguments?.let { args ->
            roomId = args.getLong(ARG_ROOM_ID, -1L)
        }
        val textClock = binding.textClock
        textClock.alpha = 0f
        textClock.translationY = -textClock.height.toFloat().coerceAtLeast(200f)
        binding.playerView.setControllerVisibilityListener(object : com.tutu.myblbl.feature.player.view.MyPlayerView.ControllerVisibilityListener {
            override fun onVisibilityChanged(visibility: Int) {
                val animate = textClock.animate().setDuration(250L)
                when (visibility) {
                    View.VISIBLE -> animate.translationY(0f).alpha(1f).setListener(null)
                    View.INVISIBLE -> animate.translationY(-textClock.height.toFloat()).alpha(0f).setListener(null)
                    View.GONE -> {
                        textClock.translationY = -textClock.height.toFloat()
                        textClock.alpha = 0f
                    }
                }
            }
        })
        setupPlayer()
        setupObservers()
        if (roomId > 0) {
            viewModel.loadLiveStream(roomId)
        }
    }

    private fun setupPlayer() {
        val liveHeaders = mapOf(
            "Origin" to "https://live.bilibili.com/",
            "Referer" to if (roomId > 0L) {
                "https://live.bilibili.com/$roomId"
            } else {
                "https://live.bilibili.com"
            }
        )
        val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"
            )
            .setDefaultRequestProperties(liveHeaders)
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(5_000, 50_000, 600, 1200)
            .build()
        val extractorsFactory = ExtractorsFactory {
            DefaultExtractorsFactory().createExtractors().map { extractor ->
                if (extractor is FlvExtractor) FlvHevcExtractor() else extractor
            }.toTypedArray()
        }
        player = ExoPlayer.Builder(requireContext())
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory))
            .setLoadControl(loadControl)
            .build()
            .also {
                PlayerPlaybackPolicy.apply(it)
                PlayerAudioNormalizer.attach(it)
                it.addListener(playerListener)
            }
        binding.playerView.setPlayer(player)
        binding.playerView.setControllerAutoShow(true)
        binding.playerView.showHideFfRe(false)
        binding.playerView.showHideNextPrevious(false)
        binding.playerView.showHideEpisodeButton(false)
        binding.playerView.showHideActionButton(false)
        binding.playerView.showHideRelatedButton(false)
        binding.playerView.showHideRepeatButton(false)
        binding.playerView.showHideSubtitleButton(false)
        binding.playerView.showHideDmSwitchButton(true)
        binding.playerView.setShowHideOwnerInfo(false)
        binding.playerView.showSettingButton(false)
        binding.playerView.showHideLiveSettingButton(false)
        binding.playerView.showHideTimeBar(false)
        binding.playerView.showHideTimeText(false)
        binding.playerView.showHideRefreshButton(true)
        binding.playerView.setOnVideoSettingChangeListener(object : com.tutu.myblbl.feature.player.view.OnVideoSettingChangeListener {
            override fun onLiveSettings() {
                binding.playerView.showLiveQualityMenu()
            }

            override fun onRefresh() {
                if (roomId > 0) {
                    Toast.makeText(requireContext(), "正在刷新", Toast.LENGTH_SHORT).show()
                    viewModel.refreshLiveStream(roomId)
                }
            }

            override fun onClose() {
                navigateBackFromUi()
            }
        })
        binding.playerView.setOnPlayerSettingChange(object : com.tutu.myblbl.feature.player.view.OnPlayerSettingChange {
            override fun onVideoQualityChange(quality: com.tutu.myblbl.model.video.quality.VideoQuality) {}
            override fun onAudioQualityChange(quality: com.tutu.myblbl.model.video.quality.AudioQuality) {}
            override fun onPlaybackSpeedChange(speed: Float) {}
            override fun onSubtitleChange(position: Int) {}
            override fun onVideoCodecChange(codec: com.tutu.myblbl.model.video.quality.VideoCodecEnum) {}
            override fun onAspectRatioChange(ratio: Int) {}
            override fun onLiveQualityChange(qn: Int) {
                viewModel.switchQuality(qn)
            }
        })

        // 初始化直播弹幕：启用弹幕，启动引擎（不等待数据）
        binding.playerView.setDanmakuEnabled(true)
        binding.playerView.startLiveDanmaku()

        syncPlaybackEnvironment()
    }

    private fun syncPlaybackEnvironment() {
        val currentPlayer = player
        val keepScreenOn = currentPlayer != null &&
            currentPlayer.playWhenReady &&
            currentPlayer.playbackState != Player.STATE_IDLE &&
            currentPlayer.playbackState != Player.STATE_ENDED
        activity?.let { ViewUtils.keepScreenOn(it, keepScreenOn) }
    }

    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.playUrl.collect { url ->
                url?.let {
                    binding.textError.visibility = View.GONE
                    val mediaItem = MediaItem.Builder()
                        .setUri(it)
                        .setMimeType(resolveMimeType(it))
                        .build()
                    player?.setMediaItem(mediaItem)
                    player?.prepare()
                    player?.playWhenReady = true
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isLoading.collect { isLoading ->
                binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.error.collect { error ->
                error?.let {
                    binding.textError.text = it
                    binding.textError.visibility = View.VISIBLE
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.qualities.collect { qualities ->
                binding.playerView.setLiveQualities(qualities)
                binding.playerView.showHideLiveSettingButton(qualities.isNotEmpty())
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.selectedQuality.collect { quality ->
                quality?.let {
                    binding.playerView.selectLiveQuality(it.qn)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.roomTitle.collect { title ->
                binding.playerView.setTitle(title)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.anchorName.collect { name ->
                binding.playerView.setSubTitle(name)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.liveDuration.collect { duration ->
                binding.playerView.setLiveDuration(if (duration.isNotEmpty()) "直播中：$duration" else "")
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.refreshEvent.collect { msg ->
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            }
        }

        // 实时弹幕：用引擎当前时间作为 position 注入
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.liveDanmaku.collect { dmModel ->
                binding.playerView.addLiveDanmaku(dmModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        player?.playWhenReady = true
        syncPlaybackEnvironment()
    }

    override fun onStop() {
        super.onStop()
        player?.playWhenReady = false
        binding.playerView.pauseDanmaku()
        syncPlaybackEnvironment()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        player?.removeListener(playerListener)
        binding.playerView.stopDanmaku()
        binding.playerView.destroy()
        PlayerAudioNormalizer.release(player)
        player?.release()
        activity?.let { ViewUtils.keepScreenOn(it, false) }
        player = null
        _binding = null
    }

    private fun resolveMimeType(url: String): String? {
        val normalized = url.substringBefore('?').lowercase()
        return when {
            normalized.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            normalized.endsWith(".flv") -> MimeTypes.VIDEO_FLV
            else -> null
        }
    }
}

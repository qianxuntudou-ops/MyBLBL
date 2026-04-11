package com.tutu.myblbl.ui.fragment.player

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.tutu.myblbl.databinding.FragmentLivePlayerBinding
import com.tutu.myblbl.network.NetworkManager
import com.tutu.myblbl.utils.AppLog
import com.tutu.myblbl.utils.ViewUtils
import kotlinx.coroutines.launch
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
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            syncPlaybackEnvironment()
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
        val dataSourceFactory = OkHttpDataSource.Factory(NetworkManager.getOkHttpClient())
            .setUserAgent(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"
            )
            .setDefaultRequestProperties(liveHeaders)
        player = ExoPlayer.Builder(requireContext())
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .also {
                PlayerPlaybackPolicy.apply(it)
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
        binding.playerView.showHideDmSwitchButton(false)
        binding.playerView.setShowHideOwnerInfo(false)
        binding.playerView.showSettingButton(false)
        binding.playerView.showHideLiveSettingButton(false)
        binding.playerView.setOnVideoSettingChangeListener(object : com.tutu.myblbl.ui.view.player.OnVideoSettingChangeListener {
            override fun onLiveQualityChange(qn: Int) {
                viewModel.switchQuality(qn)
            }

            override fun onClose() {
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }
        })
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
                    AppLog.d(TAG, "apply live play url: ${it.take(160)}")
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
    }

    override fun onStart() {
        super.onStart()
        player?.playWhenReady = true
        syncPlaybackEnvironment()
    }

    override fun onStop() {
        super.onStop()
        player?.playWhenReady = false
        syncPlaybackEnvironment()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        player?.removeListener(playerListener)
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

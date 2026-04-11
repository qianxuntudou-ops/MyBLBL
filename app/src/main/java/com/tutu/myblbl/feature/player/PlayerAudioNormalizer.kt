package com.tutu.myblbl.feature.player

import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.tutu.myblbl.utils.AppLog
import java.util.WeakHashMap

@UnstableApi
internal object PlayerAudioNormalizer {

    private val sessions = WeakHashMap<ExoPlayer, AudioNormalizerSession>()

    @Synchronized
    fun attach(player: ExoPlayer) {
        val existing = sessions[player]
        if (existing != null) {
            return
        }
        val session = AudioNormalizerSession(player)
        sessions[player] = session
        session.attach()
    }

    @Synchronized
    fun release(player: ExoPlayer?) {
        if (player == null) {
            return
        }
        sessions.remove(player)?.release()
    }

    private class AudioNormalizerSession(
        private val player: ExoPlayer
    ) : Player.Listener {

        companion object {
            private const val TAG = "PlayerAudioNormalizer"
            private const val AUDIO_SESSION_ID_UNSET = -1

            private const val INPUT_GAIN_DB = 3.0f

            private const val COMPRESSOR_ATTACK_MS = 3.0f
            private const val COMPRESSOR_RELEASE_MS = 80.0f
            private const val COMPRESSOR_RATIO = 3.5f
            private const val COMPRESSOR_THRESHOLD_DB = -22.0f
            private const val COMPRESSOR_KNEE_WIDTH_DB = 8.0f
            private const val COMPRESSOR_POST_GAIN_DB = 0.0f

            private const val LIMITER_ATTACK_MS = 1.0f
            private const val LIMITER_RELEASE_MS = 60.0f
            private const val LIMITER_RATIO = 10.0f
            private const val LIMITER_THRESHOLD_DB = -2.0f
            private const val LIMITER_POST_GAIN_DB = 0.0f

            private const val FALLBACK_TARGET_GAIN_MB = 320
        }

        private var currentAudioSessionId = AUDIO_SESSION_ID_UNSET
        private var dynamicsProcessing: DynamicsProcessing? = null
        private var loudnessEnhancer: LoudnessEnhancer? = null
        private var released = false

        fun attach() {
            player.addListener(this)
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            if (released || audioSessionId == currentAudioSessionId) {
                return
            }
            currentAudioSessionId = audioSessionId
            releaseEffects()
            if (audioSessionId <= 0) {
                AppLog.d(TAG, "skip invalid audio session: $audioSessionId")
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && attachDynamicsProcessing(audioSessionId)) {
                return
            }
            attachLoudnessEnhancer(audioSessionId)
        }

        fun release() {
            if (released) {
                return
            }
            released = true
            player.removeListener(this)
            currentAudioSessionId = AUDIO_SESSION_ID_UNSET
            releaseEffects()
        }

        private fun attachDynamicsProcessing(audioSessionId: Int): Boolean {
            return runCatching {
                DynamicsProcessing(audioSessionId).also { effect ->
                    configureDynamicsProcessing(effect)
                    effect.enabled = true
                    dynamicsProcessing = effect
                }
                AppLog.d(TAG, "attached DynamicsProcessing to session=$audioSessionId")
            }.onFailure { error ->
                AppLog.w(TAG, "failed to attach DynamicsProcessing for session=$audioSessionId", error)
            }.isSuccess
        }

        private fun configureDynamicsProcessing(effect: DynamicsProcessing) {
            effect.setInputGainAllChannelsTo(INPUT_GAIN_DB)
            repeat(effect.channelCount) { channelIndex ->
                val mbc = effect.getMbcByChannelIndex(channelIndex)
                if (mbc.isInUse) {
                    mbc.setEnabled(true)
                    repeat(mbc.bandCount) { bandIndex ->
                        val band = DynamicsProcessing.MbcBand(mbc.getBand(bandIndex))
                        band.setEnabled(true)
                        band.setAttackTime(COMPRESSOR_ATTACK_MS)
                        band.setReleaseTime(COMPRESSOR_RELEASE_MS)
                        band.setRatio(COMPRESSOR_RATIO)
                        band.setThreshold(COMPRESSOR_THRESHOLD_DB)
                        band.setKneeWidth(COMPRESSOR_KNEE_WIDTH_DB)
                        band.setPostGain(COMPRESSOR_POST_GAIN_DB)
                        mbc.setBand(bandIndex, band)
                    }
                    effect.setMbcByChannelIndex(channelIndex, mbc)
                }

                val limiter = effect.getLimiterByChannelIndex(channelIndex)
                if (limiter.isInUse) {
                    limiter.setEnabled(true)
                    limiter.setAttackTime(LIMITER_ATTACK_MS)
                    limiter.setReleaseTime(LIMITER_RELEASE_MS)
                    limiter.setRatio(LIMITER_RATIO)
                    limiter.setThreshold(LIMITER_THRESHOLD_DB)
                    limiter.setPostGain(LIMITER_POST_GAIN_DB)
                    effect.setLimiterByChannelIndex(channelIndex, limiter)
                }
            }
        }

        private fun attachLoudnessEnhancer(audioSessionId: Int): Boolean {
            return runCatching {
                LoudnessEnhancer(audioSessionId).also { effect ->
                    effect.setTargetGain(FALLBACK_TARGET_GAIN_MB)
                    effect.enabled = true
                    loudnessEnhancer = effect
                }
                AppLog.d(TAG, "attached LoudnessEnhancer to session=$audioSessionId")
            }.onFailure { error ->
                AppLog.w(TAG, "failed to attach LoudnessEnhancer for session=$audioSessionId", error)
            }.isSuccess
        }

        private fun releaseEffects() {
            dynamicsProcessing?.runCatching {
                release()
            }?.onFailure { error ->
                AppLog.w(TAG, "failed to release DynamicsProcessing", error)
            }
            dynamicsProcessing = null

            loudnessEnhancer?.runCatching {
                release()
            }?.onFailure { error ->
                AppLog.w(TAG, "failed to release LoudnessEnhancer", error)
            }
            loudnessEnhancer = null
        }
    }
}

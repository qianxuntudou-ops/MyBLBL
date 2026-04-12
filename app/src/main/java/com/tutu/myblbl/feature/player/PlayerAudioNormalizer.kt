package com.tutu.myblbl.feature.player

import android.os.Build
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.tutu.myblbl.core.common.log.AppLog
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
        private var dynamicsProcessing: Any? = null
        private var loudnessEnhancer: Any? = null
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
            if (Build.VERSION.SDK_INT >= 28 && attachDynamicsProcessing(audioSessionId)) {
                return
            }
            if (Build.VERSION.SDK_INT >= 26) {
                attachLoudnessEnhancer(audioSessionId)
            }
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
                val effectClass = Class.forName("android.media.audiofx.DynamicsProcessing")
                val effect = effectClass.getConstructor(Int::class.javaPrimitiveType)
                    .newInstance(audioSessionId)
                configureDynamicsProcessing(effect)
                effectClass.getMethod("setEnabled", Boolean::class.javaPrimitiveType)
                    .invoke(effect, true)
                dynamicsProcessing = effect
                AppLog.d(TAG, "attached DynamicsProcessing to session=$audioSessionId")
            }.onFailure { error ->
                AppLog.w(TAG, "failed to attach DynamicsProcessing for session=$audioSessionId", error)
            }.isSuccess
        }

        private fun configureDynamicsProcessing(effect: Any) {
            val effectClass = effect.javaClass
            effectClass.getMethod("setInputGainAllChannelsTo", Float::class.javaPrimitiveType)
                .invoke(effect, INPUT_GAIN_DB)
            val channelCount = effectClass.getMethod("getChannelCount").invoke(effect) as Int
            repeat(channelCount) { channelIndex ->
                val mbc = effectClass.getMethod("getMbcByChannelIndex", Int::class.javaPrimitiveType)
                    .invoke(effect, channelIndex)
                val mbcClass = mbc.javaClass
                if (mbcClass.getMethod("isInUse").invoke(mbc) as Boolean) {
                    mbcClass.getMethod("setEnabled", Boolean::class.javaPrimitiveType)
                        .invoke(mbc, true)
                    val bandCount = mbcClass.getMethod("getBandCount").invoke(mbc) as Int
                    repeat(bandCount) { bandIndex ->
                        val band = mbcClass.getMethod("getBand", Int::class.javaPrimitiveType)
                            .invoke(mbc, bandIndex)
                        val bandClass = Class.forName("android.media.audiofx.DynamicsProcessing\$MbcBand")
                        val bandCtor = bandClass.getConstructor(bandClass)
                        val newBand = bandCtor.newInstance(band)
                        newBand.javaClass.getMethod("setEnabled", Boolean::class.javaPrimitiveType)
                            .invoke(newBand, true)
                        newBand.javaClass.getMethod("setAttackTime", Float::class.javaPrimitiveType)
                            .invoke(newBand, COMPRESSOR_ATTACK_MS)
                        newBand.javaClass.getMethod("setReleaseTime", Float::class.javaPrimitiveType)
                            .invoke(newBand, COMPRESSOR_RELEASE_MS)
                        newBand.javaClass.getMethod("setRatio", Float::class.javaPrimitiveType)
                            .invoke(newBand, COMPRESSOR_RATIO)
                        newBand.javaClass.getMethod("setThreshold", Float::class.javaPrimitiveType)
                            .invoke(newBand, COMPRESSOR_THRESHOLD_DB)
                        newBand.javaClass.getMethod("setKneeWidth", Float::class.javaPrimitiveType)
                            .invoke(newBand, COMPRESSOR_KNEE_WIDTH_DB)
                        newBand.javaClass.getMethod("setPostGain", Float::class.javaPrimitiveType)
                            .invoke(newBand, COMPRESSOR_POST_GAIN_DB)
                        mbcClass.getMethod("setBand", Int::class.javaPrimitiveType, newBand.javaClass)
                            .invoke(mbc, bandIndex, newBand)
                    }
                    effectClass.getMethod("setMbcByChannelIndex", Int::class.javaPrimitiveType, mbcClass)
                        .invoke(effect, channelIndex, mbc)
                }

                val limiter = effectClass.getMethod("getLimiterByChannelIndex", Int::class.javaPrimitiveType)
                    .invoke(effect, channelIndex)
                val limiterClass = limiter.javaClass
                if (limiterClass.getMethod("isInUse").invoke(limiter) as Boolean) {
                    limiterClass.getMethod("setEnabled", Boolean::class.javaPrimitiveType)
                        .invoke(limiter, true)
                    limiterClass.getMethod("setAttackTime", Float::class.javaPrimitiveType)
                        .invoke(limiter, LIMITER_ATTACK_MS)
                    limiterClass.getMethod("setReleaseTime", Float::class.javaPrimitiveType)
                        .invoke(limiter, LIMITER_RELEASE_MS)
                    limiterClass.getMethod("setRatio", Float::class.javaPrimitiveType)
                        .invoke(limiter, LIMITER_RATIO)
                    limiterClass.getMethod("setThreshold", Float::class.javaPrimitiveType)
                        .invoke(limiter, LIMITER_THRESHOLD_DB)
                    limiterClass.getMethod("setPostGain", Float::class.javaPrimitiveType)
                        .invoke(limiter, LIMITER_POST_GAIN_DB)
                    effectClass.getMethod("setLimiterByChannelIndex", Int::class.javaPrimitiveType, limiterClass)
                        .invoke(effect, channelIndex, limiter)
                }
            }
        }

        private fun attachLoudnessEnhancer(audioSessionId: Int): Boolean {
            return runCatching {
                val effectClass = Class.forName("android.media.audiofx.LoudnessEnhancer")
                val effect = effectClass.getConstructor(Int::class.javaPrimitiveType)
                    .newInstance(audioSessionId)
                effectClass.getMethod("setTargetGain", Int::class.javaPrimitiveType)
                    .invoke(effect, FALLBACK_TARGET_GAIN_MB)
                effectClass.getMethod("setEnabled", Boolean::class.javaPrimitiveType)
                    .invoke(effect, true)
                loudnessEnhancer = effect
                AppLog.d(TAG, "attached LoudnessEnhancer to session=$audioSessionId")
            }.onFailure { error ->
                AppLog.w(TAG, "failed to attach LoudnessEnhancer for session=$audioSessionId", error)
            }.isSuccess
        }

        private fun releaseEffects() {
            dynamicsProcessing?.runCatching {
                javaClass.getMethod("release").invoke(this)
            }?.onFailure { error ->
                AppLog.w(TAG, "failed to release DynamicsProcessing", error)
            }
            dynamicsProcessing = null

            loudnessEnhancer?.runCatching {
                javaClass.getMethod("release").invoke(this)
            }?.onFailure { error ->
                AppLog.w(TAG, "failed to release LoudnessEnhancer", error)
            }
            loudnessEnhancer = null
        }
    }
}

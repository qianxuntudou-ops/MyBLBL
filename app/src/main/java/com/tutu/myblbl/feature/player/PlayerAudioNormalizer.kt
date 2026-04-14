package com.tutu.myblbl.feature.player

import android.os.Build
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.tutu.myblbl.core.common.log.AppLog
import java.lang.reflect.Constructor
import java.lang.reflect.Method
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

    private object ReflectionCache {
        val dpClass: Class<*>? = runCatching {
            Class.forName("android.media.audiofx.DynamicsProcessing")
        }.getOrNull()

        val dpCtor: Constructor<*>? = dpClass?.getConstructor(Int::class.javaPrimitiveType)
        val dpSetEnabled: Method? = dpClass?.getMethod("setEnabled", Boolean::class.javaPrimitiveType)
        val dpSetInputGain: Method? = dpClass?.getMethod("setInputGainAllChannelsTo", Float::class.javaPrimitiveType)
        val dpGetChannelCount: Method? = dpClass?.getMethod("getChannelCount")
        val dpGetMbc: Method? = dpClass?.getMethod("getMbcByChannelIndex", Int::class.javaPrimitiveType)
        val dpGetLimiter: Method? = dpClass?.getMethod("getLimiterByChannelIndex", Int::class.javaPrimitiveType)

        val mbcBandClass: Class<*>? = runCatching {
            Class.forName("android.media.audiofx.DynamicsProcessing\$MbcBand")
        }.getOrNull()
        val mbcBandCtor: Constructor<*>? = mbcBandClass?.getConstructor(mbcBandClass)
        val mbcIsInUse: Method? = dpGetMbc?.returnType?.getMethod("isInUse")
        val mbcSetEnabled: Method? = dpGetMbc?.returnType?.getMethod("setEnabled", Boolean::class.javaPrimitiveType)
        val mbcGetBandCount: Method? = dpGetMbc?.returnType?.getMethod("getBandCount")
        val mbcGetBand: Method? = dpGetMbc?.returnType?.getMethod("getBand", Int::class.javaPrimitiveType)
        val mbcSetBand: Method? = dpGetMbc?.returnType?.getMethod("setBand", Int::class.javaPrimitiveType, mbcBandClass)
        val bandSetEnabled: Method? = mbcBandClass?.getMethod("setEnabled", Boolean::class.javaPrimitiveType)
        val bandSetAttackTime: Method? = mbcBandClass?.getMethod("setAttackTime", Float::class.javaPrimitiveType)
        val bandSetReleaseTime: Method? = mbcBandClass?.getMethod("setReleaseTime", Float::class.javaPrimitiveType)
        val bandSetRatio: Method? = mbcBandClass?.getMethod("setRatio", Float::class.javaPrimitiveType)
        val bandSetThreshold: Method? = mbcBandClass?.getMethod("setThreshold", Float::class.javaPrimitiveType)
        val bandSetKneeWidth: Method? = mbcBandClass?.getMethod("setKneeWidth", Float::class.javaPrimitiveType)
        val bandSetPostGain: Method? = mbcBandClass?.getMethod("setPostGain", Float::class.javaPrimitiveType)

        val limiterClass: Class<*>? = dpGetLimiter?.returnType
        val limiterIsInUse: Method? = limiterClass?.getMethod("isInUse")
        val limiterSetEnabled: Method? = limiterClass?.getMethod("setEnabled", Boolean::class.javaPrimitiveType)
        val limiterSetAttackTime: Method? = limiterClass?.getMethod("setAttackTime", Float::class.javaPrimitiveType)
        val limiterSetReleaseTime: Method? = limiterClass?.getMethod("setReleaseTime", Float::class.javaPrimitiveType)
        val limiterSetRatio: Method? = limiterClass?.getMethod("setRatio", Float::class.javaPrimitiveType)
        val limiterSetThreshold: Method? = limiterClass?.getMethod("setThreshold", Float::class.javaPrimitiveType)
        val limiterSetPostGain: Method? = limiterClass?.getMethod("setPostGain", Float::class.javaPrimitiveType)

        val dpSetMbc: Method? = runCatching {
            dpClass?.getMethod("setMbcByChannelIndex", Int::class.javaPrimitiveType, dpGetMbc?.returnType)
        }.getOrNull()
        val dpSetLimiter: Method? = runCatching {
            dpClass?.getMethod("setLimiterByChannelIndex", Int::class.javaPrimitiveType, dpGetLimiter?.returnType)
        }.getOrNull()

        val leClass: Class<*>? = runCatching {
            Class.forName("android.media.audiofx.LoudnessEnhancer")
        }.getOrNull()
        val leCtor: Constructor<*>? = leClass?.getConstructor(Int::class.javaPrimitiveType)
        val leSetTargetGain: Method? = leClass?.getMethod("setTargetGain", Int::class.javaPrimitiveType)
        val leSetEnabled: Method? = leClass?.getMethod("setEnabled", Boolean::class.javaPrimitiveType)

        val dpRelease: Method? = dpClass?.getMethod("release")
        val leRelease: Method? = leClass?.getMethod("release")
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
            val c = ReflectionCache
            val ctor = c.dpCtor ?: return false
            return runCatching {
                val effect = ctor.newInstance(audioSessionId)
                configureDynamicsProcessing(effect)
                c.dpSetEnabled?.invoke(effect, true)
                dynamicsProcessing = effect
                AppLog.d(TAG, "attached DynamicsProcessing to session=$audioSessionId")
            }.onFailure { error ->
                AppLog.w(TAG, "failed to attach DynamicsProcessing for session=$audioSessionId", error)
            }.isSuccess
        }

        private fun configureDynamicsProcessing(effect: Any) {
            val c = ReflectionCache
            c.dpSetInputGain?.invoke(effect, INPUT_GAIN_DB)
            val channelCount = (c.dpGetChannelCount?.invoke(effect) as? Int) ?: return
            repeat(channelCount) { channelIndex ->
                val mbc = c.dpGetMbc?.invoke(effect, channelIndex) ?: return@repeat
                if ((c.mbcIsInUse?.invoke(mbc) as? Boolean) == true) {
                    c.mbcSetEnabled?.invoke(mbc, true)
                    val bandCount = (c.mbcGetBandCount?.invoke(mbc) as? Int) ?: 0
                    repeat(bandCount) { bandIndex ->
                        val band = c.mbcGetBand?.invoke(mbc, bandIndex) ?: return@repeat
                        val newBand = c.mbcBandCtor?.newInstance(band) ?: return@repeat
                        c.bandSetEnabled?.invoke(newBand, true)
                        c.bandSetAttackTime?.invoke(newBand, COMPRESSOR_ATTACK_MS)
                        c.bandSetReleaseTime?.invoke(newBand, COMPRESSOR_RELEASE_MS)
                        c.bandSetRatio?.invoke(newBand, COMPRESSOR_RATIO)
                        c.bandSetThreshold?.invoke(newBand, COMPRESSOR_THRESHOLD_DB)
                        c.bandSetKneeWidth?.invoke(newBand, COMPRESSOR_KNEE_WIDTH_DB)
                        c.bandSetPostGain?.invoke(newBand, COMPRESSOR_POST_GAIN_DB)
                        c.mbcSetBand?.invoke(mbc, bandIndex, newBand)
                    }
                    c.dpSetMbc?.invoke(effect, channelIndex, mbc)
                }

                val limiter = c.dpGetLimiter?.invoke(effect, channelIndex) ?: return@repeat
                if ((c.limiterIsInUse?.invoke(limiter) as? Boolean) == true) {
                    c.limiterSetEnabled?.invoke(limiter, true)
                    c.limiterSetAttackTime?.invoke(limiter, LIMITER_ATTACK_MS)
                    c.limiterSetReleaseTime?.invoke(limiter, LIMITER_RELEASE_MS)
                    c.limiterSetRatio?.invoke(limiter, LIMITER_RATIO)
                    c.limiterSetThreshold?.invoke(limiter, LIMITER_THRESHOLD_DB)
                    c.limiterSetPostGain?.invoke(limiter, LIMITER_POST_GAIN_DB)
                    c.dpSetLimiter?.invoke(effect, channelIndex, limiter)
                }
            }
        }

        private fun attachLoudnessEnhancer(audioSessionId: Int): Boolean {
            val c = ReflectionCache
            val ctor = c.leCtor ?: return false
            return runCatching {
                val effect = ctor.newInstance(audioSessionId)
                c.leSetTargetGain?.invoke(effect, FALLBACK_TARGET_GAIN_MB)
                c.leSetEnabled?.invoke(effect, true)
                loudnessEnhancer = effect
                AppLog.d(TAG, "attached LoudnessEnhancer to session=$audioSessionId")
            }.onFailure { error ->
                AppLog.w(TAG, "failed to attach LoudnessEnhancer for session=$audioSessionId", error)
            }.isSuccess
        }

        private fun releaseEffects() {
            dynamicsProcessing?.runCatching {
                ReflectionCache.dpRelease?.invoke(this)
            }?.onFailure { error ->
                AppLog.w(TAG, "failed to release DynamicsProcessing", error)
            }
            dynamicsProcessing = null

            loudnessEnhancer?.runCatching {
                ReflectionCache.leRelease?.invoke(this)
            }?.onFailure { error ->
                AppLog.w(TAG, "failed to release LoudnessEnhancer", error)
            }
            loudnessEnhancer = null
        }
    }
}

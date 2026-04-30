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
        val dpGetLimiter: Method? = dpClass?.getMethod("getLimiterByChannelIndex", Int::class.javaPrimitiveType)

        val limiterClass: Class<*>? = dpGetLimiter?.returnType
        val limiterIsInUse: Method? = limiterClass?.getMethod("isInUse")
        val limiterSetEnabled: Method? = limiterClass?.getMethod("setEnabled", Boolean::class.javaPrimitiveType)
        val limiterSetAttackTime: Method? = limiterClass?.getMethod("setAttackTime", Float::class.javaPrimitiveType)
        val limiterSetReleaseTime: Method? = limiterClass?.getMethod("setReleaseTime", Float::class.javaPrimitiveType)
        val limiterSetRatio: Method? = limiterClass?.getMethod("setRatio", Float::class.javaPrimitiveType)
        val limiterSetThreshold: Method? = limiterClass?.getMethod("setThreshold", Float::class.javaPrimitiveType)
        val limiterSetPostGain: Method? = limiterClass?.getMethod("setPostGain", Float::class.javaPrimitiveType)

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

            // 温和提升安静内容
            private const val INPUT_GAIN_DB = 2.0f

            // 峰值兜底限制器：仅防止削波，不追求响度最大化
            private const val LIMITER_ATTACK_MS = 10.0f
            private const val LIMITER_RELEASE_MS = 200.0f
            private const val LIMITER_RATIO = 4.0f
            private const val LIMITER_THRESHOLD_DB = -8.0f
            private const val LIMITER_POST_GAIN_DB = 0.0f

            // 降级方案：温和增益
            private const val FALLBACK_TARGET_GAIN_MB = 180
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
                configureLimiter(effect)
                c.dpSetEnabled?.invoke(effect, true)
                dynamicsProcessing = effect
            }.onFailure { error ->
                AppLog.w(TAG, "failed to attach DynamicsProcessing for session=$audioSessionId", error)
            }.isSuccess
        }

        private fun configureLimiter(effect: Any) {
            val c = ReflectionCache
            c.dpSetInputGain?.invoke(effect, INPUT_GAIN_DB)
            val channelCount = (c.dpGetChannelCount?.invoke(effect) as? Int) ?: return
            repeat(channelCount) { channelIndex ->
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

package com.tutu.myblbl.core.common.media

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import com.tutu.myblbl.model.video.quality.VideoCodecEnum

object VideoCodecSupport {

    private val codecPriorityOrder = listOf(
        VideoCodecEnum.AV1,
        VideoCodecEnum.HEVC,
        VideoCodecEnum.AVC
    )

    fun getHardwareSupportedCodecs(): Set<VideoCodecEnum> {
        return runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .asSequence()
                .filter { !it.isEncoder }
                .filter(::isHardwareDecoder)
                .flatMap { info ->
                    info.supportedTypes
                        .asSequence()
                        .mapNotNull(::codecFromMimeType)
                }
                .toSet()
        }.getOrDefault(emptySet())
    }

    fun buildSupportSummary(supportedCodecs: Collection<VideoCodecEnum>): String {
        val supported = supportedCodecs.toSet()
        return codecPriorityOrder.joinToString("  ") { codec ->
            if (codec in supported) "✓${codec.name}" else "✗${codec.name}"
        }
    }

    fun orderCandidates(
        availableCodecs: Collection<VideoCodecEnum>,
        preferredCodec: VideoCodecEnum?,
        hardwareSupportedCodecs: Collection<VideoCodecEnum>
    ): List<VideoCodecEnum> {
        val uniqueAvailable = availableCodecs.toSet()
        if (uniqueAvailable.isEmpty()) {
            return emptyList()
        }

        if (preferredCodec != null && preferredCodec in uniqueAvailable) {
            val rest = uniqueAvailable.filterTo(linkedSetOf()) { it != preferredCodec }
            return listOf(preferredCodec) + orderByHardwareThenPriority(rest, hardwareSupportedCodecs)
        }

        return orderByHardwareThenPriority(uniqueAvailable, hardwareSupportedCodecs)
    }

    private fun orderByHardwareThenPriority(
        codecs: Set<VideoCodecEnum>,
        hardwareSupportedCodecs: Collection<VideoCodecEnum>
    ): List<VideoCodecEnum> {
        if (codecs.isEmpty()) return emptyList()

        val hardwareSupported = hardwareSupportedCodecs.toSet()
        val hardwareAvailable = codecs.filterTo(linkedSetOf()) { it in hardwareSupported }
        val softwareFallback = codecs.filterTo(linkedSetOf()) { it !in hardwareAvailable }

        return if (hardwareAvailable.isNotEmpty()) {
            orderWithinTier(hardwareAvailable, null) +
                orderWithinTier(softwareFallback, null)
        } else {
            orderWithinTier(codecs, null)
        }
    }

    private fun orderWithinTier(
        availableCodecs: Collection<VideoCodecEnum>,
        preferredCodec: VideoCodecEnum?
    ): List<VideoCodecEnum> {
        val available = availableCodecs.toSet()
        return buildList {
            preferredCodec
                ?.takeIf { it in available }
                ?.let(::add)
            codecPriorityOrder
                .filter { it in available && it !in this }
                .forEach(::add)
            available
                .filter { it !in this }
                .sortedBy(::codecPriority)
                .forEach(::add)
        }
    }

    private fun codecFromMimeType(mimeType: String): VideoCodecEnum? {
        return when (mimeType.lowercase()) {
            "video/av01" -> VideoCodecEnum.AV1
            "video/hevc" -> VideoCodecEnum.HEVC
            "video/avc" -> VideoCodecEnum.AVC
            else -> null
        }
    }

    private fun isHardwareDecoder(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return info.isHardwareAccelerated && !info.isSoftwareOnly && !info.isAlias
        }

        val codecName = info.name.lowercase()
        return when {
            codecName.startsWith("omx.google.") -> false
            codecName.startsWith("c2.android.") -> false
            codecName.startsWith("c2.google.") -> false
            codecName.contains(".sw.") -> false
            codecName.contains("software") -> false
            codecName.contains("ffmpeg") -> false
            else -> codecName.startsWith("omx.") || codecName.startsWith("c2.")
        }
    }

    private fun codecPriority(codec: VideoCodecEnum): Int {
        return codecPriorityOrder.indexOf(codec).takeIf { it >= 0 } ?: Int.MAX_VALUE
    }
}

package com.tutu.myblbl.core.common.media

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import com.tutu.myblbl.model.video.quality.VideoCodecEnum

object VideoCodecSupport {

    @Volatile
    private var cachedHardwareCodecs: Set<VideoCodecEnum>? = null

    @Volatile
    private var cachedHdrSupported: Boolean? = null

    @Volatile
    private var cachedDolbyVisionSupported: Boolean? = null

    private val codecPriorityOrder = listOf(
        VideoCodecEnum.AV1,
        VideoCodecEnum.HEVC,
        VideoCodecEnum.AVC
    )

    fun getHardwareSupportedCodecs(): Set<VideoCodecEnum> {
        cachedHardwareCodecs?.let { return it }
        val result = runCatching {
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
        cachedHardwareCodecs = result
        return result
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

    fun isHdrSupported(context: Context? = null): Boolean {
        cachedHdrSupported?.let { return it }
        val decoderOk = hasDecoder("video/hevc")
        if (!decoderOk) {
            cachedHdrSupported = false
            return false
        }
        if (context == null) {
            return decoderOk
        }
        val displayOk = hasHdrDisplaySupport(context)
        val result = displayOk
        cachedHdrSupported = result
        return result
    }

    fun isDolbyVisionSupported(context: Context? = null): Boolean {
        cachedDolbyVisionSupported?.let { return it }
        val decoderOk = hasDecoder("video/dolby-vision") ||
            hasDecoder("video/dvhe") ||
            hasDecoder("video/dvav")
        if (!decoderOk) {
            cachedDolbyVisionSupported = false
            return false
        }
        if (context == null) {
            return true
        }
        val result = hasDolbyVisionDisplaySupport(context)
        cachedDolbyVisionSupported = result
        return result
    }

    private fun hasDecoder(mimeType: String): Boolean {
        return runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .any { !it.isEncoder && mimeType.lowercase() in it.supportedTypes.map { t -> t.lowercase() } }
        }.getOrDefault(false)
    }

    @Suppress("DEPRECATION")
    private fun hasHdrDisplaySupport(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val display = getDisplay(context) ?: return false
        val hdrCaps = display.hdrCapabilities ?: return false
        return hdrCaps.supportedHdrTypes.isNotEmpty()
    }

    @Suppress("DEPRECATION")
    private fun hasDolbyVisionDisplaySupport(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val display = getDisplay(context) ?: return false
        val hdrCaps = display.hdrCapabilities ?: return false
        return hdrCaps.supportedHdrTypes.contains(Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION)
    }

    private fun getDisplay(context: Context): Display? {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return null
        return dm.getDisplay(Display.DEFAULT_DISPLAY)
    }
}

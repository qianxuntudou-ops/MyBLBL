package com.tutu.myblbl.feature.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.core.common.media.VideoCodecSupport
import com.tutu.myblbl.model.player.DashAudio
import com.tutu.myblbl.model.player.DashVideo
import com.tutu.myblbl.model.player.PlayInfoModel
import com.tutu.myblbl.model.player.SegmentBase
import com.tutu.myblbl.model.player.SupportFormat
import com.tutu.myblbl.model.video.quality.AudioQuality
import com.tutu.myblbl.model.video.quality.VideoCodecEnum
import com.tutu.myblbl.model.video.quality.VideoQuality
import kotlin.math.roundToLong

@OptIn(UnstableApi::class)
internal class VideoPlayerStreamResolver(
    private val dataSourceFactory: DataSource.Factory,
    private val urlNormalizer: (String) -> String
) {

    companion object {
        private const val TAG = "VideoPlayerStreamResolver"
    }

    // Centralizes stream fallback rules so the ViewModel only coordinates state and side effects.
    data class SelectionSnapshot(
        val qualities: List<VideoQuality>,
        val selectedQualityId: Int?,
        val audios: List<AudioQuality>,
        val selectedAudioId: Int?,
        val codecs: List<VideoCodecEnum>,
        val selectedCodec: VideoCodecEnum?
    )

    data class MediaSourceSelection(
        val mediaSource: MediaSource,
        val availableCodecs: List<VideoCodecEnum>,
        val selectedCodec: VideoCodecEnum?
    )

    data class RepresentationDescriptor(
        val mimeType: String,
        val codecs: String,
        val bandwidth: Long,
        val width: Int = 0,
        val height: Int = 0,
        val frameRate: String = "",
        val startWithSap: Int = 1,
        val segmentBase: SegmentBase? = null
    ) {
        fun hasSegmentBase(): Boolean {
            val base = segmentBase ?: return false
            val initialization = base.initialization.ifBlank { base.range }
            return initialization.isNotBlank() && base.realIndexRange.isNotBlank()
        }
    }

    data class CdnUrls(
        val videoUrls: List<String>,
        val audioUrls: List<String>,
        val videoMimeType: String,
        val audioMimeType: String
    )

    data class CodecRoute(
        val codec: VideoCodecEnum,
        val videoUrls: List<String>,
        val audioUrls: List<String>,
        val videoDescriptor: RepresentationDescriptor,
        val audioDescriptor: RepresentationDescriptor?
    )

    data class StreamFallbackPlan(
        val qualityId: Int,
        val selectedAudioId: Int?,
        val durationMs: Long,
        val minBufferTimeMs: Long,
        val routes: List<CodecRoute>
    )

    fun resolveDashRoutePlan(
        playInfo: PlayInfoModel,
        lockedQualityId: Int,
        selectedAudioId: Int?,
        preferredCodec: VideoCodecEnum?,
        hardwareSupportedCodecs: Collection<VideoCodecEnum>
    ): DashRoutePlan? {
        val dash = playInfo.dash ?: return null
        val videosAtQuality = dash.video.orEmpty()
            .filter { it.id == lockedQualityId }
        if (videosAtQuality.isEmpty()) return null

        val selectedAudio = buildAudioTracks(playInfo)
            .firstOrNull { it.id == selectedAudioId }
            ?: buildAudioTracks(playInfo).maxByOrNull { it.bandwidth }
        val audioUrls = selectedAudio
            ?.let { CdnLatencyProfile.sortUrlsByLatency(buildDistinctUrls(it.realBaseUrl, it.realBackupUrl)) }
            .orEmpty()
        val audioRepresentation = selectedAudio?.let { audio ->
            DashRepresentation(
                id = audio.id,
                mimeType = audio.realMimeType.ifBlank { "audio/mp4" },
                codecs = audio.codecs,
                bandwidth = audio.bandwidth,
                baseUrl = audio.realBaseUrl,
                backupUrls = audio.realBackupUrl.orEmpty(),
                segmentBase = audio.realSegmentBase?.let { sb ->
                    DashSegmentBase(
                        initialization = sb.initialization.ifBlank { sb.range },
                        indexRange = sb.realIndexRange
                    )
                }
            )
        }

        val videosByCodec = videosAtQuality.groupBy { VideoCodecEnum.fromId(it.codecId) }
        val codecOrder = orderCodecs(
            available = videosByCodec.keys,
            preferredCodec = preferredCodec,
            hardwareSupportedCodecs = hardwareSupportedCodecs
        )
        val routes = codecOrder.mapNotNull { codec ->
            val selectedVideo = videosByCodec[codec]
                .orEmpty()
                .maxByOrNull { it.bandwidth }
                ?: return@mapNotNull null
            val videoUrls = CdnLatencyProfile.sortUrlsByLatency(
                buildDistinctUrls(selectedVideo.realBaseUrl, selectedVideo.realBackupUrl)
            )
            if (videoUrls.isEmpty()) return@mapNotNull null
            DashRoute(
                codec = codec,
                videoRepresentation = DashRepresentation(
                    id = selectedVideo.id,
                    mimeType = selectedVideo.realMimeType.ifBlank { "video/mp4" },
                    codecs = selectedVideo.codecs,
                    bandwidth = selectedVideo.bandwidth,
                    width = selectedVideo.width,
                    height = selectedVideo.height,
                    frameRate = selectedVideo.realFrameRate,
                    baseUrl = videoUrls.first(),
                    backupUrls = videoUrls.drop(1),
                    segmentBase = selectedVideo.realSegmentBase?.let { sb ->
                        DashSegmentBase(
                            initialization = sb.initialization.ifBlank { sb.range },
                            indexRange = sb.realIndexRange
                        )
                    }
                ),
                audioRepresentation = audioRepresentation?.let { audioRep ->
                    audioRep.copy(
                        baseUrl = audioUrls.firstOrNull() ?: audioRep.baseUrl,
                        backupUrls = audioUrls.drop(1).ifEmpty { audioRep.backupUrls }
                    )
                },
                videoUrls = videoUrls,
                audioUrls = audioUrls,
                durationMs = resolveDurationMs(playInfo),
                minBufferTimeMs = resolveMinBufferTimeMs(playInfo)
            )
        }
        if (routes.isEmpty()) return null
        return DashRoutePlan(
            qualityId = lockedQualityId,
            selectedAudioId = selectedAudio?.id ?: selectedAudioId,
            routes = routes,
            durationMs = resolveDurationMs(playInfo),
            minBufferTimeMs = resolveMinBufferTimeMs(playInfo)
        )
    }

    fun buildFnval(@Suppress("UNUSED_PARAMETER") qualityId: Int): Int = 4048

    fun buildFourk(qualityId: Int): Int {
        return if (qualityId >= 120) 1 else 0
    }

    fun resolveSelections(
        playInfo: PlayInfoModel,
        preferredQualityId: Int?,
        preferredAudioId: Int?,
        preferredCodec: VideoCodecEnum?,
        hardwareSupportedCodecs: Collection<VideoCodecEnum> = emptySet()
    ): SelectionSnapshot {
        val qualities = buildQualityList(playInfo)
        val resolvedQualityId = resolveQualityId(playInfo, qualities, preferredQualityId)
        val audios = buildAudioList(playInfo)
        val resolvedAudioId = resolveAudioId(audios, preferredAudioId)
        val codecs = buildCodecList(
            playInfo = playInfo,
            qualityId = resolvedQualityId,
            preferredCodec = preferredCodec,
            hardwareSupportedCodecs = hardwareSupportedCodecs
        )
        val resolvedCodec = codecs.firstOrNull()
        return SelectionSnapshot(
            qualities = qualities,
            selectedQualityId = resolvedQualityId,
            audios = audios,
            selectedAudioId = resolvedAudioId,
            codecs = codecs,
            selectedCodec = resolvedCodec
        )
    }

    fun buildQualityList(playInfo: PlayInfoModel): List<VideoQuality> {
        val formatNames = playInfo.supportFormats.orEmpty().associateBy { it.quality }
        val videos = playInfo.dash?.video.orEmpty()
        val qualityOrder = playInfo.acceptQuality.orEmpty()

        val streamQualities = if (videos.isNotEmpty()) {
            videos
                .groupBy { it.id }
                .values
                .mapNotNull { streams ->
                    val sample = streams.maxByOrNull { it.bandwidth } ?: return@mapNotNull null
                    buildQualityModel(
                        qualityId = sample.id,
                        support = formatNames[sample.id],
                        resolution = "${sample.width}x${sample.height}",
                        codecId = sample.codecId,
                        bandwidth = sample.bandwidth,
                        baseUrl = sample.realBaseUrl,
                        backupUrls = sample.realBackupUrl
                    )
                }
        } else {
            playInfo.durl
                ?.firstOrNull()
                ?.takeIf { playInfo.quality > 0 && it.url.isNotBlank() }
                ?.let { currentStream ->
                    listOf(
                        buildQualityModel(
                            qualityId = playInfo.quality,
                            support = formatNames[playInfo.quality],
                            resolution = formatNames[playInfo.quality]?.displayDesc
                                ?.takeIf { it.isNotBlank() }
                                ?: VideoQuality.fromId(playInfo.quality).resolution,
                            bandwidth = currentStream.size,
                            baseUrl = currentStream.url,
                            backupUrls = currentStream.backupUrl
                        )
                    )
                }
                .orEmpty()
        }

        return streamQualities.sortedWith(
            compareBy<VideoQuality> {
                qualityOrder.indexOf(it.id).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE
            }.thenByDescending { it.id }
        )
    }

    fun buildAudioList(playInfo: PlayInfoModel): List<AudioQuality> {
        return buildAudioTracks(playInfo)
            .map { audio ->
                AudioQuality(
                    id = audio.id,
                    name = AudioQuality.fromId(audio.id).name,
                    bandwidth = audio.bandwidth,
                    codecId = audio.codecId,
                    baseUrl = audio.realBaseUrl,
                    backupUrls = audio.realBackupUrl
                )
            }
            .sortedByDescending { it.bandwidth }
    }

    fun buildCodecList(
        playInfo: PlayInfoModel,
        qualityId: Int?,
        preferredCodec: VideoCodecEnum? = null,
        hardwareSupportedCodecs: Collection<VideoCodecEnum> = emptySet()
    ): List<VideoCodecEnum> {
        val videos = playInfo.dash?.video.orEmpty()
        val filteredVideos = videos
            .filter { qualityId == null || it.id == qualityId }
            .ifEmpty { videos }
        val availableCodecs = filteredVideos
            .map { VideoCodecEnum.fromId(it.codecId) }
            .distinct()
        return VideoCodecSupport.orderCandidates(
            availableCodecs = availableCodecs,
            preferredCodec = preferredCodec,
            hardwareSupportedCodecs = hardwareSupportedCodecs
        )
    }

    fun buildMediaSource(
        playInfo: PlayInfoModel,
        selectedQualityId: Int?,
        selectedAudioId: Int?,
        selectedCodec: VideoCodecEnum?
    ): MediaSourceSelection? {
        AppLog.d(TAG, "buildMediaSource: fallback progressive path")
        val dash = playInfo.dash
        if (dash != null && !dash.video.isNullOrEmpty()) {
            val filteredByQuality = dash.video.orEmpty()
                .filter { it.id == selectedQualityId }
                .ifEmpty { dash.video.orEmpty() }
            val availableCodecs = filteredByQuality
                .map { VideoCodecEnum.fromId(it.codecId) }
                .distinct()
            val resolvedCodec = selectedCodec.takeIf { it in availableCodecs } ?: availableCodecs.firstOrNull()
            val selectedVideo = filteredByQuality
                .filter { resolvedCodec == null || it.codecId == resolvedCodec.id }
                .maxByOrNull { it.bandwidth }
                ?: filteredByQuality.maxByOrNull { it.bandwidth }
                ?: return null
            val selectedAudio = buildAudioTracks(playInfo)
                .firstOrNull { it.id == selectedAudioId }
                ?: buildAudioTracks(playInfo).maxByOrNull { it.bandwidth }
            val videoUrls = buildDistinctUrls(
                primaryUrl = selectedVideo.realBaseUrl,
                backupUrls = selectedVideo.realBackupUrl
            )
            val audioUrls = selectedAudio?.let {
                buildDistinctUrls(
                    primaryUrl = it.realBaseUrl,
                    backupUrls = it.realBackupUrl
                )
            }.orEmpty()
            val mediaSource = createMediaSource(
                videoUrls = videoUrls,
                audioUrls = audioUrls,
                videoMimeType = selectedVideo.realMimeType,
                audioMimeType = selectedAudio?.realMimeType.orEmpty()
            )
            return MediaSourceSelection(
                mediaSource = mediaSource,
                availableCodecs = availableCodecs,
                selectedCodec = resolvedCodec
            )
        }

        val durl = playInfo.durl?.firstOrNull()?.url ?: return null
        return MediaSourceSelection(
            mediaSource = createProgressiveSource(listOf(durl), "video/mp4"),
            availableCodecs = emptyList(),
            selectedCodec = null
        )
    }

    fun collectCdnUrls(
        playInfo: PlayInfoModel,
        selectedQualityId: Int?,
        selectedAudioId: Int?,
        selectedCodec: VideoCodecEnum?
    ): CdnUrls? {
        val dash = playInfo.dash
        if (dash == null || dash.video.isNullOrEmpty()) return null

        val filteredByQuality = dash.video.orEmpty()
            .filter { it.id == selectedQualityId }
            .ifEmpty { dash.video.orEmpty() }
        val availableCodecs = filteredByQuality
            .map { VideoCodecEnum.fromId(it.codecId) }
            .distinct()
        val resolvedCodec = selectedCodec.takeIf { it in availableCodecs } ?: availableCodecs.firstOrNull()
        val selectedVideo = filteredByQuality
            .filter { resolvedCodec == null || it.codecId == resolvedCodec.id }
            .maxByOrNull { it.bandwidth }
            ?: filteredByQuality.maxByOrNull { it.bandwidth }
            ?: return null

        val selectedAudio = buildAudioTracks(playInfo)
            .firstOrNull { it.id == selectedAudioId }
            ?: buildAudioTracks(playInfo).maxByOrNull { it.bandwidth }

        val videoUrls = buildList {
            add(selectedVideo.realBaseUrl)
            selectedVideo.realBackupUrl?.let(::addAll)
        }.distinct().map(urlNormalizer)

        val audioUrls = if (selectedAudio != null) {
            buildList {
                add(selectedAudio.realBaseUrl)
                selectedAudio.realBackupUrl?.let(::addAll)
            }.distinct().map(urlNormalizer)
        } else {
            emptyList()
        }

        return CdnUrls(
            videoUrls = videoUrls,
            audioUrls = audioUrls,
            videoMimeType = selectedVideo.realMimeType,
            audioMimeType = selectedAudio?.realMimeType ?: ""
        )
    }

    fun buildMediaSourceForRoute(
        route: CodecRoute,
        videoUrl: String,
        audioUrl: String?,
        availableCodecs: List<VideoCodecEnum>,
        selectedCodec: VideoCodecEnum?,
        durationMs: Long,
        minBufferTimeMs: Long
    ): MediaSourceSelection {
        AppLog.d(TAG, "buildMediaSourceForRoute: fallback progressive path")
        val mediaSource = createMediaSource(
            videoUrls = prioritizeUrl(route.videoUrls, videoUrl),
            audioUrls = prioritizeUrl(route.audioUrls, audioUrl),
            videoMimeType = route.videoDescriptor.mimeType,
            audioMimeType = route.audioDescriptor?.mimeType.orEmpty()
        )
        return MediaSourceSelection(
            mediaSource = mediaSource,
            availableCodecs = availableCodecs,
            selectedCodec = selectedCodec
        )
    }

    fun buildStreamFallbackPlan(
        playInfo: PlayInfoModel,
        lockedQualityId: Int,
        selectedAudioId: Int?,
        preferredCodec: VideoCodecEnum?,
        hardwareSupportedCodecs: Collection<VideoCodecEnum>
    ): StreamFallbackPlan? {
        val dash = playInfo.dash ?: return null
        val videosAtQuality = dash.video.orEmpty()
            .filter { it.id == lockedQualityId }
        if (videosAtQuality.isEmpty()) {
            return null
        }

        val selectedAudio = buildAudioTracks(playInfo)
            .firstOrNull { it.id == selectedAudioId }
            ?: buildAudioTracks(playInfo).maxByOrNull { it.bandwidth }
        val audioUrls = selectedAudio
            ?.let { buildDistinctUrls(it.realBaseUrl, it.realBackupUrl) }
            .orEmpty()

        val videosByCodec = videosAtQuality.groupBy { VideoCodecEnum.fromId(it.codecId) }
        val codecOrder = orderCodecs(
            available = videosByCodec.keys,
            preferredCodec = preferredCodec,
            hardwareSupportedCodecs = hardwareSupportedCodecs
        )
        val routes = codecOrder.mapNotNull { codec ->
            val selectedVideo = videosByCodec[codec]
                .orEmpty()
                .maxByOrNull { it.bandwidth }
                ?: return@mapNotNull null
            val videoUrls = buildDistinctUrls(selectedVideo.realBaseUrl, selectedVideo.realBackupUrl)
            if (videoUrls.isEmpty()) {
                return@mapNotNull null
            }
            CodecRoute(
                codec = codec,
                videoUrls = videoUrls,
                audioUrls = audioUrls,
                videoDescriptor = selectedVideo.toRepresentationDescriptor(),
                audioDescriptor = selectedAudio?.toRepresentationDescriptor()
            )
        }
        if (routes.isEmpty()) {
            return null
        }
        return StreamFallbackPlan(
            qualityId = lockedQualityId,
            selectedAudioId = selectedAudio?.id ?: selectedAudioId,
            durationMs = resolveDurationMs(playInfo),
            minBufferTimeMs = resolveMinBufferTimeMs(playInfo),
            routes = routes
        )
    }

    private fun resolveQualityId(
        playInfo: PlayInfoModel,
        qualities: List<VideoQuality>,
        preferredQualityId: Int?
    ): Int? {
        val availableQualityIds = qualities.map { it.id }
        if (preferredQualityId in availableQualityIds) {
            return preferredQualityId
        }
        return qualities.maxByOrNull { it.id }?.id
            ?: qualities.firstOrNull()?.id
            ?: playInfo.quality
    }

    private fun resolveAudioId(
        audios: List<AudioQuality>,
        preferredAudioId: Int?
    ): Int? {
        val availableAudioIds = audios.map { it.id }
        if (preferredAudioId in availableAudioIds) {
            return preferredAudioId
        }
        return audios.maxByOrNull { it.bandwidth }?.id
            ?: audios.firstOrNull()?.id
    }

    private fun buildAudioTracks(playInfo: PlayInfoModel): List<DashAudio> {
        val dash = playInfo.dash ?: return emptyList()
        return buildList {
            addAll(dash.audio.orEmpty())
            dash.dolby?.audio?.let(::addAll)
            dash.flac?.audio?.let(::add)
        }.distinctBy { it.id }
    }

    private fun orderCodecs(
        available: Collection<VideoCodecEnum>,
        preferredCodec: VideoCodecEnum?,
        hardwareSupportedCodecs: Collection<VideoCodecEnum>
    ): List<VideoCodecEnum> {
        return VideoCodecSupport.orderCandidates(
            availableCodecs = available,
            preferredCodec = preferredCodec,
            hardwareSupportedCodecs = hardwareSupportedCodecs
        )
    }

    private fun buildDistinctUrls(primaryUrl: String, backupUrls: List<String>?): List<String> {
        return buildList {
            if (primaryUrl.isNotBlank()) {
                add(primaryUrl)
            }
            backupUrls
                .orEmpty()
                .filter { it.isNotBlank() }
                .let(::addAll)
        }.distinct().map(urlNormalizer)
    }

    private fun buildQualityModel(
        qualityId: Int,
        support: SupportFormat?,
        resolution: String,
        codecId: Int = 0,
        bandwidth: Long = 0,
        baseUrl: String = "",
        backupUrls: List<String>? = null
    ): VideoQuality {
        val static = VideoQuality.fromId(qualityId)
        val displayName = support?.newDescription?.takeIf(String::isNotBlank)
            ?: support?.displayDesc?.takeIf(String::isNotBlank)
            ?: static.name
        val displayResolution = resolution.ifBlank {
            support?.displayDesc?.takeIf(String::isNotBlank)
                ?: static.resolution
        }
        return VideoQuality(
            id = qualityId,
            name = displayName,
            resolution = displayResolution,
            codecId = codecId,
            bandwidth = bandwidth,
            baseUrl = baseUrl,
            backupUrls = backupUrls
        )
    }

    private fun DashVideo.toRepresentationDescriptor(): RepresentationDescriptor {
        return RepresentationDescriptor(
            mimeType = realMimeType.ifBlank { "video/mp4" },
            codecs = codecs,
            bandwidth = bandwidth,
            width = width,
            height = height,
            frameRate = realFrameRate,
            startWithSap = realStartWithSap.takeIf { it > 0 } ?: 1,
            segmentBase = realSegmentBase
        )
    }

    private fun DashAudio.toRepresentationDescriptor(): RepresentationDescriptor {
        return RepresentationDescriptor(
            mimeType = realMimeType.ifBlank { "audio/mp4" },
            codecs = codecs,
            bandwidth = bandwidth,
            startWithSap = startWithSap.takeIf { it > 0 } ?: startWithSapAlt.takeIf { it > 0 } ?: 1,
            segmentBase = realSegmentBase
        )
    }

    private fun resolveDurationMs(playInfo: PlayInfoModel): Long {
        return playInfo.timeLength.takeIf { it > 0L }
            ?: playInfo.dash?.duration?.takeIf { it > 0L }?.times(1000L)
            ?: 1L
    }

    private fun resolveMinBufferTimeMs(playInfo: PlayInfoModel): Long {
        val dash = playInfo.dash
        val seconds = when {
            dash == null -> 1.5
            dash.minBufferTime > 0.0 -> dash.minBufferTime
            dash.minBufferTimeAlt > 0.0 -> dash.minBufferTimeAlt
            else -> 1.5
        }
        return (seconds * 1000.0).roundToLong().coerceAtLeast(500L)
    }

    private val loadErrorPolicy = object : LoadErrorHandlingPolicy {
        override fun getFallbackSelectionFor(
            options: LoadErrorHandlingPolicy.FallbackOptions,
            errorInfo: LoadErrorHandlingPolicy.LoadErrorInfo
        ): LoadErrorHandlingPolicy.FallbackSelection? = null

        override fun getRetryDelayMsFor(
            errorInfo: LoadErrorHandlingPolicy.LoadErrorInfo
        ): Long = 1000L

        override fun getMinimumLoadableRetryCount(dataType: Int): Int = 2
    }

    private fun createMediaSource(
        videoUrls: List<String>,
        audioUrls: List<String>,
        videoMimeType: String,
        audioMimeType: String
    ): MediaSource {
        val buildStartMs = System.currentTimeMillis()
        val normalizedVideoUrls = videoUrls
            .map(urlNormalizer)
            .filter { it.isNotBlank() }
            .distinct()
        val normalizedAudioUrls = audioUrls
            .map(urlNormalizer)
            .filter { it.isNotBlank() }
            .distinct()
        AppLog.d(
            TAG,
            "createMediaSource: using merged progressive sources, videoCandidates=${normalizedVideoUrls.size}, audioCandidates=${normalizedAudioUrls.size}"
        )
        val source = createProgressivePairSource(
            videoUrls = normalizedVideoUrls,
            audioUrls = normalizedAudioUrls,
            videoMimeType = videoMimeType,
            audioMimeType = audioMimeType
        )
        AppLog.d(TAG, "progressiveMediaSource:build:done cost=${System.currentTimeMillis() - buildStartMs}ms")
        return source
    }

    private fun createProgressivePairSource(
        videoUrls: List<String>,
        audioUrls: List<String>,
        videoMimeType: String,
        audioMimeType: String
    ): MediaSource {
        val videoSource = createProgressiveSource(videoUrls, videoMimeType)
        val audioSource = audioUrls
            .takeIf { it.isNotEmpty() }
            ?.let { createProgressiveSource(it, audioMimeType) }
        return if (audioSource != null) {
            MergingMediaSource(videoSource, audioSource)
        } else {
            videoSource
        }
    }

    private fun createProgressiveSource(urls: List<String>, mimeType: String): MediaSource {
        val primaryUrl = urls.firstOrNull()
            ?: error("No media url candidates available")
        val sourceFactory = createCandidateAwareFactory(urls)
        val mediaItem = MediaItem.Builder()
            .setUri(primaryUrl)
            .setMimeType(mimeType.takeIf { it.isNotBlank() })
            .build()
        return ProgressiveMediaSource.Factory(sourceFactory)
            .setLoadErrorHandlingPolicy(loadErrorPolicy)
            .createMediaSource(mediaItem)
    }

    private fun createCandidateAwareFactory(urls: List<String>): DataSource.Factory {
        val candidates = urls
            .map(urlNormalizer)
            .filter { it.isNotBlank() }
            .distinct()
        if (candidates.size <= 1) {
            return dataSourceFactory
        }
        return VideoPlayerCdnFailoverDataSourceFactory(
            upstreamFactory = dataSourceFactory,
            state = VideoPlayerCdnFailoverState(
                candidates = candidates.map(Uri::parse)
            )
        )
    }

    private fun prioritizeUrl(urls: List<String>, preferredUrl: String?): List<String> {
        val normalizedUrls = urls
            .map(urlNormalizer)
            .filter { it.isNotBlank() }
            .distinct()
        val normalizedPreferred = preferredUrl
            ?.takeIf { it.isNotBlank() }
            ?.let(urlNormalizer)
            ?: return normalizedUrls
        val preferredFirst = normalizedUrls.firstOrNull { it == normalizedPreferred } ?: return normalizedUrls
        return buildList {
            add(preferredFirst)
            normalizedUrls
                .filterNot { it == normalizedPreferred }
                .forEach(::add)
        }
    }
}

package com.tutu.myblbl.feature.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.io.IOException

@OptIn(UnstableApi::class)
class VideoPlayerDashMediaSourceFactory(
    private val context: android.content.Context,
    private val okHttpClient: okhttp3.OkHttpClient
) {

    companion object {
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

    fun createMediaSource(route: DashRoute): MediaSource {
        val startTime = System.currentTimeMillis()

        val videoSource = createProgressiveSource(
            urls = route.videoUrls,
            mimeType = route.videoRepresentation.mimeType
        )

        val audioSource = route.audioRepresentation?.let {
            if (route.audioUrls.isNotEmpty()) {
                createProgressiveSource(
                    urls = route.audioUrls,
                    mimeType = it.mimeType
                )
            } else null
        }

        val mediaSource = if (audioSource != null) {
            MergingMediaSource(true, videoSource, audioSource)
        } else {
            videoSource
        }

        val elapsed = System.currentTimeMillis() - startTime

        return mediaSource
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
            .filter { it.isNotBlank() }
            .distinct()
        if (candidates.size <= 1) {
            return buildHttpDataSourceFactory(candidates.firstOrNull())
        }
        val upstreamFactory = buildHttpDataSourceFactory(candidates.first())
        return VideoPlayerCdnFailoverDataSourceFactory(
            upstreamFactory = upstreamFactory,
            state = VideoPlayerCdnFailoverState(
                candidates = candidates.map(Uri::parse)
            )
        )
    }

    private fun buildHttpDataSourceFactory(defaultUrl: String?): DataSource.Factory {
        val upstreamFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"
            )
            .setDefaultRequestProperties(
                mapOf(
                    "Origin" to "https://www.bilibili.com",
                    "Referer" to "https://www.bilibili.com"
                )
            )
            .setConnectTimeoutMs(5000)
            .setReadTimeoutMs(15000)
            .setAllowCrossProtocolRedirects(true)
        return LoggingDataSourceFactory(upstreamFactory)
    }

    private class LoggingDataSourceFactory(
        private val upstreamFactory: DataSource.Factory
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            return LoggingDataSource(upstreamFactory.createDataSource())
        }
    }

    private class LoggingDataSource(
        private val upstream: DataSource
    ) : DataSource {

        override fun addTransferListener(transferListener: TransferListener) {
            upstream.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val pos = dataSpec.position
            val len = dataSpec.length
            val uri = dataSpec.uri
            val host = uri?.host.orEmpty()
            val path = uri?.path.orEmpty().takeLast(40)
            val openStartMs = System.currentTimeMillis()
            return try {
                val remaining = upstream.open(dataSpec)
                val actualUri = upstream.uri
                val cdnConnectMs = System.currentTimeMillis() - openStartMs
                remaining
            } catch (e: IOException) {
                throw e
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            return upstream.read(buffer, offset, length)
        }

        override fun getUri(): Uri? = upstream.uri

        override fun close() {
            upstream.close()
        }
    }
}

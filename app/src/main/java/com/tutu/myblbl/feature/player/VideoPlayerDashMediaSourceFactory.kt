package com.tutu.myblbl.feature.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.dash.DashMediaSource
import com.tutu.myblbl.core.common.log.AppLog

@OptIn(UnstableApi::class)
class VideoPlayerDashMediaSourceFactory(
    private val dataSourceFactory: DataSource.Factory,
    private val manifestBuilder: VideoPlayerDashManifestBuilder
) {

    companion object {
        private const val TAG = "DashMediaSourceFactory"
    }

    fun createMediaSource(route: DashRoute): MediaSource {
        val startTime = System.currentTimeMillis()
        AppLog.d(TAG, "dashPrepare:start")

        val manifest = manifestBuilder.buildManifest(route)

        val mediaItem = MediaItem.Builder()
            .setUri(Uri.EMPTY)
            .build()

        val mediaSource = DashMediaSource.Factory(dataSourceFactory)
            .createMediaSource(manifest, mediaItem)

        val elapsed = System.currentTimeMillis() - startTime
        AppLog.d(TAG, "dashPrepare:done ${elapsed}ms")

        return mediaSource
    }
}

package com.tutu.myblbl.feature.player.cache

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.tutu.myblbl.core.common.log.AppLog
import java.io.File

@UnstableApi
object PlayerMediaCache {

    private const val TAG = "PlayerMediaCache"
    private const val CACHE_DIR_NAME = "player_media_cache"
    private const val MAX_CACHE_BYTES = 512L * 1024L * 1024L
    private const val CACHE_FRAGMENT_SIZE_BYTES = 2L * 1024L * 1024L
    private const val RESOURCE_PATH_MARKER = "/v1/resource/"

    @Volatile
    private var simpleCache: SimpleCache? = null

    private val cacheKeyFactory = CacheKeyFactory { dataSpec ->
        dataSpec.key ?: buildStableCacheKey(dataSpec.uri)
    }

    private val cacheEventListener = object : CacheDataSource.EventListener {
        override fun onCachedBytesRead(cacheSizeBytes: Long, cachedBytesRead: Long) {
        }

        override fun onCacheIgnored(reason: Int) {
            val reasonName = when (reason) {
                CacheDataSource.CACHE_IGNORED_REASON_ERROR -> "error"
                CacheDataSource.CACHE_IGNORED_REASON_UNSET_LENGTH -> "unset_length"
                else -> "unknown($reason)"
            }
            AppLog.w(TAG, "cache ignored: reason=$reasonName")
        }
    }

    fun buildDataSourceFactory(
        context: Context,
        upstreamFactory: DataSource.Factory
    ): DataSource.Factory {
        val appContext = context.applicationContext
        val cache = getOrCreateCache(appContext)
        val cacheWriteFactory = CacheDataSink.Factory()
            .setCache(cache)
            .setFragmentSize(CACHE_FRAGMENT_SIZE_BYTES)
        return CacheDataSource.Factory()
            .setCache(cache)
            .setCacheKeyFactory(cacheKeyFactory)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setCacheReadDataSourceFactory(FileDataSource.Factory())
            .setCacheWriteDataSinkFactory(cacheWriteFactory)
            .setEventListener(cacheEventListener)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    fun buildDefaultDataSourceFactory(
        context: Context,
        upstreamFactory: DataSource.Factory
    ): DataSource.Factory {
        return DefaultDataSource.Factory(
            context.applicationContext,
            buildDataSourceFactory(context, upstreamFactory)
        )
    }

    @Synchronized
    fun clear(context: Context) {
        simpleCache?.release()
        simpleCache = null
        File(context.applicationContext.cacheDir, CACHE_DIR_NAME).deleteRecursively()
    }

    private fun buildStableCacheKey(uri: Uri): String {
        val rawPath = uri.encodedPath
            ?: uri.path
            ?: ""
        val stablePath = rawPath
            .substringAfter(RESOURCE_PATH_MARKER, rawPath)
            .trim('/')
            .ifBlank {
                uri.lastPathSegment?.trim('/')?.takeIf { it.isNotBlank() }
                    ?: uri.schemeSpecificPart.orEmpty()
            }
        return "media:$stablePath"
    }

    @Synchronized
    private fun getOrCreateCache(context: Context): SimpleCache {
        simpleCache?.let { return it }
        val cacheDirectory = File(context.cacheDir, CACHE_DIR_NAME).apply {
            if (!exists()) {
                mkdirs()
            }
        }
        return SimpleCache(
            cacheDirectory,
            LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES),
            StandaloneDatabaseProvider(context)
        ).also { simpleCache = it }
    }
}

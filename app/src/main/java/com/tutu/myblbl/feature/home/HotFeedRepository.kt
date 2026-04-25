package com.tutu.myblbl.feature.home

import android.os.SystemClock
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.core.common.log.HomeVideoCardDebugLogger
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.repository.VideoRepository
import com.tutu.myblbl.repository.cache.HomeCacheStore

class HotFeedRepository(
    private val videoRepository: VideoRepository
) {

    companion object {
        private const val TAG = "HotFeedRepository"
        private const val CACHE_KEY = "hotCacheList"
        private const val MAX_CACHED_HOT_ITEMS = 24
    }

    data class CachedFeed(
        val items: List<VideoModel>,
        val savedAtMs: Long,
        val schemaVersion: Int
    )

    data class NetworkPage(
        val items: List<VideoModel>,
        val hasMore: Boolean
    )

    suspend fun readCachedFeed(): CachedFeed {
        val startMs = SystemClock.elapsedRealtime()
        AppLog.i(TAG, "APP_STARTUP hot cache read start")
        val cached = HomeCacheStore.readCachedVideos(CACHE_KEY)
        AppLog.i(
            TAG,
            "APP_STARTUP hot cache read end elapsed=${SystemClock.elapsedRealtime() - startMs}ms count=${cached.items.size} ageMs=${formatCacheAge(cached.savedAtMs)} schema=${cached.schemaVersion}"
        )
        return CachedFeed(
            items = cached.items.take(MAX_CACHED_HOT_ITEMS),
            savedAtMs = cached.savedAtMs,
            schemaVersion = cached.schemaVersion
        )
    }

    suspend fun loadNetworkPage(page: Int, pageSize: Int): Result<NetworkPage> = runCatching {
        val response = videoRepository.getHotList(page, pageSize)
        if (!response.isSuccess) {
            error(response.message.ifBlank { "热门加载失败" })
        }
        val rawItems = response.data.orEmpty()
        HomeVideoCardDebugLogger.logRejectedCards(
            source = "hot(page=$page,pageSize=$pageSize)",
            items = rawItems
        )
        NetworkPage(
            items = rawItems.filter { it.isSupportedHomeVideoCard },
            hasMore = rawItems.size >= pageSize
        )
    }

    suspend fun writeCache(items: List<VideoModel>) {
        HomeCacheStore.writeVideos(
            cacheKey = CACHE_KEY,
            videos = items.take(MAX_CACHED_HOT_ITEMS)
        )
    }

    fun trimCacheItems(items: List<VideoModel>): List<VideoModel> {
        return items.take(MAX_CACHED_HOT_ITEMS)
    }

    private fun formatCacheAge(savedAtMs: Long): Long {
        return if (savedAtMs > 0L) {
            System.currentTimeMillis() - savedAtMs
        } else {
            -1L
        }
    }
}

package com.tutu.myblbl.feature.home

import android.os.SystemClock
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.core.common.log.HomeVideoCardDebugLogger
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.repository.VideoRepository
import com.tutu.myblbl.repository.cache.HomeCacheStore

class RecommendFeedRepository(
    private val videoRepository: VideoRepository
) {

    companion object {
        private const val TAG = "RecommendFeedRepository"
        private const val CACHE_KEY = "recommendCacheList"
        private const val MAX_CACHED_RECOMMEND_ITEMS = 24
        private const val PRELOAD_PAGE_SIZE = 12
    }

    @Volatile
    private var preloadedFirstPage: NetworkPage? = null

    suspend fun preloadFirstPage() {
        val startMs = SystemClock.elapsedRealtime()
        AppLog.i(TAG, "APP_STARTUP recommend preload start")
        loadNetworkPage(page = 1, pageSize = PRELOAD_PAGE_SIZE, freshIdx = 0)
            .getOrNull()?.let { page ->
                preloadedFirstPage = page
                writeCache(page.items)
            }
        AppLog.i(TAG, "APP_STARTUP recommend preload end elapsed=${SystemClock.elapsedRealtime() - startMs}ms")
    }

    fun takePreloadedFirstPage(): NetworkPage? {
        val result = preloadedFirstPage
        preloadedFirstPage = null
        return result
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
        AppLog.i(TAG, "APP_STARTUP recommend cache read start")
        val cached = HomeCacheStore.readCachedVideos(CACHE_KEY)
        AppLog.i(
            TAG,
            "APP_STARTUP recommend cache read end elapsed=${SystemClock.elapsedRealtime() - startMs}ms count=${cached.items.size} ageMs=${formatCacheAge(cached.savedAtMs)} schema=${cached.schemaVersion}"
        )
        return CachedFeed(
            items = cached.items.take(MAX_CACHED_RECOMMEND_ITEMS),
            savedAtMs = cached.savedAtMs,
            schemaVersion = cached.schemaVersion
        )
    }

    suspend fun loadNetworkPage(
        page: Int,
        pageSize: Int,
        freshIdx: Int
    ): Result<NetworkPage> = runCatching {
        val response = videoRepository.getRecommendList(freshIdx, pageSize)
        if (!response.isSuccess) {
            error(response.errorMessage.ifBlank { response.message.ifBlank { "推荐加载失败" } })
        }
        val rawItems = response.data?.items.orEmpty()
        HomeVideoCardDebugLogger.logRejectedCards(
            source = "recommend(page=$page,freshIdx=$freshIdx)",
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
            videos = items.take(MAX_CACHED_RECOMMEND_ITEMS)
        )
    }

    fun trimCacheItems(items: List<VideoModel>): List<VideoModel> {
        return items.take(MAX_CACHED_RECOMMEND_ITEMS)
    }

    private fun formatCacheAge(savedAtMs: Long): Long {
        return if (savedAtMs > 0L) {
            System.currentTimeMillis() - savedAtMs
        } else {
            -1L
        }
    }
}

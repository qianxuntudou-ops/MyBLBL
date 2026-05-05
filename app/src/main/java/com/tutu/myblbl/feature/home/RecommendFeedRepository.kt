package com.tutu.myblbl.feature.home

import android.content.Context
import android.os.SystemClock
import com.tutu.myblbl.core.common.settings.AppSettingsDataStore
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.core.common.log.HomeVideoCardDebugLogger
import com.tutu.myblbl.core.ui.image.ImageLoader
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.repository.VideoRepository
import com.tutu.myblbl.repository.cache.HomeCacheStore

class RecommendFeedRepository(
    private val videoRepository: VideoRepository,
    context: Context,
    private val appSettings: AppSettingsDataStore
) {

    private val appContext = context.applicationContext

    companion object {
        private const val TAG = "RecommendFeedRepository"
        private const val CACHE_KEY = "recommendCacheList"
        private const val MAX_CACHED_RECOMMEND_ITEMS = 24
        private const val PRELOAD_PAGE_SIZE = 12
        private const val COVER_PREFETCH_COUNT = 8
        private const val APP_FEED_PRELOAD_SIZE = 28
        private const val APP_FEED_FIRST_PAGE_SIZE = 28
        private const val APP_FEED_PAGE_SIZE = 30
        const val KEY_FEED_MODE = "feed_mode"
    }

    @Volatile
    private var preloadedFirstPage: NetworkPage? = null

    private var appFeedIdx = 0

    suspend fun preloadFirstPage() {
        val startMs = SystemClock.elapsedRealtime()
        AppLog.i(TAG, "APP_STARTUP recommend preload start mode=${if (isAppFeedMode()) "app" else "web"}")
        val preloadSize = if (isAppFeedMode()) APP_FEED_PRELOAD_SIZE else PRELOAD_PAGE_SIZE
        loadNetworkPage(page = 1, pageSize = preloadSize, freshIdx = 0)
            .getOrNull()?.let { page ->
                AppLog.i(TAG, "APP_STARTUP recommend preload got ${page.items.size} items, hasMore=${page.hasMore}")
                preloadedFirstPage = page
                writeCache(page.items)
                prefetchCovers(page.items)
            }
        AppLog.i(TAG, "APP_STARTUP recommend preload end elapsed=${SystemClock.elapsedRealtime() - startMs}ms")
    }

    private fun prefetchCovers(items: List<VideoModel>) {
        if (items.isEmpty()) return
        val urls = items.asSequence()
            .take(COVER_PREFETCH_COUNT)
            .map { it.bangumi?.cover?.takeIf { c -> c.isNotBlank() } ?: it.coverUrl }
            .toList()
        ImageLoader.prefetchVideoCovers(appContext, urls)
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

    private fun isAppFeedMode(): Boolean {
        return appSettings.getCachedString(KEY_FEED_MODE) == "移动端"
    }

    suspend fun loadNetworkPage(
        page: Int,
        pageSize: Int,
        freshIdx: Int
    ): Result<NetworkPage> = runCatching {
        if (isAppFeedMode()) {
            val appPageSize = if (page == 1) APP_FEED_FIRST_PAGE_SIZE else APP_FEED_PAGE_SIZE
            try {
                loadAppFeedPage(page, appPageSize, freshIdx)
            } catch (e: Exception) {
                AppLog.e(TAG, "recommend_app failed, fallback to web: ${e.message}")
                loadWebFeedPage(page, pageSize, freshIdx)
            }
        } else {
            loadWebFeedPage(page, pageSize, freshIdx)
        }
    }

    private suspend fun loadWebFeedPage(
        page: Int,
        pageSize: Int,
        freshIdx: Int
    ): NetworkPage {
        val response = videoRepository.getRecommendList(freshIdx, pageSize)
        if (!response.isSuccess) {
            error(response.errorMessage.ifBlank { response.message.ifBlank { "推荐加载失败" } })
        }
        val rawItems = response.data?.items.orEmpty()
        AppLog.i(TAG, "recommend_web(page=$page,freshIdx=$freshIdx) raw=${rawItems.size}")
        HomeVideoCardDebugLogger.logRejectedCards(
            source = "recommend_web(page=$page,freshIdx=$freshIdx)",
            items = rawItems
        )
        return NetworkPage(
            items = rawItems.filter { it.isSupportedHomeVideoCard },
            hasMore = rawItems.size >= pageSize
        )
    }

    private suspend fun loadAppFeedPage(
        page: Int,
        pageSize: Int,
        freshIdx: Int
    ): NetworkPage {
        if (page == 1) {
            appFeedIdx = 0
        }
        val idx = appFeedIdx
        val response = videoRepository.getAppRecommendList(idx, pageSize)
        if (!response.isSuccess) {
            error(response.errorMessage.ifBlank { response.message.ifBlank { "推荐加载失败" } })
        }
        val rawItems = response.data.orEmpty()
        // 对齐参考项目: 只保留 goto=="av" 且有有效标识(aid>0 或 bvid 非空)的视频
        val videoItems = rawItems.filter {
            it.hasPlaybackIdentity && (it.aid > 0L || it.bvid.isNotBlank())
        }
        AppLog.i(TAG, "recommend_app(page=$page,idx=$idx) raw=${rawItems.size} video=${videoItems.size}")
        HomeVideoCardDebugLogger.logRejectedCards(
            source = "recommend_app(page=$page,idx=$idx)",
            items = rawItems
        )
        // 对齐参考项目: 只要有视频数据就认为还有更多，idx 始终递增
        val hasMore = videoItems.isNotEmpty()
        if (hasMore) {
            appFeedIdx++
        }
        return NetworkPage(
            items = videoItems,
            hasMore = hasMore
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

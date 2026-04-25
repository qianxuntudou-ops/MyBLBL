package com.tutu.myblbl.feature.home

import android.os.SystemClock
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.model.lane.HomeLaneSection
import com.tutu.myblbl.model.lane.HomeLanePage
import com.tutu.myblbl.repository.HomeLaneRepository
import com.tutu.myblbl.repository.cache.HomeCacheStore

class HomeLaneFeedRepository(
    private val repository: HomeLaneRepository
) {

    companion object {
        private const val TAG = "HomeLaneFeedRepository"
        private const val MAX_CACHED_LANE_SECTIONS = 12
        private const val CACHE_MAX_AGE_MS = 14L * 24L * 60L * 60L * 1000L
    }

    data class CachedFeed(
        val items: List<HomeLaneSection>,
        val savedAtMs: Long,
        val schemaVersion: Int
    )

    suspend fun readCachedFeed(type: Int): CachedFeed {
        val startMs = SystemClock.elapsedRealtime()
        val cacheKey = cacheKey(type)
        AppLog.i(TAG, "APP_STARTUP lane cache read start type=$type")
        val cached = HomeCacheStore.readCachedSections(cacheKey)
        val items = if (isExpired(cached.savedAtMs)) {
            emptyList()
        } else {
            cached.items.take(MAX_CACHED_LANE_SECTIONS)
        }
        AppLog.i(
            TAG,
            "APP_STARTUP lane cache read end type=$type elapsed=${SystemClock.elapsedRealtime() - startMs}ms count=${items.size} ageMs=${formatCacheAge(cached.savedAtMs)} schema=${cached.schemaVersion}"
        )
        return CachedFeed(
            items = items,
            savedAtMs = cached.savedAtMs,
            schemaVersion = cached.schemaVersion
        )
    }

    suspend fun loadNetworkPage(type: Int, cursor: Long, isRefresh: Boolean): Result<HomeLanePage> {
        return repository.getHomeLanes(type = type, cursor = cursor, isRefresh = isRefresh)
            .map { page -> page.copy(sections = page.sections.filter { it.items.isNotEmpty() || it.timelineDays.isNotEmpty() }) }
    }

    suspend fun writeCache(type: Int, sections: List<HomeLaneSection>) {
        HomeCacheStore.writeSections(
            cacheKey(type),
            sections.sectionsForCache(type).take(MAX_CACHED_LANE_SECTIONS)
        )
    }

    private fun cacheKey(type: Int): String {
        return "laneCacheList$type"
    }

    private fun List<HomeLaneSection>.sectionsForCache(type: Int): List<HomeLaneSection> {
        return if (type == HomeLaneRepository.TYPE_ANIMATION) {
            filter { it.timelineDays.isEmpty() }
        } else {
            this
        }
    }

    private fun formatCacheAge(savedAtMs: Long): Long {
        return if (savedAtMs > 0L) {
            System.currentTimeMillis() - savedAtMs
        } else {
            -1L
        }
    }

    private fun isExpired(savedAtMs: Long): Boolean {
        return savedAtMs > 0L && System.currentTimeMillis() - savedAtMs > CACHE_MAX_AGE_MS
    }
}

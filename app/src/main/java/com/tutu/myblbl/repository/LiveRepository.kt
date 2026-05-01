package com.tutu.myblbl.repository

import com.google.gson.JsonObject
import com.tutu.myblbl.model.live.LiveAreaCategoryParent
import com.tutu.myblbl.model.live.LiveListWrapper
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.tutu.myblbl.repository.remote.LiveRepository as NetworkLiveRepository

typealias LiveRoomPage = com.tutu.myblbl.repository.remote.LiveRoomPage

/**
 * 直播 facade，负责给 ViewModel 提供「带轻量内存缓存」的数据接口。
 *
 * 直播 API 的 host 是独立的 `api.live.bilibili.com`，TLS 首连成本在电视上常达 600~1000ms；
 * 而这层下面的 ViewModel 都是 fragment-scoped，ViewPager 切走再切回来 fragment 会重建、
 * ViewModel 会重建，结果就是用户每来回切一次直播 tab，就要重头拉一次 areas + recommend，
 * 体感非常卡。这里在进程内做一层短 TTL 内存缓存来挡住重复请求。
 *
 * 用户主动下拉刷新时，调用方传 `forceRefresh = true` 旁路缓存。
 */
class LiveRepository(
    private val delegate: NetworkLiveRepository
) {

    companion object {
        // 直播分区列表很少变化，30 分钟基本足够 cover 一次冷启动后的所有切换。
        private const val AREAS_CACHE_TTL_MS = 30 * 60 * 1000L
        // 推荐数据：用户在主 tab 之间来回切的常见周期是几秒到几分钟，5 分钟 TTL 既能挡住
        // 「切走再切回」的重复请求，又不会让数据看起来过期；用户主动下拉/MENU 刷新会
        // 通过 forceRefresh 旁路。
        private const val RECOMMEND_CACHE_TTL_MS = 5 * 60 * 1000L
    }

    private data class CachedAreas(
        val data: List<LiveAreaCategoryParent>,
        val ts: Long
    )

    private data class CachedRecommend(
        val data: LiveListWrapper,
        val ts: Long
    )

    @Volatile
    private var cachedAreas: CachedAreas? = null
    private val areasMutex = Mutex()

    @Volatile
    private var cachedRecommend: CachedRecommend? = null
    private val recommendMutex = Mutex()

    suspend fun getLivePlayInfo(roomId: Long, quality: Int = 10000) =
        delegate.getLivePlayInfo(roomId, quality)

    suspend fun getRecommendLive(page: Int, pageSize: Int) =
        delegate.getRecommendLive(page, pageSize)

    suspend fun getLiveRecommend(forceRefresh: Boolean = false): Result<LiveListWrapper> {
        if (!forceRefresh) {
            cachedRecommend
                ?.takeIf { System.currentTimeMillis() - it.ts < RECOMMEND_CACHE_TTL_MS }
                ?.let { return Result.success(it.data) }
        }
        return recommendMutex.withLock {
            if (!forceRefresh) {
                cachedRecommend
                    ?.takeIf { System.currentTimeMillis() - it.ts < RECOMMEND_CACHE_TTL_MS }
                    ?.let { return@withLock Result.success(it.data) }
            }
            val result = delegate.getLiveRecommend()
            result.onSuccess { data ->
                cachedRecommend = CachedRecommend(data, System.currentTimeMillis())
            }
            result
        }
    }

    suspend fun getAreaLive(parentAreaId: Long, areaId: Long, page: Int) =
        delegate.getAreaLive(parentAreaId, areaId, page)

    suspend fun getLiveAreas(forceRefresh: Boolean = false): Result<List<LiveAreaCategoryParent>> {
        if (!forceRefresh) {
            cachedAreas
                ?.takeIf { System.currentTimeMillis() - it.ts < AREAS_CACHE_TTL_MS }
                ?.let { return Result.success(it.data) }
        }
        return areasMutex.withLock {
            if (!forceRefresh) {
                cachedAreas
                    ?.takeIf { System.currentTimeMillis() - it.ts < AREAS_CACHE_TTL_MS }
                    ?.let { return@withLock Result.success(it.data) }
            }
            val result = delegate.getLiveAreas()
            result.onSuccess { data ->
                cachedAreas = CachedAreas(data, System.currentTimeMillis())
            }
            result
        }
    }

    suspend fun getIpInfo(): Result<JsonObject> = delegate.getIpInfo()

    suspend fun reportRoomEntry(roomId: Long): Result<Unit> = delegate.reportRoomEntry(roomId)

    suspend fun getHeartbeatKey(roomId: Long): Result<JsonObject> = delegate.getHeartbeatKey(roomId)

    suspend fun getUserRoomInfo(roomId: Long): Result<JsonObject> = delegate.getUserRoomInfo(roomId)

    suspend fun getDanmuHistory(roomId: Long): Result<JsonObject> = delegate.getDanmuHistory(roomId)

    suspend fun sendLiveHeartbeat(roomId: Long): Result<Unit> = delegate.sendLiveHeartbeat(roomId)
}

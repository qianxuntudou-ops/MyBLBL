package com.tutu.myblbl.repository

import com.tutu.myblbl.repository.remote.LiveRepository as NetworkLiveRepository

typealias LiveRoomPage = com.tutu.myblbl.repository.remote.LiveRoomPage

class LiveRepository(
    private val delegate: NetworkLiveRepository
) {
    suspend fun getLivePlayInfo(roomId: Long, quality: Int = 10000) =
        delegate.getLivePlayInfo(roomId, quality)

    suspend fun getRecommendLive(page: Int, pageSize: Int) =
        delegate.getRecommendLive(page, pageSize)

    suspend fun getLiveRecommend() = delegate.getLiveRecommend()

    suspend fun getAreaLive(parentAreaId: Long, areaId: Long, page: Int) =
        delegate.getAreaLive(parentAreaId, areaId, page)

    suspend fun getLiveAreas() = delegate.getLiveAreas()
}

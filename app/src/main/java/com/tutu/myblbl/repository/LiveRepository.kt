package com.tutu.myblbl.repository

import com.google.gson.JsonArray
import com.google.gson.JsonObject
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

    suspend fun getIpInfo(): Result<JsonObject> = delegate.getIpInfo()

    suspend fun reportRoomEntry(roomId: Long): Result<Unit> = delegate.reportRoomEntry(roomId)

    suspend fun getHeartbeatKey(roomId: Long): Result<JsonObject> = delegate.getHeartbeatKey(roomId)

    suspend fun getUserRoomInfo(roomId: Long): Result<JsonObject> = delegate.getUserRoomInfo(roomId)

    suspend fun getDanmuHistory(roomId: Long): Result<JsonObject> = delegate.getDanmuHistory(roomId)

    suspend fun sendLiveHeartbeat(roomId: Long): Result<Unit> = delegate.sendLiveHeartbeat(roomId)
}

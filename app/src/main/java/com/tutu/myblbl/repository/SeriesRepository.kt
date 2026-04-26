package com.tutu.myblbl.repository

import com.tutu.myblbl.model.series.CheckUserSeriesResult
import com.tutu.myblbl.model.series.FollowSeriesResult
import com.tutu.myblbl.model.series.RelatedRecommendResult
import com.tutu.myblbl.repository.remote.SeriesRepository as NetworkSeriesRepository

class SeriesRepository(
    private val delegate: NetworkSeriesRepository
) {
    suspend fun getSeriesDetail(seasonId: Long, epId: Long = 0) =
        delegate.getSeriesDetail(seasonId, epId)

    suspend fun checkUserFollowStatus(seasonId: Long, epId: Long = 0): Result<CheckUserSeriesResult> =
        delegate.checkUserFollowStatus(seasonId, epId)

    suspend fun followSeries(seasonId: Long): Result<FollowSeriesResult> =
        delegate.followSeries(seasonId)

    suspend fun cancelFollowSeries(seasonId: Long): Result<FollowSeriesResult> =
        delegate.cancelFollowSeries(seasonId)

    suspend fun getMyFollowingSeries(type: Int, page: Int, pageSize: Int, vmid: Long) =
        delegate.getMyFollowingSeries(type, page, pageSize, vmid)

    suspend fun getSeriesTimeline(type: Int, before: Int = 6, after: Int = 6) =
        delegate.getSeriesTimeline(type, before, after)

    suspend fun getRelatedRecommend(seasonId: Long): Result<RelatedRecommendResult> =
        delegate.getRelatedRecommend(seasonId)
}

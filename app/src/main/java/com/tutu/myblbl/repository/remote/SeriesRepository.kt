package com.tutu.myblbl.repository.remote

import com.tutu.myblbl.model.series.EpisodesDetailModel
import com.tutu.myblbl.model.series.FollowSeriesResult
import com.tutu.myblbl.model.series.MyFollowingResponseWrapper
import com.tutu.myblbl.model.series.timeline.TimeLineADayModel
import com.tutu.myblbl.network.api.ApiService
import com.tutu.myblbl.network.session.NetworkSessionGateway

class SeriesRepository(
    private val apiService: ApiService,
    private val sessionGateway: NetworkSessionGateway
) {

    suspend fun getSeriesDetail(seasonId: Long, epId: Long = 0): Result<EpisodesDetailModel> {
        return runCatching {
            val response = apiService.getVideoEpisodes(
                if (seasonId > 0) seasonId else null,
                if (epId > 0) epId else null
            ).let {
                sessionGateway.syncAuthState(it, source = "series.getSeriesDetail")
            }
            val detail = response.result
            if (response.isSuccess && detail != null) {
                val resolvedSeasonId = detail.seasonId.takeIf { it > 0 } ?: seasonId
                val sectionResult = if (resolvedSeasonId > 0) {
                    apiService.getVideoEpisodeSections(resolvedSeasonId)
                        .let { sessionGateway.syncAuthState(it, source = "series.getVideoEpisodeSections") }
                        .result
                } else {
                    null
                }
                val mergedDetail = detail.copy(
                    episodes = sectionResult?.mainSection?.episodes.orEmpty(),
                    section = sectionResult?.section.orEmpty(),
                    mainSectionTitle = sectionResult?.mainSection?.title.orEmpty()
                )
                mergedDetail
            } else {
                throw IllegalStateException(response.errorMessage)
            }
        }
    }

    suspend fun followSeries(seasonId: Long): Result<FollowSeriesResult> {
        return runCatching {
            val csrf = sessionGateway.getCsrfToken()
            val response = sessionGateway.syncAuthState(
                apiService.followSeries(seasonId, csrf),
                source = "series.followSeries"
            )
            if (response.isSuccess) {
                FollowSeriesResult(
                    relation = true,
                    status = 1,
                    toast = response.errorMessage.ifBlank { "追番成功" }
                )
            } else {
                throw IllegalStateException(response.errorMessage.ifBlank { "追番失败" })
            }
        }
    }

    suspend fun cancelFollowSeries(seasonId: Long): Result<FollowSeriesResult> {
        return runCatching {
            val csrf = sessionGateway.getCsrfToken()
            val response = sessionGateway.syncAuthState(
                apiService.cancelFollowSeries(seasonId, csrf),
                source = "series.cancelFollowSeries"
            )
            if (response.isSuccess) {
                FollowSeriesResult(
                    relation = false,
                    status = 0,
                    toast = response.errorMessage.ifBlank { "已取消追番" }
                )
            } else {
                throw IllegalStateException(response.errorMessage.ifBlank { "取消追番失败" })
            }
        }
    }

    suspend fun getMyFollowingSeries(
        type: Int,
        page: Int,
        pageSize: Int,
        vmid: Long
    ): Result<MyFollowingResponseWrapper> {
        return runCatching {
            val response = apiService.getMyFollowingSeries(
                type = type,
                page = page,
                pageSize = pageSize,
                vmid = vmid,
                ts = System.currentTimeMillis()
            ).let {
                sessionGateway.syncAuthState(it, source = "series.getMyFollowingSeries")
            }
            if (response.code == 0 && response.data != null) {
                response.data
            } else {
                throw IllegalStateException(response.message.ifEmpty { response.msg })
            }
        }
    }

    suspend fun getSeriesTimeline(
        type: Int,
        before: Int = 6,
        after: Int = 6
    ): Result<List<TimeLineADayModel>> {
        return runCatching {
            val response = apiService.getSeriesTimeLine(
                type = type,
                before = before,
                after = after
            )
            if (response.code == 0) {
                response.result.map { day ->
                    day.copy(
                        episodes = day.episodes.map { episode ->
                            episode.copy(dayOfWeek = day.dayOfWeek)
                        }
                    )
                }
            } else {
                throw IllegalStateException(response.message.ifEmpty { "时间线加载失败" })
            }
        }
    }
}

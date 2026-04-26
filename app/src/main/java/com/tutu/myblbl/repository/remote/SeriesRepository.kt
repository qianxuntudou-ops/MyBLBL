package com.tutu.myblbl.repository.remote

import com.tutu.myblbl.model.series.CheckUserSeriesResult
import com.tutu.myblbl.model.series.EpisodesDetailModel
import com.tutu.myblbl.model.series.FollowSeriesResult
import com.tutu.myblbl.model.series.MyFollowingResponseWrapper
import com.tutu.myblbl.model.series.RelatedRecommendResult
import com.tutu.myblbl.model.series.timeline.TimeLineADayModel
import com.tutu.myblbl.network.api.ApiService
import com.tutu.myblbl.network.security.NetworkSecurityGateway
import com.tutu.myblbl.network.session.NetworkSessionGateway

class SeriesRepository(
    private val apiService: ApiService,
    private val sessionGateway: NetworkSessionGateway,
    private val securityGateway: NetworkSecurityGateway
) {

    suspend fun getSeriesDetail(seasonId: Long, epId: Long = 0): Result<EpisodesDetailModel> {
        return runCatching {
            securityGateway.ensureHealthyForPlay()
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

    suspend fun checkUserFollowStatus(seasonId: Long, epId: Long = 0): Result<CheckUserSeriesResult> {
        return runCatching {
            val response = apiService.getSeriesUserStatus(
                if (seasonId > 0) seasonId else null,
                if (epId > 0) epId else null
            ).let {
                sessionGateway.syncAuthState(it, source = "series.checkUserFollowStatus")
            }
            val result = response.result
            if (response.isSuccess && result != null) {
                result
            } else {
                throw IllegalStateException(response.errorMessage.ifBlank { "获取追番状态失败" })
            }
        }
    }

    suspend fun followSeries(seasonId: Long): Result<FollowSeriesResult> {
        return runCatching {
            securityGateway.ensureHealthyForPlay()
            val csrf = sessionGateway.requireCsrfToken()
                ?: throw IllegalStateException("csrf token is blank")
            var response = apiService.followSeries(seasonId, csrf)
            if (response.code == -101 && sessionGateway.tryRecoverExpiredSession()) {
                val newCsrf = sessionGateway.requireCsrfToken()
                if (!newCsrf.isNullOrBlank()) {
                    response = apiService.followSeries(seasonId, newCsrf)
                }
            }
            val finalResponse = sessionGateway.syncAuthState(
                response,
                source = "series.followSeries"
            )
            if (finalResponse.isSuccess) {
                FollowSeriesResult(
                    relation = true,
                    status = 1,
                    toast = ""
                )
            } else {
                throw IllegalStateException(finalResponse.errorMessage.ifBlank { "追番失败" })
            }
        }
    }

    suspend fun cancelFollowSeries(seasonId: Long): Result<FollowSeriesResult> {
        return runCatching {
            securityGateway.ensureHealthyForPlay()
            val csrf = sessionGateway.requireCsrfToken()
                ?: throw IllegalStateException("csrf token is blank")
            var response = apiService.cancelFollowSeries(seasonId, csrf)
            if (response.code == -101 && sessionGateway.tryRecoverExpiredSession()) {
                val newCsrf = sessionGateway.requireCsrfToken()
                if (!newCsrf.isNullOrBlank()) {
                    response = apiService.cancelFollowSeries(seasonId, newCsrf)
                }
            }
            val finalResponse = sessionGateway.syncAuthState(
                response,
                source = "series.cancelFollowSeries"
            )
            if (finalResponse.isSuccess) {
                FollowSeriesResult(
                    relation = false,
                    status = 0,
                    toast = ""
                )
            } else {
                throw IllegalStateException(finalResponse.errorMessage.ifBlank { "取消追番失败" })
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

    suspend fun getRelatedRecommend(seasonId: Long): Result<RelatedRecommendResult> {
        return runCatching {
            val response = apiService.getRelatedRecommend(seasonId)
            if (response.isSuccess && response.data != null) {
                response.data
            } else {
                throw IllegalStateException(response.errorMessage.ifEmpty { "推荐加载失败" })
            }
        }
    }
}

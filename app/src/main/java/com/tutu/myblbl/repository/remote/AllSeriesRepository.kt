package com.tutu.myblbl.repository.remote

import com.tutu.myblbl.model.lane.LaneItemModel
import com.tutu.myblbl.model.series.AllSeriesFilterModel
import com.tutu.myblbl.model.series.SeriesModel
import com.tutu.myblbl.network.api.ApiService

data class AllSeriesPage(
    val list: List<SeriesModel> = emptyList(),
    val hasMore: Boolean = false
)

class AllSeriesRepository(
    private val apiService: ApiService
) {

    suspend fun getAllSeries(
        type: Int,
        page: Int,
        filters: List<AllSeriesFilterModel> = emptyList()
    ): Result<AllSeriesPage> {
        return runCatching {
            val params = buildParams(type, page, filters)
            val response = apiService.getAllSeries(params)
            if (response.code != 0 || response.data == null) {
                throw IllegalStateException(response.message.ifEmpty { response.msg })
            }

            AllSeriesPage(
                list = response.data.list.map { it.toSeriesModel() },
                hasMore = response.data.list.size >= 24
            )
        }
    }

    private fun buildParams(
        type: Int,
        page: Int,
        filters: List<AllSeriesFilterModel>
    ): Map<String, String> {
        return buildMap {
            put("st", type.toString())
            put("season_type", type.toString())
            put("page", page.toString())
            put("pagesize", "24")
            put("type", "1")
            filters.forEach { filter ->
                val option = filter.options.getOrNull(filter.currentSelect) ?: return@forEach
                put(filter.key, option.value)
                if (filter.key == "order") {
                    put("sort", filter.sortDirection.toString())
                }
            }
        }
    }

    private fun LaneItemModel.toSeriesModel(): SeriesModel {
        return SeriesModel(
            seasonId = seasonId,
            title = title,
            cover = cover,
            badge = badgeInfo?.text.orEmpty(),
            badgeInfo = badgeInfo,
            seasonTitle = title,
            evaluate = desc,
            progress = subTitle.ifEmpty { desc }
        )
    }
}

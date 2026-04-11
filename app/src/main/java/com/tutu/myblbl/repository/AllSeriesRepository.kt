package com.tutu.myblbl.repository

import com.tutu.myblbl.model.series.AllSeriesFilterModel
import com.tutu.myblbl.repository.remote.AllSeriesRepository as NetworkAllSeriesRepository

typealias AllSeriesPage = com.tutu.myblbl.repository.remote.AllSeriesPage

class AllSeriesRepository(
    private val delegate: NetworkAllSeriesRepository
) {
    suspend fun getAllSeries(
        type: Int,
        page: Int,
        filters: List<AllSeriesFilterModel> = emptyList()
    ) = delegate.getAllSeries(type, page, filters)
}

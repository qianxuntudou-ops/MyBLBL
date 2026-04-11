package com.tutu.myblbl.repository

import com.tutu.myblbl.model.search.HotWordModel
import com.tutu.myblbl.model.search.SearchType
import com.tutu.myblbl.model.search.SearchVideoOrder
import com.tutu.myblbl.repository.remote.SearchRepository as NetworkSearchRepository

class SearchRepository(
    private val delegate: NetworkSearchRepository
) {
    suspend fun loadHotSearchWords(): Result<List<HotWordModel>> = delegate.loadHotSearchWords()

    suspend fun searchSuggest(keyword: String): Result<List<HotWordModel>> = delegate.searchSuggest(keyword)

    suspend fun searchAll(keyword: String) = delegate.searchAll(keyword)

    suspend fun searchByType(
        searchType: SearchType,
        keyword: String,
        page: Int,
        pageSize: Int,
        order: SearchVideoOrder = SearchVideoOrder.TotalRank
    ) = delegate.searchByType(searchType, keyword, page, pageSize, order)

    suspend fun search(
        keyword: String,
        page: Int,
        pageSize: Int,
        order: SearchVideoOrder = SearchVideoOrder.TotalRank
    ) = delegate.search(keyword, page, pageSize, order)
}

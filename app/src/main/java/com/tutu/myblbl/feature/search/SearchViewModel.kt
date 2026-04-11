package com.tutu.myblbl.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tutu.myblbl.model.search.HotWordModel
import com.tutu.myblbl.model.search.SearchAllCountWrapper
import com.tutu.myblbl.model.search.SearchAllResponseData
import com.tutu.myblbl.model.search.SearchCategoryItem
import com.tutu.myblbl.model.search.SearchItemModel
import com.tutu.myblbl.model.search.SearchType
import com.tutu.myblbl.model.search.SearchVideoOrder
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.repository.SearchRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SearchViewModel(
    private val searchRepository: SearchRepository
) : ViewModel() {

    data class SearchPageState(
        val items: List<SearchItemModel> = emptyList(),
        val page: Int = 0,
        val hasMore: Boolean = true,
        val loading: Boolean = false
    )

    private val _hotSearchWords = MutableStateFlow<List<HotWordModel>>(emptyList())
    val hotSearchWords: StateFlow<List<HotWordModel>> = _hotSearchWords.asStateFlow()

    private val _searchResults = MutableStateFlow<List<VideoModel>>(emptyList())
    val searchResults: StateFlow<List<VideoModel>> = _searchResults.asStateFlow()

    private val _suggestWords = MutableStateFlow<List<HotWordModel>>(emptyList())
    val suggestWords: StateFlow<List<HotWordModel>> = _suggestWords.asStateFlow()

    private val _searchOverview = MutableStateFlow<SearchAllResponseData?>(null)
    val searchOverview: StateFlow<SearchAllResponseData?> = _searchOverview.asStateFlow()

    private val _searchCategories = MutableStateFlow<List<SearchCategoryItem>>(emptyList())
    val searchCategories: StateFlow<List<SearchCategoryItem>> = _searchCategories.asStateFlow()

    private val _searchPageStates = MutableStateFlow<Map<SearchType, SearchPageState>>(emptyMap())
    val searchPageStates: StateFlow<Map<SearchType, SearchPageState>> = _searchPageStates.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _hasMore = MutableStateFlow(true)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private var searchAllJob: Job? = null
    private val pageJobs = mutableMapOf<SearchType, Job>()
    private var suggestJob: Job? = null
    private var activeKeyword: String = ""

    fun loadHotSearch() {
        viewModelScope.launch {
            _hotSearchWords.value = searchRepository.loadHotSearchWords()
                .getOrDefault(emptyList())
        }
    }

    fun search(
        keyword: String,
        page: Int,
        pageSize: Int,
        order: SearchVideoOrder = SearchVideoOrder.TotalRank
    ) {
        viewModelScope.launch {
            _loading.value = true

            val result = searchRepository.search(keyword, page, pageSize, order)

            result.fold(
                onSuccess = { videos ->
                    _searchResults.value = if (page == 1) {
                        videos
                    } else {
                        _searchResults.value + videos
                    }
                    _hasMore.value = videos.size >= pageSize
                },
                onFailure = {
                    if (page == 1) {
                        _searchResults.value = emptyList()
                    }
                    _hasMore.value = false
                }
            )
            
            _loading.value = false
        }
    }

    fun searchAll(keyword: String) {
        searchAllJob?.cancel()
        pageJobs.values.forEach(Job::cancel)
        pageJobs.clear()
        activeKeyword = keyword
        searchAllJob = viewModelScope.launch {
            val requestKeyword = keyword
            _loading.value = true
            _searchOverview.value = null
            _searchCategories.value = emptyList()
            _searchPageStates.value = emptyMap()

            val result = searchRepository.searchAll(keyword)
            if (activeKeyword != requestKeyword) {
                return@launch
            }

            result.fold(
                onSuccess = { overview ->
                    _searchOverview.value = overview
                    val categories = buildCategories(overview)
                    _searchCategories.value = categories
                    _searchPageStates.value = buildInitialPageStates(overview, categories)
                },
                onFailure = {
                    _searchOverview.value = null
                    _searchCategories.value = emptyList()
                    _searchPageStates.value = emptyMap()
                }
            )

            _loading.value = false
        }
    }

    fun loadSearchPage(
        searchType: SearchType,
        keyword: String,
        pageSize: Int,
        order: SearchVideoOrder = SearchVideoOrder.TotalRank,
        forceRefresh: Boolean = false
    ) {
        if (keyword != activeKeyword) {
            return
        }
        val currentState = _searchPageStates.value[searchType] ?: SearchPageState()
        if (currentState.loading) {
            return
        }
        if (!forceRefresh && !currentState.hasMore && currentState.page > 0) {
            return
        }

        val nextPage = if (forceRefresh) 1 else currentState.page + 1
        updatePageState(searchType) {
            it.copy(
                loading = true,
                items = if (forceRefresh) emptyList() else it.items
            )
        }

        pageJobs[searchType]?.cancel()
        pageJobs[searchType] = viewModelScope.launch {
            val result = searchRepository.searchByType(
                searchType = searchType,
                keyword = keyword,
                page = nextPage,
                pageSize = pageSize,
                order = order
            )
            if (activeKeyword != keyword) {
                return@launch
            }

            result.fold(
                onSuccess = { response ->
                    val newItems = response.result.orEmpty()
                    updatePageState(searchType) { old ->
                        old.copy(
                            items = if (nextPage == 1) newItems else old.items + newItems,
                            page = nextPage,
                            hasMore = response.numPages > nextPage || newItems.size >= pageSize,
                            loading = false
                        )
                    }
                },
                onFailure = {
                    updatePageState(searchType) { old ->
                        if (nextPage == 1) {
                            old.copy(
                                items = emptyList(),
                                page = 0,
                                hasMore = true,
                                loading = false
                            )
                        } else {
                            old.copy(
                                page = old.page,
                                hasMore = old.hasMore,
                                loading = false
                            )
                        }
                    }
                }
            )
            pageJobs.remove(searchType)
        }
    }

    fun searchSuggest(keyword: String) {
        suggestJob?.cancel()
        suggestJob = viewModelScope.launch {
            if (keyword.isEmpty()) {
                _suggestWords.value = emptyList()
                return@launch
            }

            _suggestWords.value = searchRepository.searchSuggest(keyword)
                .getOrDefault(emptyList())
        }
    }

    private fun buildCategories(overview: SearchAllResponseData): List<SearchCategoryItem> {
        val pageInfo = overview.pageinfo ?: return emptyList()
        val categories = mutableListOf<SearchCategoryItem>()

        fun addIfNeeded(type: SearchType, count: Int) {
            if (count > 0) {
                categories += SearchCategoryItem(type, resolveTitle(type))
            }
        }

        addIfNeeded(SearchType.Video, pageInfo.video?.numResults ?: 0)
        addIfNeeded(SearchType.Animation, pageInfo.mediaBangumi?.numResults ?: 0)
        addIfNeeded(SearchType.FilmAndTv, pageInfo.mediaFt?.numResults ?: 0)
        addIfNeeded(SearchType.LiveRoom, pageInfo.liveRoom?.numResults ?: 0)
        addIfNeeded(SearchType.User, pageInfo.biliUser?.numResults ?: 0)

        return categories
    }

    private fun buildInitialPageStates(
        overview: SearchAllResponseData,
        categories: List<SearchCategoryItem>
    ): Map<SearchType, SearchPageState> {
        val pageInfo = overview.pageinfo
        val groupedResults = overview.result
            .orEmpty()
            .mapNotNull { result ->
                SearchType.fromValue(result.resultType)?.let { type ->
                    type to result.data.orEmpty()
                }
            }
            .toMap()

        return categories.associate { category ->
            val items = groupedResults[category.type].orEmpty()
            category.type to SearchPageState(
                items = items,
                page = if (items.isNotEmpty()) 1 else 0,
                hasMore = hasMorePages(category.type, pageInfo, items.size),
                loading = false
            )
        }
    }

    private fun hasMorePages(
        type: SearchType,
        pageInfo: SearchAllCountWrapper?,
        itemCount: Int
    ): Boolean {
        val count = when (type) {
            SearchType.Video -> pageInfo?.video
            SearchType.Animation -> pageInfo?.mediaBangumi
            SearchType.FilmAndTv -> pageInfo?.mediaFt
            SearchType.LiveRoom -> pageInfo?.liveRoom
            SearchType.User -> pageInfo?.biliUser
        } ?: return itemCount > 0

        return count.pages > 1 || count.numResults > itemCount
    }

    private fun resolveTitle(type: SearchType): String {
        return when (type) {
            SearchType.Video -> "视频"
            SearchType.Animation -> "番剧"
            SearchType.FilmAndTv -> "影视"
            SearchType.LiveRoom -> "直播"
            SearchType.User -> "用户"
        }
    }

    private fun updatePageState(
        searchType: SearchType,
        updater: (SearchPageState) -> SearchPageState
    ) {
        val current = _searchPageStates.value[searchType] ?: SearchPageState()
        _searchPageStates.value = _searchPageStates.value.toMutableMap().apply {
            this[searchType] = updater(current)
        }
    }
}

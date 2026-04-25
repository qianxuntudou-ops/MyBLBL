package com.tutu.myblbl.feature.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tutu.myblbl.core.common.content.ContentFilter
import com.tutu.myblbl.model.video.VideoModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecommendViewModel(
    private val repository: RecommendFeedRepository,
    context: Context
) : ViewModel(), VideoFeedViewModel {

    companion object {
        private const val FIRST_PAGE_SIZE = 12
        private const val NEXT_PAGE_SIZE = 24
    }

    private val appContext = context.applicationContext
    private val _uiState = MutableStateFlow(FeedUiState<VideoModel>())
    override val uiState: StateFlow<FeedUiState<VideoModel>> = _uiState.asStateFlow()

    private val freshIndexTracker = RecommendFreshIndexTracker()
    private var currentPage = 0
    private var hasLoadedInitial = false

    override fun loadInitial() {
        if (hasLoadedInitial) return
        hasLoadedInitial = true
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                loadingInitial = true,
                errorMessage = null,
                listChange = FeedListChange.NONE
            )
            val cached = runCatching { repository.readCachedFeed() }.getOrNull()
            val cachedItems = cached?.items.orEmpty().filterForDisplay()
            if (cachedItems.isNotEmpty()) {
                currentPage = 1
                _uiState.value = FeedUiState(
                    items = cachedItems,
                    source = FeedSource.CACHE,
                    listChange = FeedListChange.REPLACE,
                    loadingInitial = false,
                    hasMore = true
                )
                return@launch
            }
            loadPage(page = 1, replace = true, fromInitial = true)
        }
    }

    override fun refresh() {
        viewModelScope.launch {
            loadPage(page = 1, replace = true, fromRefresh = true)
        }
    }

    override fun loadMore() {
        val state = _uiState.value
        if (state.loadingInitial || state.refreshing || state.appending || !state.hasMore) {
            return
        }
        val nextPage = (currentPage + 1).coerceAtLeast(1)
        viewModelScope.launch {
            loadPage(page = nextPage, replace = false)
        }
    }

    override fun consumeListChange() {
        val state = _uiState.value
        if (state.listChange != FeedListChange.NONE) {
            _uiState.value = state.copy(listChange = FeedListChange.NONE)
        }
    }

    private suspend fun loadPage(
        page: Int,
        replace: Boolean,
        fromInitial: Boolean = false,
        fromRefresh: Boolean = false
    ) {
        val current = _uiState.value
        _uiState.value = current.copy(
            loadingInitial = fromInitial,
            refreshing = fromRefresh,
            appending = !replace,
            errorMessage = null,
            listChange = FeedListChange.NONE
        )

        val freshIdx = freshIndexTracker.resolve(page)
        val pageSize = if (page == 1) FIRST_PAGE_SIZE else NEXT_PAGE_SIZE
        repository.loadNetworkPage(
            page = page,
            pageSize = pageSize,
            freshIdx = freshIdx
        ).onSuccess { pageResult ->
            val filteredItems = pageResult.items.filterForDisplay()
            if (page == 1) {
                freshIndexTracker.markFirstPageLoaded()
            }
            val mergedItems = if (replace) {
                filteredItems
            } else {
                current.items + filteredItems
            }
            currentPage = page
            _uiState.value = FeedUiState(
                items = mergedItems,
                source = FeedSource.NETWORK,
                listChange = if (replace) FeedListChange.REPLACE else FeedListChange.APPEND,
                hasMore = pageResult.hasMore
            )
            if (mergedItems.isNotEmpty()) {
                repository.writeCache(repository.trimCacheItems(mergedItems))
            }
        }.onFailure { throwable ->
            _uiState.value = current.copy(
                loadingInitial = false,
                refreshing = false,
                appending = false,
                errorMessage = throwable.message ?: "推荐加载失败",
                listChange = FeedListChange.NONE
            )
        }
    }

    private suspend fun List<VideoModel>.filterForDisplay(): List<VideoModel> {
        return withContext(Dispatchers.Default) {
            ContentFilter.filterVideos(appContext, this@filterForDisplay)
        }
    }
}

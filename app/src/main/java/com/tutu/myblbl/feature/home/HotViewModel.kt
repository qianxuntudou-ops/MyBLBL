package com.tutu.myblbl.feature.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tutu.myblbl.core.common.content.ContentFilter
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.repository.UserRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HotViewModel(
    private val repository: HotFeedRepository,
    private val userRepository: UserRepository,
    context: Context
) : ViewModel(), VideoFeedViewModel {

    companion object {
        private const val PAGE_SIZE = 24
    }

    private val appContext = context.applicationContext
    private val _uiState = MutableStateFlow(FeedUiState<VideoModel>())
    override val uiState: StateFlow<FeedUiState<VideoModel>> = _uiState.asStateFlow()

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
            val cachedItems = runCatching { repository.readCachedFeed().items }
                .getOrDefault(emptyList())
                .filterForDisplay()
            if (cachedItems.isNotEmpty()) {
                currentPage = 1
                _uiState.value = FeedUiState(
                    items = cachedItems,
                    source = FeedSource.CACHE,
                    listChange = FeedListChange.REPLACE,
                    loadingInitial = false,
                    hasMore = true
                )
                enrichFollowStatus(cachedItems)
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

        repository.loadNetworkPage(page = page, pageSize = PAGE_SIZE)
            .onSuccess { pageResult ->
                val filteredItems = pageResult.items.filterForDisplay()
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
                    enrichFollowStatus(mergedItems)
                }
            }.onFailure { throwable ->
                _uiState.value = current.copy(
                    loadingInitial = false,
                    refreshing = false,
                    appending = false,
                    errorMessage = throwable.message ?: "热门加载失败",
                    listChange = FeedListChange.NONE
                )
            }
    }

    private fun enrichFollowStatus(videos: List<VideoModel>) {
        val mids = videos.mapNotNull { it.owner?.mid }.distinct()
        if (mids.isEmpty()) return
        viewModelScope.launch {
            val followedMids = userRepository.batchCheckFollowed(mids)
            if (followedMids.isEmpty()) return@launch
            val current = _uiState.value
            val updated = current.items.map { video ->
                val mid = video.owner?.mid ?: 0L
                if (mid in followedMids && !video.isFollowed) video.copy(isFollowed = true) else video
            }
            _uiState.value = current.copy(
                items = updated,
                listChange = if (current.items.isNotEmpty()) FeedListChange.REPLACE else FeedListChange.NONE
            )
        }
    }

    private suspend fun List<VideoModel>.filterForDisplay(): List<VideoModel> {
        return withContext(Dispatchers.Default) {
            ContentFilter.filterVideos(appContext, this@filterForDisplay)
        }
    }
}

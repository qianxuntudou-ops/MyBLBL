package com.tutu.myblbl.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tutu.myblbl.model.lane.HomeLaneSection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HomeLaneViewModel(
    private val type: Int,
    private val repository: HomeLaneFeedRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(FeedUiState<HomeLaneSection>())
    val uiState: StateFlow<FeedUiState<HomeLaneSection>> = _uiState.asStateFlow()

    private var cursor: Long = 0L
    private var hasLoadedInitial = false

    fun loadInitial() {
        if (hasLoadedInitial) return
        hasLoadedInitial = true
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                loadingInitial = true,
                errorMessage = null,
                listChange = FeedListChange.NONE
            )
            val cachedItems = runCatching { repository.readCachedFeed(type).items }
                .getOrDefault(emptyList())
            if (cachedItems.isNotEmpty()) {
                cursor = 0L
                _uiState.value = FeedUiState(
                    items = cachedItems,
                    source = FeedSource.CACHE,
                    listChange = FeedListChange.REPLACE,
                    loadingInitial = false,
                    hasMore = true
                )
                return@launch
            }
            loadPage(replace = true, fromInitial = true)
        }
    }

    fun refresh() {
        viewModelScope.launch {
            loadPage(replace = true, fromRefresh = true)
        }
    }

    fun loadMore() {
        val state = _uiState.value
        if (state.loadingInitial || state.refreshing || state.appending || !state.hasMore) {
            return
        }
        viewModelScope.launch {
            loadPage(replace = false)
        }
    }

    fun consumeListChange() {
        val state = _uiState.value
        if (state.listChange != FeedListChange.NONE) {
            _uiState.value = state.copy(listChange = FeedListChange.NONE)
        }
    }

    private suspend fun loadPage(
        replace: Boolean,
        fromInitial: Boolean = false,
        fromRefresh: Boolean = false
    ) {
        val current = _uiState.value
        val requestCursor = if (replace) 0L else cursor
        _uiState.value = current.copy(
            loadingInitial = fromInitial,
            refreshing = fromRefresh,
            appending = !replace,
            errorMessage = null,
            listChange = FeedListChange.NONE
        )

        repository.loadNetworkPage(type = type, cursor = requestCursor, isRefresh = replace)
            .onSuccess { page ->
                val mergedItems = if (replace) {
                    page.sections
                } else {
                    mergeSections(current.items, page.sections)
                }
                cursor = page.nextCursor
                _uiState.value = FeedUiState(
                    items = mergedItems,
                    source = FeedSource.NETWORK,
                    listChange = if (replace) FeedListChange.REPLACE else FeedListChange.APPEND,
                    hasMore = page.hasMore && page.sections.isNotEmpty()
                )
                if (replace && page.sections.isNotEmpty()) {
                    repository.writeCache(type, page.sections)
                }
            }.onFailure { throwable ->
                _uiState.value = current.copy(
                    loadingInitial = false,
                    refreshing = false,
                    appending = false,
                    errorMessage = throwable.message ?: "分区加载失败",
                    listChange = FeedListChange.NONE
                )
            }
    }

    private fun mergeSections(
        existing: List<HomeLaneSection>,
        incoming: List<HomeLaneSection>
    ): List<HomeLaneSection> {
        val keys = existing.map { it.deduplicateKey() }.toMutableSet()
        val deduped = incoming.filter { keys.add(it.deduplicateKey()) }
        return existing + deduped
    }

    private fun HomeLaneSection.deduplicateKey(): String {
        if (timelineDays.isNotEmpty()) return "timeline"
        if (style == "follow") return "follow"
        return "${title.trim()}#${style}#${moreSeasonType}"
    }
}

package com.tutu.myblbl.feature.home

enum class FeedSource {
    NONE,
    CACHE,
    NETWORK
}

enum class FeedListChange {
    NONE,
    REPLACE,
    APPEND
}

data class FeedUiState<T>(
    val items: List<T> = emptyList(),
    val source: FeedSource = FeedSource.NONE,
    val listChange: FeedListChange = FeedListChange.NONE,
    val loadingInitial: Boolean = false,
    val refreshing: Boolean = false,
    val appending: Boolean = false,
    val hasMore: Boolean = true,
    val errorMessage: String? = null
)

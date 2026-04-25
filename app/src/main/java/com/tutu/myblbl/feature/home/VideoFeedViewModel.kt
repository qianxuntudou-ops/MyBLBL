package com.tutu.myblbl.feature.home

import com.tutu.myblbl.model.video.VideoModel
import kotlinx.coroutines.flow.StateFlow

interface VideoFeedViewModel {
    val uiState: StateFlow<FeedUiState<VideoModel>>

    fun loadInitial()

    fun refresh()

    fun loadMore()

    fun consumeListChange()
}

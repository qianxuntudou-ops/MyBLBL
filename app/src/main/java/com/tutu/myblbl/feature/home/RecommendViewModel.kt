package com.tutu.myblbl.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.repository.VideoRepository
import com.tutu.myblbl.core.common.log.AppLog
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RecommendViewModel(
    private val videoRepository: VideoRepository
) : ViewModel() {

    companion object {
        private const val TAG = "RecommendViewModel"
    }

    private val _videos = MutableStateFlow<List<VideoModel>>(emptyList())
    val videos: StateFlow<List<VideoModel>> = _videos.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableSharedFlow<String?>()
    val error: SharedFlow<String?> = _error.asSharedFlow()

    private val _hasMore = MutableStateFlow(true)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private val freshIndexTracker = RecommendFreshIndexTracker()

    fun loadRecommendList(page: Int, pageSize: Int) {
        viewModelScope.launch {
            val freshIdx = freshIndexTracker.resolve(page)
            _loading.value = true
            runCatching {
                videoRepository.getRecommendList(freshIdx, pageSize)
            }.onSuccess { response ->
                _loading.value = false
                if (response.isSuccess) {
                    val items = response.data?.items ?: emptyList()
                    val validItems = items.filter {
                        it.title.isNotBlank() && (it.bvid.isNotBlank() || it.aid > 0 || it.cid > 0)
                    }
                    if (page == 1) {
                        freshIndexTracker.markFirstPageLoaded()
                    }
                    _videos.value = validItems
                    _hasMore.value = items.size >= pageSize
                } else {
                    AppLog.e(
                        TAG,
                        "loadRecommendList api failure: page=$page, freshIdx=$freshIdx, code=${response.code}, message=${response.message}"
                    )
                    _error.emit(response.errorMessage)
                }
            }.onFailure { throwable ->
                _loading.value = false
                AppLog.e(
                    TAG,
                    "loadRecommendList failure: page=$page, freshIdx=$freshIdx, pageSize=$pageSize, message=${throwable.message}",
                    throwable
                )
                _error.emit(throwable.message)
            }
        }
    }

    fun refresh(pageSize: Int) {
        loadRecommendList(1, pageSize)
    }
}

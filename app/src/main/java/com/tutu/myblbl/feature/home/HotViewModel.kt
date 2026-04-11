package com.tutu.myblbl.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.repository.VideoRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HotViewModel(
    private val videoRepository: VideoRepository
) : ViewModel() {

    private val _videos = MutableStateFlow<List<VideoModel>>(emptyList())
    val videos: StateFlow<List<VideoModel>> = _videos.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableSharedFlow<String?>()
    val error: SharedFlow<String?> = _error.asSharedFlow()

    private val _hasMore = MutableStateFlow(true)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    fun loadHotList(page: Int, pageSize: Int) {
        viewModelScope.launch {
            _loading.value = true
            runCatching {
                videoRepository.getHotList(page, pageSize)
            }.onSuccess { response ->
                _loading.value = false
                if (response.isSuccess) {
                    val items = response.data ?: emptyList()
                    val validItems = items.filter {
                        it.title.isNotBlank() && (it.bvid.isNotBlank() || it.aid > 0 || it.cid > 0)
                    }
                    _videos.value = validItems
                    _hasMore.value = validItems.size >= pageSize
                } else {
                    _error.emit(response.message)
                }
            }.onFailure { throwable ->
                _loading.value = false
                _error.emit(throwable.message)
            }
        }
    }

    fun refresh(pageSize: Int) {
        loadHotList(1, pageSize)
    }
}

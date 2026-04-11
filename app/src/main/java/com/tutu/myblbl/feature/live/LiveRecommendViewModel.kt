package com.tutu.myblbl.feature.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tutu.myblbl.model.live.LiveListWrapper
import com.tutu.myblbl.repository.LiveRepository
import com.tutu.myblbl.utils.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LiveRecommendViewModel(
    private val liveRepository: LiveRepository
) : ViewModel() {
    private var lastLoadedAt = 0L

    enum class LiveRecommendStatus {
        Idle,
        Content,
        Empty,
        Error
    }

    private val _recommendData = MutableStateFlow<LiveListWrapper?>(null)
    val recommendData: StateFlow<LiveListWrapper?> = _recommendData.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _status = MutableStateFlow(LiveRecommendStatus.Idle)
    val status: StateFlow<LiveRecommendStatus> = _status.asStateFlow()

    fun loadData(forceRefresh: Boolean = false) {
        if (_loading.value && !forceRefresh) {
            AppLog.d("LiveRecommendVM", "loadData skipped: already loading")
            return
        }

        AppLog.d("LiveRecommendVM", "loadData: forceRefresh=$forceRefresh")
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            _status.value = LiveRecommendStatus.Idle

            liveRepository.getLiveRecommend().fold(
                onSuccess = { data ->
                    AppLog.d("LiveRecommendVM", "loadData success: recommendRoomList=${data.recommendRoomList?.size ?: "null"}, roomList=${data.roomList?.size ?: "null"}")
                    _recommendData.value = data
                    lastLoadedAt = System.currentTimeMillis()
                    val hasContent = hasContent(data)
                    AppLog.d("LiveRecommendVM", "hasContent=$hasContent")
                    _status.value = if (hasContent) {
                        LiveRecommendStatus.Content
                    } else {
                        LiveRecommendStatus.Empty
                    }
                },
                onFailure = { exception ->
                    AppLog.e("LiveRecommendVM", "loadData failed: ${exception.message}", exception)
                    _recommendData.value = null
                    _error.value = exception.message
                    _status.value = LiveRecommendStatus.Error
                }
            )

            _loading.value = false
        }
    }

    private fun hasContent(data: LiveListWrapper): Boolean {
        return data.recommendRoomList.orEmpty().isNotEmpty() || data.roomList.orEmpty().any {
            it.list.orEmpty().isNotEmpty()
        }
    }

    fun shouldRefresh(ttlMs: Long): Boolean {
        if (_recommendData.value == null) {
            return true
        }
        return System.currentTimeMillis() - lastLoadedAt >= ttlMs
    }
}

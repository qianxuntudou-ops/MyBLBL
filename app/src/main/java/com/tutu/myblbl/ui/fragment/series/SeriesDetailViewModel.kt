package com.tutu.myblbl.ui.fragment.series

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tutu.myblbl.model.series.EpisodeProgressModel
import com.tutu.myblbl.model.series.EpisodesDetailModel
import com.tutu.myblbl.model.series.FollowSeriesResult
import com.tutu.myblbl.network.NetworkManager
import com.tutu.myblbl.R
import com.tutu.myblbl.repository.SeriesRepository
import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class SeriesDetailViewModel(
    private val repository: SeriesRepository
) : ViewModel() {

    sealed interface UiMessage {
        data class Text(val value: String) : UiMessage
        data class Res(@StringRes val resId: Int) : UiMessage
    }

    private val _seriesDetail = MutableStateFlow<EpisodesDetailModel?>(null)
    val seriesDetail: StateFlow<EpisodesDetailModel?> = _seriesDetail.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    
    private val _isFollowed = MutableStateFlow(false)
    val isFollowed: StateFlow<Boolean> = _isFollowed.asStateFlow()

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 1)
    val messages: SharedFlow<UiMessage> = _messages.asSharedFlow()
    
    private var currentSeasonId: Long = 0
    private var isFollowActionRunning = false

    fun loadSeriesDetail(seasonId: Long, epId: Long = 0) {
        currentSeasonId = seasonId
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            
            repository.getSeriesDetail(seasonId, epId).fold(
                onSuccess = { detail ->
                    _seriesDetail.value = detail
                    currentSeasonId = detail.seasonId.takeIf { it > 0 } ?: currentSeasonId
                    detail.userStatus?.let { status ->
                        _isFollowed.value = status.follow == 1
                    }
                },
                onFailure = { e ->
                    _error.value = e.message ?: "加载番剧详情失败"
                    _messages.tryEmit(UiMessage.Text(_error.value.orEmpty()))
                }
            )
            
            _isLoading.value = false
        }
    }

    fun toggleFollow() {
        if (currentSeasonId <= 0 || isFollowActionRunning) return
        if (!NetworkManager.isLoggedIn()) {
            return
        }
        
        viewModelScope.launch {
            isFollowActionRunning = true
            val result: Result<FollowSeriesResult> = if (_isFollowed.value) {
                repository.cancelFollowSeries(currentSeasonId)
            } else {
                repository.followSeries(currentSeasonId)
            }
            
            result.fold(
                onSuccess = { followResult ->
                    _isFollowed.value = !_isFollowed.value
                    if (followResult.toast.isNotBlank()) {
                        _messages.emit(UiMessage.Text(followResult.toast))
                    } else {
                        _messages.emit(
                            UiMessage.Res(
                                if (_isFollowed.value) R.string.followed_series else R.string.follow_series
                            )
                        )
                    }
                },
                onFailure = { e ->
                    _error.value = e.message ?: "操作失败"
                    _messages.emit(UiMessage.Text(_error.value.orEmpty()))
                }
            )
            isFollowActionRunning = false
        }
    }

    fun playEpisode(@Suppress("UNUSED_PARAMETER") epId: Long, @Suppress("UNUSED_PARAMETER") cid: Long) {
    }

    fun updateEpisodeProgress(epId: Long, timeMs: Long, epIndex: String) {
        val detail = _seriesDetail.value ?: return
        val userStatus = detail.userStatus ?: return
        val newProgress = EpisodeProgressModel(
            lastEpId = epId,
            lastEpIndex = epIndex,
            lastTime = timeMs
        )
        _seriesDetail.value = detail.copy(
            userStatus = userStatus.copy(progress = newProgress)
        )
    }
}

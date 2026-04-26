package com.tutu.myblbl.feature.series

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tutu.myblbl.model.series.EpisodeProgressModel
import com.tutu.myblbl.model.series.EpisodesDetailModel
import com.tutu.myblbl.model.series.FollowSeriesResult
import com.tutu.myblbl.model.series.SeriesModel
import com.tutu.myblbl.R
import com.tutu.myblbl.network.session.NetworkSessionGateway
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
    private val repository: SeriesRepository,
    private val sessionGateway: NetworkSessionGateway
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

    private val _recommendItems = MutableStateFlow<List<SeriesModel>>(emptyList())
    val recommendItems: StateFlow<List<SeriesModel>> = _recommendItems.asStateFlow()

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
                    currentSeasonId = detail.seasonId.takeIf { it > 0 } ?: currentSeasonId
                    val userStatus = detail.userStatus
                    if (userStatus != null) {
                        _isFollowed.value = userStatus.isFollowed
                        _seriesDetail.value = detail
                        loadRecommend(currentSeasonId)
                    } else if (sessionGateway.isLoggedIn()) {
                        sessionGateway.forceCookieRefresh()
                        repository.getSeriesDetail(currentSeasonId, epId).fold(
                            onSuccess = { retryDetail ->
                                currentSeasonId = retryDetail.seasonId.takeIf { it > 0 } ?: currentSeasonId
                                retryDetail.userStatus?.let { status ->
                                    _isFollowed.value = status.isFollowed
                                }
                                _seriesDetail.value = retryDetail
                                loadRecommend(currentSeasonId)
                            },
                            onFailure = {
                                _seriesDetail.value = detail
                                loadRecommend(currentSeasonId)
                            }
                        )
                    } else {
                        _seriesDetail.value = detail
                        loadRecommend(currentSeasonId)
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
        if (!sessionGateway.isLoggedIn()) {
            viewModelScope.launch {
                _messages.emit(UiMessage.Res(R.string.toast_need_login))
            }
            return
        }
        
        viewModelScope.launch {
            isFollowActionRunning = true
            val nowFollowed = !_isFollowed.value
            val result: Result<FollowSeriesResult> = if (_isFollowed.value) {
                repository.cancelFollowSeries(currentSeasonId)
            } else {
                repository.followSeries(currentSeasonId)
            }
            
            result.fold(
                onSuccess = { followResult ->
                    _isFollowed.value = nowFollowed
                    if (followResult.toast.isNotBlank()) {
                        _messages.emit(UiMessage.Text(followResult.toast))
                    } else {
                        _messages.emit(UiMessage.Res(followSuccessMessage(nowFollowed)))
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

    private fun followSuccessMessage(nowFollowed: Boolean): Int {
        val isAnimation = _seriesDetail.value?.type.let { it == 1 || it == 4 }
        return if (nowFollowed) {
            if (isAnimation) R.string.toast_follow_animation_success
            else R.string.toast_follow_series_success
        } else {
            if (isAnimation) R.string.toast_cancel_follow_animation_success
            else R.string.toast_cancel_follow_series_success
        }
    }

    private fun loadRecommend(seasonId: Long) {
        if (seasonId <= 0) return
        viewModelScope.launch {
            repository.getRelatedRecommend(seasonId).onSuccess { result ->
                _recommendItems.value = (result.relates + result.season)
                    .filter { it.seasonId > 0 && it.cover.isNotBlank() }
            }
        }
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

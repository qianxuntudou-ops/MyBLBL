package com.tutu.myblbl.feature.series

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tutu.myblbl.model.series.EpisodeProgressModel
import com.tutu.myblbl.model.series.EpisodesDetailModel
import com.tutu.myblbl.model.series.FollowSeriesResult
import com.tutu.myblbl.model.series.SeriesModel
import com.tutu.myblbl.R
import com.tutu.myblbl.core.common.log.AppLog
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
                    _seriesDetail.value = detail
                    loadRecommend(currentSeasonId)
                    if (sessionGateway.isLoggedIn()) {
                        checkFollowStatusFromApi(currentSeasonId, epId)
                    } else {
                        val userStatus = detail.userStatus
                        AppLog.d("SeriesDetail", "loadSeriesDetail: not logged in, using userStatus from detail")
                        _isFollowed.value = userStatus?.isFollowed ?: false
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

    private fun checkFollowStatusFromApi(seasonId: Long, epId: Long) {
        viewModelScope.launch {
            repository.checkUserFollowStatus(seasonId, epId).fold(
                onSuccess = { statusResult ->
                    AppLog.d("SeriesDetail", "checkFollowStatusFromApi: follow=${statusResult.follow} login=${statusResult.login} isFollowed=${statusResult.isFollowed}")
                    _isFollowed.value = statusResult.isFollowed
                },
                onFailure = { e ->
                    AppLog.w("SeriesDetail", "checkFollowStatusFromApi failed: ${e.message}, falling back to userStatus from detail")
                    val userStatus = _seriesDetail.value?.userStatus
                    _isFollowed.value = userStatus?.isFollowed ?: false
                }
            )
        }
    }

    fun toggleFollow() {
        if (currentSeasonId <= 0 || isFollowActionRunning) return
        if (!sessionGateway.isLoggedIn()) {
            AppLog.w("SeriesDetail", "toggleFollow: not logged in")
            viewModelScope.launch {
                _messages.emit(UiMessage.Res(R.string.toast_need_login))
            }
            return
        }
        
        viewModelScope.launch {
            isFollowActionRunning = true
            val nowFollowed = !_isFollowed.value
            AppLog.d("SeriesDetail", "toggleFollow: seasonId=$currentSeasonId nowFollowed=$nowFollowed currentIsFollowed=${_isFollowed.value}")
            val result: Result<FollowSeriesResult> = if (_isFollowed.value) {
                repository.cancelFollowSeries(currentSeasonId)
            } else {
                repository.followSeries(currentSeasonId)
            }
            
            result.fold(
                onSuccess = { followResult ->
                    AppLog.d("SeriesDetail", "toggleFollow success: relation=${followResult.relation} status=${followResult.status}")
                    _isFollowed.value = nowFollowed
                    if (followResult.toast.isNotBlank()) {
                        _messages.emit(UiMessage.Text(followResult.toast))
                    } else {
                        _messages.emit(UiMessage.Res(followSuccessMessage(nowFollowed)))
                    }
                },
                onFailure = { e ->
                    AppLog.e("SeriesDetail", "toggleFollow failed: ${e.message}")
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

package com.tutu.myblbl.ui.fragment.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tutu.myblbl.repository.LiveRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LivePlayerViewModel(
    private val repository: LiveRepository
) : ViewModel() {
    
    private val _playUrl = MutableStateFlow<String?>(null)
    val playUrl: StateFlow<String?> = _playUrl.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    
    private val _qualities = MutableStateFlow<List<LiveQualityInfo>>(emptyList())
    val qualities: StateFlow<List<LiveQualityInfo>> = _qualities.asStateFlow()
    private val _selectedQuality = MutableStateFlow<LiveQualityInfo?>(null)
    val selectedQuality: StateFlow<LiveQualityInfo?> = _selectedQuality.asStateFlow()
    
    private var currentRoomId: Long = 0

    fun loadLiveStream(roomId: Long) {
        currentRoomId = roomId
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            
            repository.getLivePlayInfo(roomId).fold(
                onSuccess = { data ->
                    val durl = data.durl
                    if (!durl.isNullOrEmpty()) {
                        _playUrl.value = durl[0].url
                        
                        data.qualityDescription?.let { qualities ->
                            val mappedQualities = qualities.map {
                                LiveQualityInfo(it.qn, it.desc)
                            }
                            _qualities.value = mappedQualities
                            _selectedQuality.value = mappedQualities.firstOrNull {
                                it.qn == data.currentQn
                            } ?: mappedQualities.firstOrNull()
                        }
                    } else {
                        _error.value = "无法获取直播流地址"
                    }
                },
                onFailure = { e ->
                    _error.value = e.message ?: "加载直播失败"
                }
            )
            
            _isLoading.value = false
        }
    }

    fun switchQuality(qn: Int) {
        if (currentRoomId > 0) {
            viewModelScope.launch {
                repository.getLivePlayInfo(currentRoomId, qn).fold(
                    onSuccess = { data ->
                        _selectedQuality.value = _qualities.value.firstOrNull { it.qn == qn }
                        data.durl?.firstOrNull()?.let { durl ->
                            _playUrl.value = durl.url
                        }
                    },
                    onFailure = { e ->
                        _error.value = e.message ?: "切换画质失败"
                    }
                )
            }
        }
    }
}

data class LiveQualityInfo(
    val qn: Int,
    val desc: String
)

package com.tutu.myblbl.feature.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tutu.myblbl.model.live.LiveAreaCategoryParent
import com.tutu.myblbl.repository.LiveRepository
import com.tutu.myblbl.utils.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LiveViewModel(
    private val liveRepository: LiveRepository
) : ViewModel() {

    companion object {
        private const val TAG = "LiveViewModel"
    }

    private var lastLoadedAt = 0L

    private val _categories = MutableStateFlow<List<LiveAreaCategoryParent>>(emptyList())
    val categories: StateFlow<List<LiveAreaCategoryParent>> = _categories.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun loadLiveAreas() {
        if (_loading.value) return
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            
            val result = liveRepository.getLiveAreas()
            
            result.fold(
                onSuccess = { areaList ->
                    AppLog.e("[BLBL_DIAG]", "ViewModel: loadLiveAreas SUCCESS, areaList.size=${areaList.size}")
                    areaList.forEachIndexed { index, parent ->
                        AppLog.e("[BLBL_DIAG]", "ViewModel: [$index] id=${parent.id} name=${parent.name} areaList=${parent.areaList?.size ?: "null"}")
                    }
                    val recommendCategory = LiveAreaCategoryParent(
                        id = 0,
                        name = "推荐"
                    )
                    _categories.value = listOf(recommendCategory) + areaList
                    AppLog.e("[BLBL_DIAG]", "ViewModel: categories.value set, total=${_categories.value.size}")
                    lastLoadedAt = System.currentTimeMillis()
                },
                onFailure = { exception ->
                    AppLog.e("[BLBL_DIAG]", "ViewModel: loadLiveAreas FAILED: ${exception.message}", exception)
                    _error.value = exception.message
                }
            )
            
            _loading.value = false
        }
    }

    fun shouldRefresh(ttlMs: Long): Boolean {
        if (_categories.value.isEmpty()) {
            return true
        }
        return System.currentTimeMillis() - lastLoadedAt >= ttlMs
    }
}

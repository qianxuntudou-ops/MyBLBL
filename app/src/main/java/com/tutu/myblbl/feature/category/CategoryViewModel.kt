package com.tutu.myblbl.feature.category

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.repository.VideoRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CategoryViewModel(
    private val videoRepository: VideoRepository
) : ViewModel() {
    companion object {
        private data class CategoryCacheEntry(
            val videos: List<VideoModel>,
            val updatedAt: Long
        )

        private val categoryCache = linkedMapOf<Int, CategoryCacheEntry>()
    }

    private val _videos = MutableStateFlow<List<VideoModel>>(emptyList())
    val videos: StateFlow<List<VideoModel>> = _videos.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _hasLoaded = MutableStateFlow(false)
    val hasLoaded: StateFlow<Boolean> = _hasLoaded.asStateFlow()

    fun clearError() {
        _error.value = null
    }

    fun loadCategoryVideos(rid: Int, forceRefresh: Boolean = false) {
        if (!forceRefresh) {
            categoryCache[rid]?.let { cachedEntry ->
                _error.value = null
                _hasLoaded.value = true
                _videos.value = cachedEntry.videos
                return
            }
        }

        _loading.value = true
        _error.value = null
        viewModelScope.launch {
            runCatching {
                videoRepository.getRanking(rid)
            }.onSuccess { response ->
                _loading.value = false
                _hasLoaded.value = true
                if (response.isSuccess) {
                    val videoList = response.data.orEmpty()
                    categoryCache[rid] = CategoryCacheEntry(
                        videos = videoList,
                        updatedAt = System.currentTimeMillis()
                    )
                    _videos.value = videoList
                    _error.value = null
                } else {
                    _error.value = normalizeErrorMessage(response.errorMessage)
                }
            }.onFailure { exception ->
                _loading.value = false
                _hasLoaded.value = true
                _error.value = normalizeErrorMessage(exception.message)
            }
        }
    }

    private fun normalizeErrorMessage(message: String?): String {
        val raw = message.orEmpty().trim()
        return if (raw == "-352") {
            "分类内容加载失败，请稍后重试"
        } else {
            raw.ifBlank { "分类内容加载失败，请稍后重试" }
        }
    }

    fun isCacheStale(rid: Int, ttlMs: Long): Boolean {
        val updatedAt = categoryCache[rid]?.updatedAt ?: return true
        return System.currentTimeMillis() - updatedAt >= ttlMs
    }
}

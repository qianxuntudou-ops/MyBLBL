package com.tutu.myblbl.repository

import com.tutu.myblbl.repository.remote.HomeLaneRepository as NetworkHomeLaneRepository

class HomeLaneRepository(
    private val delegate: NetworkHomeLaneRepository
) {
    companion object {
        const val TYPE_ANIMATION = NetworkHomeLaneRepository.TYPE_ANIMATION
        const val TYPE_CINEMA = NetworkHomeLaneRepository.TYPE_CINEMA
    }

    suspend fun getHomeLanes(type: Int, cursor: Long = 0, isRefresh: Boolean = true) =
        delegate.getHomeLanes(type, cursor, isRefresh)

    suspend fun getAnimationTimelineSection() =
        delegate.getAnimationTimelineSection()
}

package com.tutu.myblbl

import com.tutu.myblbl.network.NetworkManager
import com.tutu.myblbl.repository.UserRepository
import com.tutu.myblbl.repository.VideoRepository
import com.tutu.myblbl.repository.remote.HomeLaneRepository
import com.tutu.myblbl.repository.remote.SeriesRepository as RemoteSeriesRepository
import com.tutu.myblbl.repository.remote.VideoRepository as RemoteVideoRepository
import org.junit.Test

class HomeDataProbeTest {

    @Test
    fun probeRecommendAndAnimeHomeData() {
        val videoRepository = VideoRepository(
            RemoteVideoRepository(NetworkManager.apiService)
        )
        runCatching {
            kotlinx.coroutines.runBlocking {
                videoRepository.getRecommendList(0, 24)
            }
        }.onSuccess { recommend ->
            println("recommend.code=${recommend.code}")
            println("recommend.message=${recommend.message}")
            println("recommend.items=${recommend.data?.items?.size ?: -1}")
            println(
                "recommend.first=${recommend.data?.items?.firstOrNull()?.let { "${it.title}|${it.bvid}|${it.aid}|${it.cid}" }}"
            )
        }.onFailure { throwable ->
            println("recommend.failure=${throwable::class.java.name}:${throwable.message}")
        }

        val homeLaneRepository = HomeLaneRepository(
            apiService = NetworkManager.apiService,
            seriesRepository = RemoteSeriesRepository(),
            userRepository = UserRepository()
        )
        val anime = kotlinx.coroutines.runBlocking {
            homeLaneRepository.getHomeLanes(
                type = HomeLaneRepository.TYPE_ANIMATION,
                cursor = 0,
                isRefresh = true
            ).getOrThrow()
        }
        println("anime.sections=${anime.sections.size}")
        println(
            "anime.firstSection=${anime.sections.firstOrNull()?.let { "${it.title}|items=${it.items.size}|timeline=${it.timelineDays.size}" }}"
        )
        println(
            "anime.firstItem=${anime.sections.firstOrNull { it.items.isNotEmpty() }?.items?.firstOrNull()?.let { "${it.title}|${it.cover}|${it.seasonId}|${it.oid}" }}"
        )
    }
}

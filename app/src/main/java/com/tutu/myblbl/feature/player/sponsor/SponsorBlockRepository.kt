package com.tutu.myblbl.feature.player.sponsor

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object SponsorBlockRepository {

    private const val BASE_URL = "https://bsbsb.top/api"
    private const val TAG = "SponsorBlock"

    private val gson = Gson()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    suspend fun getSegments(
        bvid: String,
        cid: Long = 0L,
        categories: List<String> = SponsorSegment.ALL_CATEGORIES
    ): List<SponsorSegment> = withContext(Dispatchers.IO) {
        try {
            val params = buildList {
                add("videoID=$bvid")
                if (cid > 0L) add("cid=$cid")
                categories.forEach { add("category=$it") }
            }
            val url = "$BASE_URL/skipSegments?${params.joinToString("&")}"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "MyBLBL/1.0")
                .get()
                .build()

            val response = client.newCall(request).execute()
            when (response.code) {
                200 -> {
                    val body = response.body?.string() ?: return@withContext emptyList()
                    val type = object : TypeToken<List<SponsorSegment>>() {}.type
                    val segments = gson.fromJson<List<SponsorSegment>>(body, type)
                    Log.d(TAG, "获取到 ${segments.size} 个空降片段 for $bvid")
                    normalizeSegments(segments)
                }
                404 -> {
                    Log.d(TAG, "视频 $bvid 没有空降数据")
                    emptyList()
                }
                else -> {
                    Log.w(TAG, "API 返回错误: ${response.code}")
                    emptyList()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取空降片段失败: ${e.message}")
            emptyList()
        }
    }

    private fun normalizeSegments(segments: List<SponsorSegment>): List<SponsorSegment> {
        return segments
            .filter { it.isSkipType && it.endTimeMs > it.startTimeMs }
            .groupBy { it.category }
            .values
            .mapNotNull { candidates ->
                candidates.maxWithOrNull(
                    compareBy<SponsorSegment> { it.locked }
                        .thenBy { it.votes }
                )
            }
            .sortedBy { it.startTimeMs }
    }
}
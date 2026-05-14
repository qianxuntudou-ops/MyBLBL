package com.tutu.myblbl.feature.player.sponsor

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object SponsorBlockRepository {

    private const val BASE_URL = "https://bsbsb.top/api"
    private const val TAG = "SponsorBlock"
    private const val CACHE_TTL_MS = 30 * 60 * 1000L

    private val gson = Gson()

    private data class CacheEntry(
        val segments: List<SponsorSegment>,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    private data class HashVideoResponse(
        val videoID: String = "",
        val segments: List<SponsorSegment> = emptyList()
    )

    suspend fun testConnection(): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$BASE_URL/status")
                .header("User-Agent", "MyBLBL/1.0")
                .header("origin", "com.tutu.myblbl")
                .header("x-ext-version", "1.0")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (response.code == 200) null else "空降助手服务异常 (${response.code})"
        } catch (e: Exception) {
            Log.e(TAG, "连通性测试失败: ${e.message}")
            "空降助手连接失败，请检查网络"
        }
    }

    suspend fun testFetch(): String = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL/skipSegments/a1b2?category=sponsor"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "MyBLBL/1.0")
                .header("origin", "com.tutu.myblbl")
                .header("x-ext-version", "1.0")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (response.code != 200) return@withContext "测试失败：API 返回 ${response.code}"

            val body = response.body?.string() ?: return@withContext "测试失败：响应为空"
            val type = object : TypeToken<List<HashVideoResponse>>() {}.type
            val videos = gson.fromJson<List<HashVideoResponse>>(body, type)
            val totalSegments = videos.sumOf { it.segments.size }
            return@withContext if (totalSegments > 0) {
                "测试通过：解析正常，获取到 $totalSegments 个片段"
            } else {
                "测试通过：连接和解析正常（当前无测试数据）"
            }
        } catch (e: Exception) {
            Log.e(TAG, "片段拉取测试失败: ${e.message}", e)
            "测试失败：${e.message}"
        }
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    data class SegmentResult(
        val segments: List<SponsorSegment> = emptyList(),
        val error: String? = null
    )

    suspend fun getSegments(
        bvid: String,
        cid: Long = 0L,
        categories: List<String> = SponsorSegment.ALL_CATEGORIES
    ): SegmentResult = withContext(Dispatchers.IO) {
        val cacheKey = "$bvid:$cid"
        cache[cacheKey]?.let { entry ->
            if (System.currentTimeMillis() - entry.timestamp < CACHE_TTL_MS) {
                return@withContext SegmentResult(entry.segments)
            }
            cache.remove(cacheKey)
        }

        try {
            val hashPrefix = sha256Prefix(bvid)
            val params = buildList {
                categories.forEach { add("category=$it") }
            }
            val url = "$BASE_URL/skipSegments/$hashPrefix?${params.joinToString("&")}"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "MyBLBL/1.0")
                .header("origin", "com.tutu.myblbl")
                .header("x-ext-version", "1.0")
                .get()
                .build()

            val response = client.newCall(request).execute()
            when (response.code) {
                200 -> {
                    val body = response.body?.string() ?: return@withContext SegmentResult()
                    val type = object : TypeToken<List<HashVideoResponse>>() {}.type
                    val videos = gson.fromJson<List<HashVideoResponse>>(body, type)
                    val allSegments = videos
                        .find { it.videoID == bvid }
                        ?.segments
                        ?: emptyList()
                    val segments = normalizeSegments(allSegments)
                    Log.d(TAG, "$bvid: ${segments.size} segments")
                    cache[cacheKey] = CacheEntry(segments)
                    SegmentResult(segments)
                }
                404 -> SegmentResult()
                else -> {
                    Log.w(TAG, "$bvid: API error ${response.code}")
                    SegmentResult(error = "空降助手服务异常 (${response.code})")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "$bvid: ${e.message}")
            SegmentResult(error = "空降助手连接失败，请检查网络")
        }
    }

    private fun sha256Prefix(input: String, prefixLength: Int = 4): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }.take(prefixLength)
    }

    private fun normalizeSegments(
        segments: List<SponsorSegment>
    ): List<SponsorSegment> {
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

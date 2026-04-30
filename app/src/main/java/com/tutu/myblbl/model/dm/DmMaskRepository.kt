package com.tutu.myblbl.model.dm

import android.util.LruCache
import com.tutu.myblbl.core.common.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class DmMaskRepository {

    companion object {
        private const val TAG = "DmMaskRepository"
        private const val MAX_CACHE_SIZE = 3
    }

    private val cache = LruCache<Long, DmMaskData>(MAX_CACHE_SIZE)

    suspend fun downloadAndParse(maskUrl: String, cid: Long, fps: Int): DmMaskData? {
        return withContext(Dispatchers.IO) {
            try {
                val url = if (maskUrl.startsWith("//")) "https:$maskUrl" else maskUrl
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 10_000
                connection.readTimeout = 30_000
                connection.requestMethod = "GET"

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    AppLog.e(TAG, "Download webmask failed: ${connection.responseCode}")
                    return@withContext null
                }

                val data = connection.inputStream.readBytes()
                connection.disconnect()

                val maskData = WebmaskParser.parse(data)
                if (maskData != null) {
                    val result = maskData.copy(fps = fps)
                    cache.put(cid, result)
                    AppLog.d(TAG, "Webmask parsed: cid=$cid, segments=${result.segments.size}, fps=$fps")
                }
                maskData?.copy(fps = fps)
            } catch (e: Exception) {
                AppLog.e(TAG, "Download webmask error: ${e.message}")
                null
            }
        }
    }

    fun queryFrame(cid: Long, positionMs: Long): MaskFrame? {
        val maskData = cache.get(cid) ?: return null
        val segments = maskData.segments
        if (segments.isEmpty()) return null

        val segIndex = segments.binarySearchBy(positionMs) { it.timeMs }
            .let { if (it < 0) -(it + 1) - 1 else it }
            .coerceIn(0, segments.lastIndex)
        val segment = segments[segIndex]

        val relativeMs = positionMs - segment.timeMs
        val frameIntervalMs = if (maskData.fps > 0) 1000L / maskData.fps else 33L
        val frameIndex = (relativeMs / frameIntervalMs).toInt().coerceIn(0, segment.frames.lastIndex)

        return segment.frames.getOrNull(frameIndex)
    }

    fun clear(cid: Long) {
        cache.remove(cid)
    }

    fun clearAll() {
        cache.evictAll()
    }
}

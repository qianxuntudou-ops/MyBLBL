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
        cache.get(cid)?.let {
            AppLog.d(TAG, "Webmask cache hit: cid=$cid")
            return it
        }
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

                val maskData = WebmaskParser.parse(data, fps)
                if (maskData != null) {
                    cache.put(cid, maskData)
                    AppLog.d(TAG, "Webmask parsed: cid=$cid, segments=${maskData.rawSegments.size}, fps=$fps")
                }
                maskData
            } catch (e: Exception) {
                AppLog.e(TAG, "Download webmask error: ${e.message}")
                null
            }
        }
    }

    data class FrameResult(
        val frame: MaskFrame,
        val segIndex: Int,
        val frameIndex: Int,
        val segStartTimeMs: Long,
        val segDurationMs: Long,
        val totalFrames: Int
    )

    fun queryFrameWithIndex(cid: Long, positionMs: Long): FrameResult? {
        val maskData = cache.get(cid) ?: return null
        val segments = maskData.rawSegments
        if (segments.isEmpty()) return null

        val segIndex = segments.binarySearchBy(positionMs) { it.timeMs }
            .let { if (it < 0) -(it + 1) - 1 else it }
            .coerceIn(0, segments.lastIndex)
        val segment = segments[segIndex]

        var frames = segment.cachedFrames
        if (frames == null) {
            frames = WebmaskParser.parseSegmentFrames(segment, maskData.fps) ?: emptyList()
            segment.cachedFrames = frames
            AppLog.d(TAG, "Segment parsed: seg=$segIndex, timeMs=${segment.timeMs}, frames=${frames.size}")
        }
        if (frames.isEmpty()) return null

        val offsetMs = positionMs - segment.timeMs
        val segDurationMs = if (segIndex + 1 < segments.size) {
            (segments[segIndex + 1].timeMs - segment.timeMs).coerceAtLeast(1)
        } else {
            (frames.size.toLong() * 1000L / maskData.fps.coerceAtLeast(1)).coerceAtLeast(1)
        }
        // 四舍五入到最近一帧——floor 会让 mask 永远显示"过去最近的帧"，平均滞后 +半帧
        // (30fps 即 +16.7ms)。改为 round 后误差变成 ±半帧、平均 ~0，肉眼感知的"延迟"
        // 直接减半。等价于在 query 上加 segDurationMs/(2*frames.size) 的 lookahead。
        val frameIndex = ((offsetMs * frames.size + segDurationMs / 2) / segDurationMs).toInt()
            .coerceIn(0, frames.size - 1)

        val frame = frames.getOrNull(frameIndex) ?: return null
        return FrameResult(
            frame = frame, segIndex = segIndex, frameIndex = frameIndex,
            segStartTimeMs = segment.timeMs, segDurationMs = segDurationMs,
            totalFrames = frames.size
        )
    }

    fun clear(cid: Long) {
        cache.remove(cid)
    }

    fun clearAll() {
        cache.evictAll()
    }
}

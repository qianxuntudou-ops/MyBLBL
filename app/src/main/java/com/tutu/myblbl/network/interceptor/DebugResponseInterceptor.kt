package com.tutu.myblbl.network.interceptor

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import okio.buffer
import okio.sink
import java.io.ByteArrayOutputStream

class DebugResponseInterceptor : Interceptor {

    companion object {
        private const val TAG = "ApiRawJson"
        private val TARGET_PATHS = listOf(
            "x/web-interface/index/top/feed/rcmd",
            "x/web-interface/popular",
            "x/polymer/web-dynamic/desktop/v1/feed/video",
            "x/web-interface/history/cursor",
            "x/space/arc/list",
            "x/web-interface/dynamic/region",
            "x/v3/fav/resource/list"
        )
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val path = request.url.encodedPath

        val matched = TARGET_PATHS.any { path.contains(it) }
        if (!matched) return response

        try {
            val source = response.body?.source() ?: return response
            source.request(Long.MAX_VALUE)
            val buffer = source.buffer.clone()
            val rawJson = buffer.readUtf8()

            val tag = when {
                path.contains("history") -> "History"
                path.contains("dynamic/desktop") -> "Dynamic"
                path.contains("popular") -> "Hot"
                path.contains("rcmd") -> "Recommend"
                path.contains("fav") -> "Favorite"
                path.contains("space/arc") -> "UserSpace"
                path.contains("region") -> "Region"
                else -> "Video"
            }

            val itemsStart = rawJson.indexOf("\"item")
            if (itemsStart >= 0) {
                val listStart = rawJson.indexOf('[', itemsStart)
                if (listStart >= 0) {
                    var depth = 0
                    var firstItemStart = -1
                    var firstItemEnd = -1
                    for (i in listStart until rawJson.length.coerceAtMost(listStart + 50000)) {
                        when (rawJson[i]) {
                            '{' -> {
                                if (depth == 0 && firstItemStart == -1) firstItemStart = i
                                depth++
                            }
                            '}' -> {
                                depth--
                                if (depth == 0 && firstItemStart != -1 && firstItemEnd == -1) {
                                    firstItemEnd = i + 1
                                    break
                                }
                            }
                        }
                    }
                    if (firstItemStart >= 0 && firstItemEnd > firstItemStart) {
                        val firstItem = rawJson.substring(firstItemStart, firstItemEnd)
                        Log.d(TAG, "[$tag] FIRST_ITEM_JSON: $firstItem")
                    } else {
                        Log.d(TAG, "[$tag] RAW (truncated): ${rawJson.take(2000)}")
                    }
                } else {
                    Log.d(TAG, "[$tag] RAW (truncated): ${rawJson.take(2000)}")
                }
            } else {
                Log.d(TAG, "[$tag] RAW (truncated): ${rawJson.take(2000)}")
            }
        } catch (_: Exception) {
        }

        return response
    }
}

package com.tutu.myblbl.feature.player.view

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.LruCache
import com.kuaishou.akdanmaku.data.DanmakuVipGradientStyle
import com.tutu.myblbl.network.NetworkManager
import com.tutu.myblbl.core.common.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import java.util.concurrent.ConcurrentHashMap
import okhttp3.Request

object VipDanmakuTextureCache {

    private const val TAG = "VipDanmakuTextureCache"
    private const val MAX_CACHE_BYTES = 2 * 1024 * 1024
    private const val PLACEHOLDER_MAX_BYTES = 128

    private val bitmapCache = object : LruCache<String, Bitmap>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount.coerceAtLeast(1)
        }
    }
    private val loadingKeys = ConcurrentHashMap<String, Boolean>()

    private val loadingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun preloadStyles(styles: Collection<DanmakuVipGradientStyle>) {
        val urls = styles.asSequence()
            .flatMap { sequenceOf(it.strokeTextureUrl, it.fillTextureUrl) }
            .map(::normalizeUrl)
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
        if (urls.isEmpty()) return
        loadingScope.async {
            urls.map { url -> async { loadBitmap(url) } }.awaitAll()
        }
    }

    fun getBitmap(url: String): Bitmap? {
        val normalizedUrl = normalizeUrl(url)
        if (normalizedUrl.isBlank()) {
            return null
        }
        return synchronized(bitmapCache) {
            bitmapCache.get(normalizedUrl)
        }
    }

    private fun loadBitmap(url: String): Bitmap? {
        val normalizedUrl = normalizeUrl(url)
        if (normalizedUrl.isBlank()) {
            return null
        }
        getBitmap(normalizedUrl)?.let { return it }
        if (loadingKeys.putIfAbsent(normalizedUrl, true) != null) {
            return getBitmap(normalizedUrl)
        }
        return try {
            val request = Request.Builder()
                .url(normalizedUrl)
                .header("Referer", "https://www.bilibili.com/")
                .header("User-Agent", NetworkManager.getCurrentUserAgent())
                .build()
            NetworkManager.getOkHttpClient().newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    AppLog.w(TAG, "loadBitmap failed: code=${response.code} url=$normalizedUrl")
                    return null
                }
                val bytes = response.body?.bytes()
                val bitmap = bytes?.let { rawBytes ->
                    BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
                }
                if (bitmap != null) {
                    val rawSize = bytes.size
                    if (bitmap.isLikelyPlaceholderTexture(rawSize)) {
                        return null
                    }
                    synchronized(bitmapCache) {
                        bitmapCache.put(normalizedUrl, bitmap)
                    }
                } else {
                    AppLog.w(TAG, "loadBitmap decode failed: url=$normalizedUrl")
                }
                bitmap
            }
        } catch (throwable: Throwable) {
            AppLog.w(TAG, "loadBitmap exception: url=$normalizedUrl, message=${throwable.message}")
            null
        } finally {
            loadingKeys.remove(normalizedUrl)
        }
    }

    private fun Bitmap.isLikelyPlaceholderTexture(rawSize: Int): Boolean {
        if (rawSize in 1..PLACEHOLDER_MAX_BYTES && width <= 128 && height <= 128) {
            return true
        }
        val stepX = (width / 12).coerceAtLeast(1)
        val stepY = (height / 12).coerceAtLeast(1)
        var firstColor: Int? = null
        var hasDifferentColor = false
        var hasTransparentPixel = false
        var sampled = 0
        var y = 0
        while (y < height && sampled < 256) {
            var x = 0
            while (x < width && sampled < 256) {
                val color = getPixel(x, y)
                val alpha = Color.alpha(color)
                if (alpha in 1..254) {
                    hasTransparentPixel = true
                    break
                }
                val base = firstColor
                if (base == null) {
                    firstColor = color
                } else if (base != color) {
                    hasDifferentColor = true
                    break
                }
                sampled++
                x += stepX
            }
            if (hasTransparentPixel || hasDifferentColor) {
                break
            }
            y += stepY
        }
        return !hasDifferentColor && !hasTransparentPixel
    }

    private fun normalizeUrl(url: String): String {
        val value = url.trim()
        if (value.isEmpty()) {
            return ""
        }
        return when {
            value.startsWith("https://", ignoreCase = true) -> value
            value.startsWith("http://", ignoreCase = true) -> "https://${value.removePrefix("http://")}"
            value.startsWith("//") -> "https:$value"
            else -> value
        }
    }
}

package com.tutu.myblbl.core.ui.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.tutu.myblbl.network.NetworkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object CoverLoader {
    private val cache = LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 20).toInt())
    private val inFlight = ConcurrentHashMap<String, Boolean>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val semaphore = Semaphore(6)

    private val cdnClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
            .dispatcher(Dispatcher().apply { maxRequestsPerHost = 8 })
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private val refererHeader = "https://www.bilibili.com/"

    fun preload(rawUrls: List<String>) {
        for (rawUrl in rawUrls) {
            if (rawUrl.isBlank()) continue
            val optimizedUrl = ImageLoader.buildVideoCoverUrl(rawUrl)
            if (optimizedUrl.isBlank()) continue
            if (cache.get(optimizedUrl) != null) continue
            if (inFlight.putIfAbsent(optimizedUrl, true) != null) continue
            scope.launch {
                try {
                    semaphore.withPermit {
                        downloadAndDecode(optimizedUrl)?.let { bitmap ->
                            cache.put(optimizedUrl, bitmap)
                        }
                    }
                } finally {
                    inFlight.remove(optimizedUrl)
                }
            }
        }
    }

    fun get(optimizedUrl: String): Bitmap? = cache.get(optimizedUrl)

    fun evictAll() {
        cache.evictAll()
        inFlight.clear()
    }

    private fun downloadAndDecode(url: String): Bitmap? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("Referer", refererHeader)
                .header("User-Agent", NetworkManager.getCurrentUserAgent())
                .build()
            val response = cdnClient.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                return null
            }
            val bytes = response.body?.bytes() ?: return null
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            val sampleSize = calculateInSampleSize(options.outWidth, options.outHeight, 480, 270)
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
        } catch (_: Exception) {
            null
        }
    }

    private fun calculateInSampleSize(
        width: Int, height: Int,
        reqWidth: Int, reqHeight: Int
    ): Int {
        if (width <= 0 || height <= 0) return 1
        var inSampleSize = 1
        if (height > reqHeight * 2 || width > reqWidth * 2) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight &&
                halfWidth / inSampleSize >= reqWidth
            ) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}

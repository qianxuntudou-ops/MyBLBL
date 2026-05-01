package com.tutu.myblbl.core.ui.image

import android.app.ActivityManager
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.util.DebugLogger
import com.tutu.myblbl.BuildConfig
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.network.NetworkManager
import okio.Path.Companion.toOkioPath
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 全局 Coil [ImageLoader] 工厂：
 * - 与 [NetworkManager] 共用同一个 OkHttp client，HTTP/2、连接池、DNS、TLS session 全部复用；
 * - 内存缓存按可用堆大小的 15% 自动调整；
 * - 磁盘缓存放外置 cache 512MB；
 * - 默认开启 crossfade 200ms 与 hardware bitmap，TV 上滚动更流畅。
 *
 * 通过 [bootstrap] 主动注册：在 `MyBLBLApplication.onCreate()` 中调用一次即可，
 * 不依赖任何注解处理器。
 */
object MyBLBLCoilInitializer {

    private const val TAG = "CoilInit"
    private val initialized = AtomicBoolean(false)

    fun bootstrap(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        SingletonImageLoader.setSafe { platformContext ->
            buildImageLoader(platformContext, appContext)
        }
        AppLog.i(TAG, "Coil ImageLoader factory registered")
    }

    private fun buildImageLoader(
        platformContext: PlatformContext,
        appContext: Context
    ): ImageLoader {
        val activityManager =
            appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryClassMb = activityManager.memoryClass
        val memoryCacheBytes = (memoryClassMb * 1024L * 1024 * 0.15).toLong()
        val diskCacheDir = File(
            appContext.externalCacheDir ?: appContext.cacheDir,
            "coil_cache"
        ).apply { if (!exists()) mkdirs() }

        return ImageLoader.Builder(platformContext)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizeBytes(memoryCacheBytes)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(diskCacheDir.toOkioPath())
                    .maxSizeBytes(512L * 1024 * 1024)
                    .build()
            }
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = { NetworkManager.getOkHttpClient() }
                    )
                )
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .crossfade(200)
            .allowHardware(true)
            .apply {
                if (BuildConfig.DEBUG) {
                    logger(DebugLogger())
                }
            }
            .build()
    }
}

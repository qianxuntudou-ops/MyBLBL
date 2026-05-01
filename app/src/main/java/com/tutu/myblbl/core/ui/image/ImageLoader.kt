package com.tutu.myblbl.core.ui.image

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.annotation.DrawableRes
import coil3.Image
import coil3.SingletonImageLoader
import coil3.asDrawable
import coil3.dispose
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.error
import coil3.request.placeholder
import coil3.request.target
import coil3.request.transformations
import coil3.size.Precision
import coil3.size.Scale
import coil3.target.ImageViewTarget
import coil3.toBitmap
import coil3.transform.CircleCropTransformation
import coil3.transform.RoundedCornersTransformation
import coil3.transform.Transformation
import com.tutu.myblbl.R
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.core.common.settings.AppSettingsDataStore
import com.tutu.myblbl.network.NetworkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform

/**
 * 项目内统一的图片加载入口。底层走 Coil 3.x，与 [NetworkManager] 共享 OkHttp 栈。
 *
 * 公开 API 与之前的 Glide 实现完全兼容，UI 层无需任何改动。
 */
object ImageLoader {

    private const val TAG = "ImageLoader"

    private const val KEY_IMAGE_QUALITY = "image_quality"
    private const val KEY_IMAGE_QUALITY_LEVEL = "imageQualityLevel"

    private val appSettings: AppSettingsDataStore get() = KoinPlatform.getKoin().get()

    @Volatile
    private var cachedImageQualityLevel: Int? = null
    private var imageQualityFlowStarted = false
    private val imageQualityScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val bilibiliHeaders: NetworkHeaders by lazy {
        NetworkHeaders.Builder()
            .add("Referer", "https://www.bilibili.com/")
            .add("User-Agent", NetworkManager.getCurrentUserAgent())
            .build()
    }

    // ---------- 公开 API ----------

    fun load(
        imageView: ImageView,
        url: String?,
        placeholder: Int = 0,
        error: Int = 0
    ) {
        enqueue(
            imageView = imageView,
            url = buildOptimizedCommonImageUrl(imageView, url),
            placeholderRes = placeholder,
            errorRes = error,
            crossfade = true
        )
    }

    fun load(
        imageView: ImageView,
        url: String?,
        placeholder: Drawable?,
        error: Drawable?
    ) {
        enqueue(
            imageView = imageView,
            url = buildOptimizedCommonImageUrl(imageView, url),
            placeholderDrawable = placeholder,
            errorDrawable = error,
            crossfade = true
        )
    }

    fun loadCircle(
        imageView: ImageView,
        url: String?,
        placeholder: Int = 0,
        error: Int = 0
    ) {
        val normalizedUrl = normalizeUrl(url)
        val optimizedUrl = buildOptimizedAvatarUrl(imageView, url)
        val canFallbackToRawUrl = optimizedUrl.isNotBlank() &&
            normalizedUrl.isNotBlank() &&
            optimizedUrl != normalizedUrl

        enqueue(
            imageView = imageView,
            url = optimizedUrl,
            placeholderRes = placeholder,
            errorRes = if (canFallbackToRawUrl) 0 else error,
            transformations = listOf(CircleCropTransformation()),
            crossfade = false,
            onError = if (canFallbackToRawUrl) {
                {
                    enqueue(
                        imageView = imageView,
                        url = normalizedUrl,
                        placeholderRes = placeholder,
                        errorRes = error,
                        transformations = listOf(CircleCropTransformation()),
                        crossfade = false
                    )
                }
            } else null
        )
    }

    /**
     * 加载本地 drawable（含 GIF / WebP 动图）。Coil 通过 component 自动识别动图格式。
     */
    fun loadDrawableRes(
        imageView: ImageView,
        @DrawableRes resId: Int,
        circleCrop: Boolean = false
    ) {
        enqueue(
            imageView = imageView,
            url = "",
            data = resId,
            transformations = if (circleCrop) listOf(CircleCropTransformation()) else emptyList(),
            crossfade = false
        )
    }

    /**
     * 取消 ImageView 上正在进行的图片加载，并清空当前显示。
     *
     * 这里必须用 Coil 的 [dispose] 而不是 enqueue 一个 `data = null` 的请求：
     * 后者会让 Coil 把 null 请求保存到 ViewTargetRequestManager，下次 view 重新
     * attach 时自动 restart，抛 `NullRequestDataException`（实测 RecyclerView
     * 复用 ItemView 时高频出现）。
     */
    fun clear(imageView: ImageView) {
        imageView.dispose()
        imageView.setImageDrawable(null)
    }

    /**
     * 加载远程图片为 [Bitmap]，主要服务于播放器拖动预览这种需要自行裁剪的场景。
     * 调用方持有返回的 [Disposable]，需要取消时调用 `dispose()`。
     */
    fun loadBitmap(
        context: Context,
        url: String,
        applyBilibiliHeaders: Boolean = true,
        onSuccess: (Bitmap) -> Unit,
        onFailed: () -> Unit = {}
    ): Disposable {
        val builder = ImageRequest.Builder(context).data(url)
        if (applyBilibiliHeaders && isBilibiliImageUrl(url)) {
            builder.httpHeaders(bilibiliHeaders)
        }
        builder.target(
            onSuccess = { image: Image ->
                onSuccess(image.toBitmap())
            },
            onError = { _: Image? -> onFailed() }
        )
        return SingletonImageLoader.get(context).enqueue(builder.build())
    }

    fun loadCenterCrop(
        imageView: ImageView,
        url: String?,
        placeholder: Int = 0,
        error: Int = 0
    ) {
        enqueue(
            imageView = imageView,
            url = buildOptimizedCommonImageUrl(imageView, url),
            placeholderRes = placeholder,
            errorRes = error,
            scale = Scale.FILL,
            crossfade = true
        )
    }

    /**
     * 视频封面统一只做 CenterCrop。圆角通过 ImageView 的 outlineProvider 在 GPU 层裁出，
     * 既避免了像素级 transform 的 CPU 开销，也让磁盘缓存命中后零成本复用。
     */
    fun loadVideoCover(
        imageView: ImageView,
        url: String?,
        placeholder: Int = R.drawable.default_video,
        error: Int = R.drawable.default_video,
        onPortraitDetected: ((Boolean) -> Unit)? = null
    ) {
        val optimizedUrl = buildOptimizedVideoCoverUrl(imageView, url)
        enqueue(
            imageView = imageView,
            url = optimizedUrl,
            placeholderRes = placeholder,
            errorRes = error,
            scale = Scale.FILL,
            crossfade = false,
            onSuccess = if (onPortraitDetected != null) { drawable ->
                val w = drawable.intrinsicWidth
                val h = drawable.intrinsicHeight
                onPortraitDetected(w > 0 && h > 0 && h > w)
            } else null,
            onError = if (onPortraitDetected != null) {
                { AppLog.e(TAG, "loadVideoCover FAILED: url=$optimizedUrl") }
            } else null
        )
    }

    fun loadSeriesCover(
        imageView: ImageView,
        url: String?,
        placeholder: Int = R.drawable.default_video,
        error: Int = R.drawable.default_video
    ) {
        val radiusPx = imageView.context.resources.getDimensionPixelSize(R.dimen.px15).toFloat()
        enqueue(
            imageView = imageView,
            url = buildOptimizedSeriesCoverUrl(imageView, url),
            placeholderRes = placeholder,
            errorRes = error,
            scale = Scale.FILL,
            transformations = listOf(RoundedCornersTransformation(radiusPx)),
            crossfade = false
        )
    }

    fun loadWithListener(
        imageView: ImageView,
        url: String?,
        onLoadSuccess: () -> Unit = {},
        onLoadFailed: () -> Unit = {}
    ) {
        enqueue(
            imageView = imageView,
            url = buildOptimizedCommonImageUrl(imageView, url),
            crossfade = true,
            onSuccess = { onLoadSuccess() },
            onError = { onLoadFailed() }
        )
    }

    fun detectPortraitFromCover(
        imageView: ImageView,
        url: String?,
        callback: (Boolean) -> Unit
    ) {
        if (url.isNullOrBlank()) return
        val rawUrl = normalizeUrl(url)
        if (!isBilibiliImageUrl(rawUrl)) return
        // 故意不带 _1c：本探测目的就是读原图 aspect ratio 判断是否竖屏，
        // 加 _1c 后被强制裁成 1:1，h > w 永远 false，检测直接失效。
        val probeUrl = appendImageSuffix(rawUrl, "@120w_120h.webp")
        val request = ImageRequest.Builder(imageView.context)
            .data(probeUrl)
            .applyBilibiliHeadersIfNeeded(probeUrl)
            .listener(
                onSuccess = { _, result ->
                    val drawable = result.image.asDrawable(imageView.resources)
                    if (!imageView.isAttachedToWindow) return@listener
                    val w = drawable.intrinsicWidth
                    val h = drawable.intrinsicHeight
                    callback(w > 0 && h > 0 && h > w)
                }
            )
            .build()
        SingletonImageLoader.get(imageView.context).enqueue(request)
    }

    fun clearMemory(context: Context) {
        SingletonImageLoader.get(context).memoryCache?.clear()
    }

    fun clearDiskCache(context: Context) {
        val loader = SingletonImageLoader.get(context)
        Thread {
            runCatching { loader.diskCache?.clear() }
        }.start()
    }

    /**
     * 在卡片真正进入 RecyclerView 之前，把首屏可见的视频封面下到磁盘 + 内存缓存。
     *
     * 不指定 [coil3.request.ImageRequest.Builder.size]：让 Coil 用 ORIGINAL 解码原图尺寸，
     * 既保证后续 ImageView 加载时一定能命中内存缓存（cache size 不小于任何 view target），
     * 也避免 prefetch 解出的 bitmap 比真实 view 还小（实测 390×219 vs 419×236 时
     * EXACT 模式下会被判 cache invalid 而重新走网络/磁盘）。
     *
     * Coil 的 `enqueue` 任意线程安全，调用方无需担心线程切换。
     */
    fun prefetchVideoCovers(context: Context, urls: List<String?>) {
        if (urls.isEmpty()) return
        val appContext = context.applicationContext
        val finalUrls = urls.asSequence()
            .mapNotNull { it?.takeIf { url -> url.isNotBlank() } }
            .distinct()
            .map { url ->
                val normalized = normalizeUrl(url)
                if (isBilibiliImageUrl(normalized)) {
                    appendImageSuffix(normalized, "@480w_270h_1c.webp")
                } else {
                    normalized
                }
            }
            .toList()
        if (finalUrls.isEmpty()) return
        val loader = SingletonImageLoader.get(appContext)
        finalUrls.forEach { finalUrl ->
            val request = ImageRequest.Builder(appContext)
                .data(finalUrl)
                .applyBilibiliHeadersIfNeeded(finalUrl)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .build()
            loader.enqueue(request)
        }
    }

    fun invalidateImageQualityCache() {
        cachedImageQualityLevel = null
    }

    // ---------- 内部实现 ----------

    private fun enqueue(
        imageView: ImageView,
        url: String,
        data: Any? = null,
        placeholderRes: Int = 0,
        errorRes: Int = 0,
        placeholderDrawable: Drawable? = null,
        errorDrawable: Drawable? = null,
        scale: Scale = Scale.FILL,
        crossfade: Boolean = true,
        transformations: List<Transformation> = emptyList(),
        onSuccess: ((Drawable) -> Unit)? = null,
        onError: (() -> Unit)? = null
    ) {
        val ctx = imageView.context
        if (ctx is Activity && ctx.isDestroyed) return

        val resolvedData: Any? = data ?: url.ifBlank { null }
        val builder = ImageRequest.Builder(ctx)
            .data(resolvedData)
            .target(ImageViewTarget(imageView))
            .scale(scale)
            // 一律用 INEXACT：B 站 image processor 输出尺寸是「上限不放大」，
            // 实际 bitmap 经常略小于 ImageView 像素尺寸，EXACT 会让本来命中的内存缓存被
            // 判 invalid 而重新解码。INEXACT 允许 cache size ≥ 0.5×target 即命中，
            // 视频卡这种 4~10% 尺寸偏差视觉无感，但帧率收益明显。
            .precision(Precision.INEXACT)
            .crossfade(crossfade)
            .applyBilibiliHeadersIfNeeded(url)

        when {
            placeholderDrawable != null -> builder.placeholder(placeholderDrawable)
            placeholderRes != 0 -> builder.placeholder(placeholderRes)
        }
        when {
            errorDrawable != null -> builder.error(errorDrawable)
            errorRes != 0 -> builder.error(errorRes)
        }
        if (transformations.isNotEmpty()) {
            builder.transformations(transformations)
        }
        if (onSuccess != null || onError != null) {
            builder.listener(
                onSuccess = { _, result ->
                    onSuccess?.invoke(result.image.asDrawable(ctx.resources))
                },
                onError = { _, _ ->
                    onError?.invoke()
                }
            )
        }
        SingletonImageLoader.get(ctx).enqueue(builder.build())
    }

    private fun ImageRequest.Builder.applyBilibiliHeadersIfNeeded(
        url: String
    ): ImageRequest.Builder {
        return if (isBilibiliImageUrl(url)) httpHeaders(bilibiliHeaders) else this
    }

    // ---------- URL 规范化 / 尺寸优化 ----------

    private fun normalizeUrl(url: String?): String {
        if (url.isNullOrBlank()) return ""
        return when {
            url.startsWith("https://") -> url
            url.startsWith("http://") -> "https://${url.removePrefix("http://")}"
            url.startsWith("//") -> "https:$url"
            url.startsWith("bfs/") -> "https://i0.hdslb.com/$url"
            else -> url
        }
    }

    // 所有挡位均使用 _1c（center crop）后缀：
    // - 不带 _1c 时 B 站 image processor 是 max-fit，原图比例稍偏即吐出非标尺寸
    //   bitmap（实测出现过 390×219、466×260 这种歪尺寸）；
    // - 带 _1c 后输出严格按 W×H 等比裁切，所有 cover 出来必然标准 16:9 / 1:1 / 3:4，
    //   memory cache 命中判定也更稳定。

    private fun buildOptimizedVideoCoverUrl(imageView: ImageView, url: String?): String {
        val normalized = normalizeUrl(url)
        if (!isBilibiliImageUrl(normalized)) return normalized
        val suffix = when (resolveImageQualityLevel(imageView)) {
            0 -> "@240w_135h_1c.webp"
            2 -> "@672w_378h_1c.webp"
            else -> "@480w_270h_1c.webp"
        }
        return appendImageSuffix(normalized, suffix)
    }

    private fun buildOptimizedCommonImageUrl(imageView: ImageView, url: String?): String {
        val normalized = normalizeUrl(url)
        if (!isBilibiliImageUrl(normalized)) return normalized
        val suffix = when (resolveImageQualityLevel(imageView)) {
            0 -> "@240w_240h_1c.webp"
            2 -> "@960w_960h_1c.webp"
            else -> "@480w_480h_1c.webp"
        }
        return appendImageSuffix(normalized, suffix)
    }

    private fun buildOptimizedAvatarUrl(imageView: ImageView, url: String?): String {
        val normalized = normalizeUrl(url)
        if (!isBilibiliImageUrl(normalized)) return normalized
        val suffix = when (resolveImageQualityLevel(imageView)) {
            0 -> "@120w_120h_1c.webp"
            2 -> "@360w_360h_1c.webp"
            else -> "@240w_240h_1c.webp"
        }
        return appendImageSuffix(normalized, suffix)
    }

    private fun buildOptimizedSeriesCoverUrl(imageView: ImageView, url: String?): String {
        val normalized = normalizeUrl(url)
        if (!isBilibiliImageUrl(normalized)) return normalized
        val suffix = when (resolveImageQualityLevel(imageView)) {
            0 -> "@160w_213h_1c.webp"
            2 -> "@466w_622h_1c.webp"
            else -> "@320w_426h_1c.webp"
        }
        return appendImageSuffix(normalized, suffix)
    }

    private fun resolveImageQualityLevel(imageView: ImageView): Int {
        cachedImageQualityLevel?.let { return it }
        ensureImageQualityFlowCollection()
        appSettings.getCachedString(KEY_IMAGE_QUALITY)?.let { label ->
            return qualityLabelToLevel(label).also { cachedImageQualityLevel = it }
        }
        val level = appSettings.getCachedInt(KEY_IMAGE_QUALITY_LEVEL, -1)
        if (level >= 0) {
            return level.coerceIn(0, 2).also { cachedImageQualityLevel = it }
        }
        return 1.also { cachedImageQualityLevel = it }
    }

    @Synchronized
    private fun ensureImageQualityFlowCollection() {
        if (imageQualityFlowStarted) return
        imageQualityFlowStarted = true
        imageQualityScope.launch {
            combine(
                appSettings.getStringFlow(KEY_IMAGE_QUALITY),
                appSettings.getIntFlow(KEY_IMAGE_QUALITY_LEVEL, -1)
            ) { _, _ -> }.collect {
                cachedImageQualityLevel = null
            }
        }
    }

    private fun qualityLabelToLevel(label: String): Int {
        return when (label.trim()) {
            "低尺寸" -> 0
            "高尺寸" -> 2
            else -> 1
        }
    }

    private fun isBilibiliImageUrl(url: String): Boolean {
        return url.contains("hdslb.com", ignoreCase = true) ||
            url.contains("biliimg.com", ignoreCase = true) ||
            url.startsWith("bfs/")
    }

    private fun appendImageSuffix(url: String, suffix: String): String {
        if (url.isBlank()) return url
        val queryPart = url.substringAfter('?', "")
        val baseUrl = if (queryPart.isEmpty()) url else url.substringBefore('?')
        val cleanedBase = stripExistingImageProcessSuffix(baseUrl)
        val optimized = cleanedBase + suffix
        return if (queryPart.isEmpty()) optimized else "$optimized?$queryPart"
    }

    private fun stripExistingImageProcessSuffix(url: String): String {
        val extensionIndex = url.lastIndexOf('.')
        if (extensionIndex == -1 || extensionIndex >= url.length - 1) return url
        val processSuffixIndex = url.indexOf('@', startIndex = extensionIndex + 1)
        if (processSuffixIndex == -1) return url
        return url.substring(0, processSuffixIndex)
    }
}

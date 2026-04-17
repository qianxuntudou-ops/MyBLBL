package com.tutu.myblbl.core.ui.image

import android.app.Activity
import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.tutu.myblbl.R
import com.tutu.myblbl.core.common.settings.AppSettingsDataStore
import com.tutu.myblbl.network.NetworkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform

object ImageLoader {

    private const val KEY_IMAGE_QUALITY = "image_quality"
    private const val KEY_IMAGE_QUALITY_LEVEL = "imageQualityLevel"

    private val appSettings: AppSettingsDataStore get() = KoinPlatform.getKoin().get()

    @Volatile
    private var cachedImageQualityLevel: Int? = null
    private var imageQualityFlowStarted = false
    private val imageQualityScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private fun isContextAlive(view: View): Boolean {
        val context = view.context
        return !(context is Activity && context.isDestroyed)
    }

    fun load(
        imageView: ImageView,
        url: String?,
        placeholder: Int = 0,
        error: Int = 0
    ) {
        if (!isContextAlive(imageView)) return
        Glide.with(imageView)
            .load(buildImageModel(buildOptimizedCommonImageUrl(imageView, url)))
            .placeholder(placeholder)
            .error(error)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .transition(DrawableTransitionOptions.withCrossFade())
            .into(imageView)
    }
    
    fun load(
        imageView: ImageView,
        url: String?,
        placeholder: Drawable?,
        error: Drawable?
    ) {
        if (!isContextAlive(imageView)) return
        Glide.with(imageView)
            .load(buildImageModel(buildOptimizedCommonImageUrl(imageView, url)))
            .placeholder(placeholder)
            .error(error)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .transition(DrawableTransitionOptions.withCrossFade())
            .into(imageView)
    }
    
    fun loadCircle(
        imageView: ImageView,
        url: String?,
        placeholder: Int = 0,
        error: Int = 0
    ) {
        if (!isContextAlive(imageView)) return
        val normalizedUrl = normalizeUrl(url)
        val optimizedUrl = buildOptimizedAvatarUrl(imageView, url)
        val requestManager = Glide.with(imageView)

        val requestBuilder = requestManager
            .load(buildImageModel(optimizedUrl))
            .placeholder(placeholder)
            .circleCrop()
            .diskCacheStrategy(DiskCacheStrategy.ALL)

        val canFallbackToRawUrl = optimizedUrl.isNotBlank() &&
            normalizedUrl.isNotBlank() &&
            optimizedUrl != normalizedUrl

        if (canFallbackToRawUrl) {
            requestBuilder
                .error(
                    requestManager
                        .load(buildImageModel(normalizedUrl))
                        .placeholder(placeholder)
                        .error(error)
                        .circleCrop()
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                )
                .into(imageView)
        } else {
            requestBuilder
                .error(error)
                .into(imageView)
        }
    }
    
    fun loadCenterCrop(
        imageView: ImageView,
        url: String?,
        placeholder: Int = 0,
        error: Int = 0
    ) {
        if (!isContextAlive(imageView)) return
        Glide.with(imageView)
            .load(buildImageModel(buildOptimizedCommonImageUrl(imageView, url)))
            .placeholder(placeholder)
            .error(error)
            .centerCrop()
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .transition(DrawableTransitionOptions.withCrossFade())
            .into(imageView)
    }

    private var cachedCoverRequestOptions: RequestOptions? = null

    private fun getCoverRequestOptions(context: android.content.Context): RequestOptions {
        return cachedCoverRequestOptions ?: RequestOptions()
            .transform(CenterCrop(), RoundedCorners(context.resources.getDimensionPixelSize(R.dimen.px15)))
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .also { cachedCoverRequestOptions = it }
    }

    fun loadVideoCover(
        imageView: ImageView,
        url: String?,
        placeholder: Int = R.drawable.default_video,
        error: Int = R.drawable.default_video
    ) {
        if (!isContextAlive(imageView)) return
        Glide.with(imageView)
            .load(buildImageModel(buildOptimizedVideoCoverUrl(imageView, url)))
            .placeholder(placeholder)
            .error(error)
            .apply(getCoverRequestOptions(imageView.context))
            .into(imageView)
    }

    fun loadSeriesCover(
        imageView: ImageView,
        url: String?,
        placeholder: Int = R.drawable.default_video,
        error: Int = R.drawable.default_video
    ) {
        if (!isContextAlive(imageView)) return
        val radius = imageView.context.resources.getDimensionPixelSize(R.dimen.px15)
        Glide.with(imageView)
            .load(buildImageModel(buildOptimizedSeriesCoverUrl(imageView, url)))
            .placeholder(placeholder)
            .error(error)
            .apply(RequestOptions().transform(CenterCrop(), RoundedCorners(radius)))
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .into(imageView)
    }
    
    fun loadWithListener(
        imageView: ImageView,
        url: String?,
        onLoadSuccess: () -> Unit = {},
        onLoadFailed: () -> Unit = {}
    ) {
        if (!isContextAlive(imageView)) return
        Glide.with(imageView)
            .load(buildImageModel(buildOptimizedCommonImageUrl(imageView, url)))
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .transition(DrawableTransitionOptions.withCrossFade())
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean
                ): Boolean {
                    onLoadFailed()
                    return false
                }
                
                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    onLoadSuccess()
                    return false
                }
            })
            .into(imageView)
    }
    
    fun clearMemory(context: Context) {
        Glide.get(context).clearMemory()
    }
    
    fun clearDiskCache(context: Context) {
        Thread {
            Glide.get(context).clearDiskCache()
        }.start()
    }

    private fun applyRoundedClip(imageView: ImageView, radius: Int) {
        imageView.clipToOutline = true
        imageView.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius.toFloat())
            }
        }
        imageView.invalidateOutline()
    }

    private fun normalizeUrl(url: String?): String {
        if (url.isNullOrBlank()) {
            return ""
        }
        return when {
            url.startsWith("https://") -> url
            url.startsWith("http://") -> "https://${url.removePrefix("http://")}"
            url.startsWith("//") -> "https:$url"
            else -> url
        }
    }

    private fun buildOptimizedVideoCoverUrl(imageView: ImageView, url: String?): String {
        val normalized = normalizeUrl(url)
        if (!isBilibiliImageUrl(normalized)) {
            return normalized
        }
        val suffix = when (resolveImageQualityLevel(imageView)) {
            0 -> "@240w_135h_1c.webp"
            2 -> "@672w_378h.webp"
            else -> "@480w_270h_1c.webp"
        }
        return appendImageSuffix(normalized, suffix)
    }

    private fun buildOptimizedCommonImageUrl(imageView: ImageView, url: String?): String {
        val normalized = normalizeUrl(url)
        if (!isBilibiliImageUrl(normalized)) {
            return normalized
        }
        val suffix = when (resolveImageQualityLevel(imageView)) {
            0 -> "@240w_240h_1c.webp"
            2 -> "@960w_960h.webp"
            else -> "@480w_480h_1c.webp"
        }
        return appendImageSuffix(normalized, suffix)
    }

    private fun buildOptimizedAvatarUrl(imageView: ImageView, url: String?): String {
        val normalized = normalizeUrl(url)
        if (!isBilibiliImageUrl(normalized)) {
            return normalized
        }
        val suffix = when (resolveImageQualityLevel(imageView)) {
            0 -> "@120w_120h_1c.webp"
            2 -> "@360w_360h.webp"
            else -> "@240w_240h_1c.webp"
        }
        return appendImageSuffix(normalized, suffix)
    }

    private fun buildOptimizedSeriesCoverUrl(imageView: ImageView, url: String?): String {
        val normalized = normalizeUrl(url)
        if (!isBilibiliImageUrl(normalized)) {
            return normalized
        }
        val suffix = when (resolveImageQualityLevel(imageView)) {
            0 -> "@160w_213h_1c.webp"
            2 -> "@466w_622h.webp"
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

    fun invalidateImageQualityCache() {
        cachedImageQualityLevel = null
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

    private fun levelToQualityLabel(level: Int): String {
        return when (level) {
            0 -> "低尺寸"
            2 -> "高尺寸"
            else -> "中尺寸"
        }
    }

    private fun isBilibiliImageUrl(url: String): Boolean {
        return url.contains("hdslb.com", ignoreCase = true) ||
            url.contains("biliimg.com", ignoreCase = true)
    }

    private fun appendImageSuffix(url: String, suffix: String): String {
        if (url.isBlank()) {
            return url
        }
        val queryPart = url.substringAfter('?', "")
        val baseUrl = if (queryPart.isEmpty()) url else url.substringBefore('?')
        val cleanedBase = stripExistingImageProcessSuffix(baseUrl)
        val optimized = cleanedBase + suffix
        return if (queryPart.isEmpty()) optimized else "$optimized?$queryPart"
    }

    private fun stripExistingImageProcessSuffix(url: String): String {
        val extensionIndex = url.lastIndexOf('.')
        if (extensionIndex == -1 || extensionIndex >= url.length - 1) {
            return url
        }

        val processSuffixIndex = url.indexOf('@', startIndex = extensionIndex + 1)
        if (processSuffixIndex == -1) {
            return url
        }

        return url.substring(0, processSuffixIndex)
    }

    private fun buildImageModel(url: String): Any {
        if (!isBilibiliImageUrl(url)) {
            return url
        }
        return GlideUrl(
            url,
            LazyHeaders.Builder()
                .addHeader("Referer", "https://www.bilibili.com/")
                .addHeader("User-Agent", NetworkManager.getCurrentUserAgent())
                .build()
        )
    }

}

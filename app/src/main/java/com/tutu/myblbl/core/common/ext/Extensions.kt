package com.tutu.myblbl.core.common.ext

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.tutu.myblbl.MyBLBLApplication
import com.tutu.myblbl.core.common.settings.AppSettingsDataStore
import org.koin.mp.KoinPlatform
import java.io.Serializable

private const val VALUE_ON = "开"
private const val VALUE_OFF = "关"
private const val VALUE_FILTER_LEVEL_MIN = 1
private const val VALUE_FILTER_LEVEL_MAX = 10
private val DEFAULT_START_PAGE_OPTIONS = arrayOf("推荐", "热门", "番剧", "影视")

private val appSettings: AppSettingsDataStore
    get() = KoinPlatform.getKoin().get()

fun Context.toast(message: String, duration: Int = Toast.LENGTH_SHORT) {
    Toast.makeText(this, message, duration).show()
}

fun Context.toast(@StringRes resId: Int, duration: Int = Toast.LENGTH_SHORT) {
    Toast.makeText(this, resId, duration).show()
}

fun Context.isOpenDetailFirstEnabled(): Boolean {
    return getToggleSetting("show_video_detail", false)
}

fun Context.getDefaultStartPageIndex(): Int {
    val savedLabel = appSettings.getCachedString("default_start_page")
    val resolvedIndex = DEFAULT_START_PAGE_OPTIONS.indexOf(savedLabel)
        .takeIf { it >= 0 } ?: 0
    appSettings.putIntAsync("defaultStartPage", resolvedIndex)
    return resolvedIndex
}

fun Context.isAppFullscreenEnabled(): Boolean {
    return getToggleSetting("fullscreen_app", true)
}

fun Context.shouldShowPlayerDebugInfo(): Boolean {
    return getToggleSetting("show_debug", false)
}

fun Context.isSimpleOperationKeyEnabled(): Boolean {
    return getToggleSetting("simple_key_press", false)
}

fun Context.getDanmakuSmartFilterLevel(): Int {
    val raw = appSettings.getCachedString("dm_filter_weight")?.trim() ?: return 0
    val parsed = raw.toIntOrNull()
    if (parsed != null && parsed in VALUE_FILTER_LEVEL_MIN..VALUE_FILTER_LEVEL_MAX) {
        return parsed
    }
    return when (raw) {
        "低" -> 1
        "中" -> 5
        "高" -> 10
        VALUE_ON -> 1
        else -> 0
    }
}

fun Context.isDanmakuSmartFilterEnabled(): Boolean {
    return getDanmakuSmartFilterLevel() > 0
}

fun normalizeDanmakuSmartFilterValue(value: String?): String {
    val trimmed = value?.trim()
    val parsed = trimmed?.toIntOrNull()
    if (parsed != null && parsed in VALUE_FILTER_LEVEL_MIN..VALUE_FILTER_LEVEL_MAX) {
        return trimmed
    }
    return when (trimmed) {
        "低" -> "1"
        "中" -> "5"
        "高" -> "10"
        VALUE_ON -> "1"
        else -> VALUE_OFF
    }
}

fun Context.isVipColorfulDanmakuAllowed(): Boolean {
    return getToggleSetting("dm_allow_vip_colorful_dm", true)
}

fun Context.isAdvancedDanmakuEnabled(): Boolean {
    return getToggleSetting("dm_show_advanced", true)
}

fun Context.getHomeDefaultStartPageIndex(maxIndex: Int, defaultIndex: Int = 1): Int {
    val safeMaxIndex = maxIndex.coerceAtLeast(0)
    val clampedDefault = defaultIndex.coerceIn(0, safeMaxIndex)
    val mappedFromLabel = when (appSettings.getCachedString("default_start_page")?.trim()) {
        "推荐" -> 0
        "热门" -> 1
        "番剧" -> 2
        "影视" -> 3
        else -> null
    }
    if (mappedFromLabel != null) {
        val normalized = mappedFromLabel.coerceIn(0, safeMaxIndex)
        if (appSettings.getCachedInt("defaultStartPage", Int.MIN_VALUE) != normalized) {
            appSettings.putIntAsync("defaultStartPage", normalized)
        }
        return normalized
    }
    return appSettings.getCachedInt("defaultStartPage", clampedDefault)
        .coerceIn(0, safeMaxIndex)
}

fun Context.hasPermission(permission: String): Boolean {
    return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}

fun Context.openAppSettings() {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    startActivity(intent)
}

inline fun <reified T : Serializable> Bundle.serializableCompat(key: String): T? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getSerializable(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getSerializable(key) as? T
    }
}

fun View.visible() {
    visibility = View.VISIBLE
}

fun View.invisible() {
    visibility = View.INVISIBLE
}

fun View.gone() {
    visibility = View.GONE
}

fun View.setVisible(visible: Boolean) {
    visibility = if (visible) View.VISIBLE else View.GONE
}

fun View.onClick(action: () -> Unit) {
    setOnClickListener { action() }
}

fun View.onLongClick(action: () -> Boolean) {
    setOnLongClickListener { action() }
}

private fun getToggleSetting(key: String, defaultValue: Boolean): Boolean {
    val fallback = if (defaultValue) VALUE_ON else VALUE_OFF
    return (appSettings.getCachedString(key) ?: fallback) == VALUE_ON
}

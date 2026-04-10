package com.tutu.myblbl.utils

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import java.io.Serializable

private const val PREFS_APP_SETTINGS = "app_settings"
private const val VALUE_ON = "开"
private const val VALUE_OFF = "关"
private const val VALUE_FILTER_LEVEL_1 = "1"
private const val VALUE_FILTER_LEVEL_2 = "2"
private const val VALUE_FILTER_LEVEL_3 = "3"
private val DEFAULT_START_PAGE_OPTIONS = arrayOf("推荐", "热门", "番剧", "影视")

fun Context.toast(message: String, duration: Int = Toast.LENGTH_SHORT) {
    Toast.makeText(this, message, duration).show()
}

fun Context.toast(@StringRes resId: Int, duration: Int = Toast.LENGTH_SHORT) {
    Toast.makeText(this, resId, duration).show()
}

fun Context.isOpenDetailFirstEnabled(): Boolean {
    return getToggleSetting(
        key = "show_video_detail",
        defaultValue = false
    )
}

fun Context.getDefaultStartPageIndex(): Int {
    val prefs = getSharedPreferences(PREFS_APP_SETTINGS, Context.MODE_PRIVATE)
    val savedLabel = prefs.getStringSafely("default_start_page")
    val resolvedIndex = DEFAULT_START_PAGE_OPTIONS.indexOf(savedLabel)
        .takeIf { it >= 0 }
        ?: 0
    prefs.edit().putInt("defaultStartPage", resolvedIndex).apply()
    return resolvedIndex
}

fun Context.isAppFullscreenEnabled(): Boolean {
    return getToggleSetting(
        key = "fullscreen_app",
        defaultValue = true
    )
}

fun Context.shouldShowPlayerDebugInfo(): Boolean {
    return getToggleSetting(
        key = "show_debug",
        defaultValue = false
    )
}

fun Context.isSimpleOperationKeyEnabled(): Boolean {
    return getToggleSetting(
        key = "simple_key_press",
        defaultValue = false
    )
}

fun Context.getDanmakuSmartFilterLevel(): Int {
    return when (getSettingString("dm_filter_weight")?.trim()) {
        VALUE_FILTER_LEVEL_1 -> 1
        VALUE_FILTER_LEVEL_2 -> 2
        VALUE_FILTER_LEVEL_3 -> 3
        "低" -> 1
        "中" -> 2
        "高" -> 3
        VALUE_ON -> 1
        else -> 0
    }
}

fun Context.isDanmakuSmartFilterEnabled(): Boolean {
    return getDanmakuSmartFilterLevel() > 0
}

fun normalizeDanmakuSmartFilterValue(value: String?): String {
    return when (value?.trim()) {
        VALUE_FILTER_LEVEL_1 -> VALUE_FILTER_LEVEL_1
        VALUE_FILTER_LEVEL_2 -> VALUE_FILTER_LEVEL_2
        VALUE_FILTER_LEVEL_3 -> VALUE_FILTER_LEVEL_3
        "低" -> VALUE_FILTER_LEVEL_1
        "中" -> VALUE_FILTER_LEVEL_2
        "高" -> VALUE_FILTER_LEVEL_3
        VALUE_ON -> VALUE_FILTER_LEVEL_1
        else -> VALUE_OFF
    }
}

fun Context.isVipColorfulDanmakuAllowed(): Boolean {
    return getToggleSetting(
        key = "dm_allow_vip_colorful_dm",
        defaultValue = true
    )
}

fun Context.isAdvancedDanmakuEnabled(): Boolean {
    return getToggleSetting(
        key = "dm_show_advanced",
        defaultValue = true
    )
}

fun Context.getHomeDefaultStartPageIndex(maxIndex: Int, defaultIndex: Int = 1): Int {
    val safeMaxIndex = maxIndex.coerceAtLeast(0)
    val clampedDefault = defaultIndex.coerceIn(0, safeMaxIndex)
    val prefs = getSharedPreferences(PREFS_APP_SETTINGS, Context.MODE_PRIVATE)
    val mappedFromLabel = when (prefs.getString("default_start_page", null)?.trim()) {
        "推荐" -> 0
        "热门" -> 1
        "番剧" -> 2
        "影视" -> 3
        else -> null
    }
    if (mappedFromLabel != null) {
        val normalized = mappedFromLabel.coerceIn(0, safeMaxIndex)
        if (prefs.getInt("defaultStartPage", Int.MIN_VALUE) != normalized) {
            prefs.edit().putInt("defaultStartPage", normalized).apply()
        }
        return normalized
    }
    val savedIndex = prefs.getInt("defaultStartPage", clampedDefault)
    return savedIndex.coerceIn(0, safeMaxIndex)
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

private fun SharedPreferences.getStringSafely(key: String): String? {
    return runCatching { getString(key, null) }.getOrNull()
}

private fun Context.getSettingString(key: String): String? {
    val prefs = getSharedPreferences(PREFS_APP_SETTINGS, Context.MODE_PRIVATE)
    return prefs.getStringSafely(key)
}

private fun Context.getToggleSetting(key: String, defaultValue: Boolean): Boolean {
    val fallback = if (defaultValue) VALUE_ON else VALUE_OFF
    return (getSettingString(key) ?: fallback) == VALUE_ON
}


package com.tutu.myblbl.core.common.format

import java.text.DecimalFormat
import java.util.Locale

object NumberUtils {
    
    private val decimalFormat = DecimalFormat("#.#")
    private val wanFormat = DecimalFormat("#.#万")
    private val yiFormat = DecimalFormat("#.#亿")
    
    fun formatCount(count: Long): String {
        return when {
            count >= 100000000 -> {
                yiFormat.format(count / 100000000.0)
            }
            count >= 10000 -> {
                wanFormat.format(count / 10000.0)
            }
            else -> {
                count.toString()
            }
        }
    }
    
    fun formatDuration(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        
        return when {
            hours > 0 -> String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, secs)
            else -> String.format(Locale.getDefault(), "%02d:%02d", minutes, secs)
        }
    }
    
    fun formatTimestamp(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp
        
        return when {
            diff < 60000 -> "刚刚"
            diff < 3600000 -> "${diff / 60000}分钟前"
            diff < 86400000 -> "${diff / 3600000}小时前"
            diff < 2592000000L -> "${diff / 86400000}天前"
            diff < 31536000000L -> "${diff / 2592000000L}个月前"
            else -> "${diff / 31536000000L}年前"
        }
    }
}

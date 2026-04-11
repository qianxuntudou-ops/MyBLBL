package com.tutu.myblbl.core.common.time

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object TimeUtils {

    private val historyTimeFormat = SimpleDateFormat("HH:mm", Locale.CHINA)
    private val historyMonthDayFormat = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
    private val historyYearFormat = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
    private val publishMonthDayFormat = SimpleDateFormat("MM-dd", Locale.CHINA)
    private val publishYearFormat = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)

    fun formatTime(timestamp: Long): String {
        return if (timestamp > 0) {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp * 1000))
        } else {
            ""
        }
    }

    fun formatShortDate(timestamp: Long): String {
        return if (timestamp > 0) {
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestamp * 1000))
        } else {
            ""
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

    fun formatRelativeTime(timestamp: Long): String {
        if (timestamp <= 0) {
            return ""
        }
        val now = System.currentTimeMillis() / 1000
        val diff = now - timestamp
        if (diff < 0) {
            return publishMonthDayFormat.format(Date(timestamp * 1000))
        }

        return when {
            diff < 60 -> "刚刚"
            diff < 3600 -> "${diff / 60}分钟前"
            diff < 86400 -> "${diff / 3600}小时前"
            diff < 86400 * 4 -> "${diff / 86400}天前"
            else -> {
                val targetDate = Date(timestamp * 1000)
                if (isSameYear(targetDate, Date(now * 1000))) {
                    publishMonthDayFormat.format(targetDate)
                } else {
                    publishYearFormat.format(targetDate)
                }
            }
        }
    }

    fun formatHistoryViewTime(timestamp: Long): String {
        if (timestamp <= 0L) {
            return ""
        }
        val targetDate = Date(timestamp * 1000)
        val nowDate = Date()
        val diffDays = ((nowDate.time - targetDate.time) / 1000L) / 86400L
        return when (diffDays) {
            0L -> "今天 ${historyTimeFormat.format(targetDate)}"
            1L -> "昨天 ${historyTimeFormat.format(targetDate)}"
            2L -> "前天 ${historyTimeFormat.format(targetDate)}"
            else -> {
                if (isSameYear(targetDate, nowDate)) {
                    historyMonthDayFormat.format(targetDate)
                } else {
                    historyYearFormat.format(targetDate)
                }
            }
        }
    }

    private fun isSameYear(first: Date, second: Date): Boolean {
        val firstCalendar = Calendar.getInstance().apply { time = first }
        val secondCalendar = Calendar.getInstance().apply { time = second }
        return firstCalendar.get(Calendar.YEAR) == secondCalendar.get(Calendar.YEAR)
    }
}

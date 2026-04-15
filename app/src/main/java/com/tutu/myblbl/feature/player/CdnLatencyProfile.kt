package com.tutu.myblbl.feature.player

import android.net.Uri
import com.tutu.myblbl.core.common.log.AppLog
import java.util.concurrent.ConcurrentHashMap

internal object CdnLatencyProfile {

    private const val TAG = "CdnLatencyProfile"
    private const val MAX_ENTRIES = 32
    private const val RECORD_TTL_MS = 10 * 60 * 1000L

    private data class LatencyRecord(
        val host: String,
        val ttfbMs: Long,
        val timestampMs: Long
    )

    private val records = ConcurrentHashMap<String, MutableList<LatencyRecord>>()

    fun recordTtfb(url: String, ttfbMs: Long) {
        if (ttfbMs <= 0L) return
        val host = extractHost(url) ?: return
        val record = LatencyRecord(host = host, ttfbMs = ttfbMs, timestampMs = System.currentTimeMillis())
        records.compute(host) { _, existing ->
            val list = (existing ?: mutableListOf()).toMutableList()
            list.add(record)
            if (list.size > 5) list.removeAt(0)
            list
        }
        if (records.size > MAX_ENTRIES) {
            val now = System.currentTimeMillis()
            records.entries.removeIf { (_, v) ->
                v.lastOrNull()?.let { now - it.timestampMs > RECORD_TTL_MS } ?: true
            }
        }
    }

    fun averageTtfbMs(url: String): Long {
        val host = extractHost(url) ?: return Long.MAX_VALUE
        val list = records[host] ?: return Long.MAX_VALUE
        if (list.isEmpty()) return Long.MAX_VALUE
        return list.map { it.ttfbMs }.sorted().let { sorted ->
            sorted.drop(sorted.size / 4).dropLast(sorted.size / 4).average().toLong()
                .takeIf { it > 0L } ?: sorted.median()
        }
    }

    fun sortUrlsByLatency(urls: List<String>): List<String> {
        if (urls.size <= 1) return urls
        return urls.sortedBy { averageTtfbMs(it) }
    }

    private fun extractHost(url: String): String? {
        return runCatching { Uri.parse(url).host }.getOrNull()
    }

    private fun List<Long>.median(): Long {
        if (isEmpty()) return Long.MAX_VALUE
        val sorted = sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2 else sorted[mid]
    }
}

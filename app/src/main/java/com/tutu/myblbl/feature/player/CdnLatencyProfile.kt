package com.tutu.myblbl.feature.player

import android.net.Uri
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

internal object CdnLatencyProfile {
    private const val MAX_ENTRIES = 32
    private const val RECORD_TTL_MS = 10 * 60 * 1000L

    private const val CDN_PREF_BILIVIDEO = 0
    private const val CDN_PREF_MCDN = 1
    private const val CDN_PREF_OTHER = 2

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
        return urls.sortedWith(compareBy({ cdnPreference(it) }, { averageTtfbMs(it) }))
    }

    private fun cdnPreference(url: String): Int {
        val host = runCatching { Uri.parse(url).host }.getOrNull()
            ?.lowercase(Locale.US) ?: return CDN_PREF_OTHER
        val isMcdn = host.contains("mcdn") && host.contains("bilivideo")
        val isBilivideo = host.contains("bilivideo") && !isMcdn
        return when {
            isBilivideo -> CDN_PREF_BILIVIDEO
            isMcdn -> CDN_PREF_MCDN
            else -> CDN_PREF_OTHER
        }
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

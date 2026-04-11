package com.tutu.myblbl.feature.player

import com.tutu.myblbl.model.player.PlayInfoModel

/**
 * Short-lived in-memory cache for UGC playurl responses.
 *
 * Purpose:
 * - Avoid immediate re-request storms when the user exits and re-enters the same video quickly.
 * - Reduce the chance of triggering risk-control responses (e.g. -351) after a successful first play.
 */
internal object VideoPlayerPlayInfoCache {

    private const val TTL_MS = 90_000L
    private const val MAX_ENTRIES = 8

    private data class Entry(
        val playInfo: PlayInfoModel,
        val savedAtMs: Long
    )

    private val cache = object : LinkedHashMap<String, Entry>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean {
            return size > MAX_ENTRIES
        }
    }

    @Synchronized
    fun get(bvid: String, cid: Long): PlayInfoModel? {
        if (bvid.isBlank() || cid <= 0L) return null
        val key = buildKey(bvid, cid)
        val entry = cache[key] ?: return null
        val now = System.currentTimeMillis()
        if (now - entry.savedAtMs > TTL_MS) {
            cache.remove(key)
            return null
        }
        return entry.playInfo
    }

    @Synchronized
    fun put(bvid: String, cid: Long, playInfo: PlayInfoModel) {
        if (bvid.isBlank() || cid <= 0L) return
        cache[buildKey(bvid, cid)] = Entry(
            playInfo = playInfo,
            savedAtMs = System.currentTimeMillis()
        )
    }

    @Synchronized
    fun invalidate(bvid: String, cid: Long) {
        if (bvid.isBlank() || cid <= 0L) return
        cache.remove(buildKey(bvid, cid))
    }

    private fun buildKey(bvid: String, cid: Long): String = "${bvid.trim()}|$cid"
}


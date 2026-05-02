package com.tutu.myblbl.feature.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.MediaSource
import com.tutu.myblbl.model.player.PlayInfoModel

/**
 * Short-lived in-memory cache for UGC playurl responses.
 *
 * Purpose:
 * - Avoid immediate re-request storms when the user exits and re-enters the same video quickly.
 * - Reduce the chance of triggering risk-control responses (e.g. -351) after a successful first play.
 */
@UnstableApi
internal object VideoPlayerPlayInfoCache {

    private const val TTL_MS = 600_000L
    private const val MAX_ENTRIES = 32
    private const val MAX_MEDIA_SOURCE_ENTRIES = 4

    @UnstableApi
    private data class Entry(
        val playInfo: PlayInfoModel,
        val savedAtMs: Long,
        val mediaSource: MediaSource? = null,
        val qualityId: Int? = null,
        val audioId: Int? = null,
        val codec: Any? = null
    )

    private val cache = object : LinkedHashMap<String, Entry>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean {
            return size > MAX_ENTRIES
        }
    }

    private val steinsGateKeys = mutableSetOf<String>()

    @Synchronized
    fun markAsSteinsGate(bvid: String, cid: Long) {
        if (bvid.isBlank() || cid <= 0L) return
        steinsGateKeys.add(buildKey(bvid, cid))
    }

    @Synchronized
    fun isSteinsGate(bvid: String, cid: Long): Boolean {
        if (bvid.isBlank() || cid <= 0L) return false
        return steinsGateKeys.contains(buildKey(bvid, cid))
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

    @UnstableApi
    @Synchronized
    fun getMediaSource(bvid: String, cid: Long): MediaSource? {
        if (bvid.isBlank() || cid <= 0L) return null
        val key = buildKey(bvid, cid)
        val entry = cache[key] ?: return null
        val now = System.currentTimeMillis()
        if (now - entry.savedAtMs > TTL_MS) {
            cache.remove(key)
            return null
        }
        return entry.mediaSource
    }

    @Synchronized
    fun put(bvid: String, cid: Long, playInfo: PlayInfoModel) {
        if (bvid.isBlank() || cid <= 0L) return
        cache[buildKey(bvid, cid)] = Entry(
            playInfo = playInfo,
            savedAtMs = System.currentTimeMillis()
        )
    }

    @UnstableApi
    @Synchronized
    fun putWithMediaSource(
        bvid: String, cid: Long, playInfo: PlayInfoModel,
        mediaSource: MediaSource, qualityId: Int?, audioId: Int?, codec: Any?
    ) {
        if (bvid.isBlank() || cid <= 0L) return
        trimMediaSources()
        cache[buildKey(bvid, cid)] = Entry(
            playInfo = playInfo,
            savedAtMs = System.currentTimeMillis(),
            mediaSource = mediaSource,
            qualityId = qualityId,
            audioId = audioId,
            codec = codec
        )
    }

    @Synchronized
    private fun trimMediaSources() {
        val withMs = cache.entries.filter { it.value.mediaSource != null }
        if (withMs.size < MAX_MEDIA_SOURCE_ENTRIES) return
        withMs.take(withMs.size - MAX_MEDIA_SOURCE_ENTRIES + 1).forEach { (key, entry) ->
            cache[key] = entry.copy(mediaSource = null, qualityId = null, audioId = null, codec = null)
        }
    }

    @Synchronized
    fun updateLastPlayPosition(bvid: String, cid: Long, lastPlayTime: Long, lastPlayCid: Long) {
        if (bvid.isBlank() || cid <= 0L) return
        val key = buildKey(bvid, cid)
        val entry = cache[key] ?: return
        cache[key] = entry.copy(
            playInfo = entry.playInfo.copy(
                lastPlayTime = lastPlayTime,
                lastPlayCid = lastPlayCid
            )
        )
    }

    @Synchronized
    fun invalidate(bvid: String, cid: Long) {
        if (bvid.isBlank() || cid <= 0L) return
        cache.remove(buildKey(bvid, cid))
    }

    private fun buildKey(bvid: String, cid: Long): String = "${bvid.trim()}|$cid"
}


package com.tutu.myblbl.feature.player.interaction

import com.tutu.myblbl.model.interaction.InteractionModel
import com.tutu.myblbl.network.api.ApiService
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Repository for loading and caching interactive video nodes.
 *
 * Uses an LRU cache (max 5 entries) to avoid redundant network calls
 * and supports parallel preloading of upcoming nodes.
 */
class InteractionRepository(
    private val apiService: ApiService
) {

    // ── LRU cache ────────────────────────────────────────────────────────────

    private val cache = object : LinkedHashMap<Long, InteractionModel>(5, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, InteractionModel>?): Boolean {
            return size > MAX_CACHE_SIZE
        }
    }

    companion object {
        private const val MAX_CACHE_SIZE = 5
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Load a single interaction node by edgeId.
     * Returns cached data if available, otherwise fetches from the API.
     *
     * @return the interaction model, or null on failure
     */
    suspend fun loadNode(
        bvid: String,
        aid: Long,
        graphVersion: Long,
        edgeId: Long
    ): InteractionModel? {
        // Check cache first
        cache[edgeId]?.let { return it }

        // Fetch from API
        return try {
            val response = apiService.getInteractionVideoInfo(bvid, aid, graphVersion, edgeId)
            if (response.code == 0) {
                response.data?.also { cache[edgeId] = it }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Preload multiple interaction nodes in parallel.
     * Skips edgeIds that are already cached.
     */
    suspend fun preloadNodes(
        bvid: String,
        aid: Long,
        graphVersion: Long,
        edgeIds: List<Long>
    ) {
        val uncached = edgeIds.filter { it !in cache }
        if (uncached.isEmpty()) return

        try {
            coroutineScope {
                uncached.map { edgeId ->
                    async {
                        val response = apiService.getInteractionVideoInfo(bvid, aid, graphVersion, edgeId)
                        if (response.code == 0) {
                            response.data?.let { cache[edgeId] = it }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Silently ignore preload failures
        }
    }

    /**
     * Get a cached node without making any network calls.
     *
     * @return the cached interaction model, or null if not cached
     */
    fun getCachedNode(edgeId: Long): InteractionModel? {
        return cache[edgeId]
    }

    /**
     * Clear all cached interaction nodes.
     */
    fun clearCache() {
        cache.clear()
    }
}

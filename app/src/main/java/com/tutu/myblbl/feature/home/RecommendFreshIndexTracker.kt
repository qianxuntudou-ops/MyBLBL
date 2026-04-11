package com.tutu.myblbl.feature.home

/**
 * Tracks the feed cursor used by the recommend API.
 *
 * The home recommend endpoint expects `fresh_idx` to keep moving forward.
 * Reusing `fresh_idx=1` on every page-1 reload returns the same anonymous feed,
 * which makes pull-to-refresh and tab reselect look like a no-op.
 */
internal class RecommendFreshIndexTracker {

    private var sequenceStartFreshIdx = 1
    private var highestResolvedFreshIdx = 0
    private var hasLoadedFirstPage = false

    fun resolve(page: Int): Int {
        val normalizedPage = page.coerceAtLeast(1)
        if (normalizedPage == 1 && hasLoadedFirstPage) {
            sequenceStartFreshIdx = highestResolvedFreshIdx + 1
        }
        val freshIdx = sequenceStartFreshIdx + normalizedPage - 1
        highestResolvedFreshIdx = maxOf(highestResolvedFreshIdx, freshIdx)
        return freshIdx
    }

    fun markFirstPageLoaded() {
        hasLoadedFirstPage = true
    }
}

package com.tutu.myblbl.feature.player

internal class VideoPlayerQualityPolicy(
    private val qualityLadder: List<Int> = DEFAULT_QUALITY_LADDER
) {

    fun buildCandidates(preferredQualityId: Int?): List<Int> {
        val requestedQualityId = preferredQualityId ?: FALLBACK_DEFAULT_QUALITY
        val knownIndex = qualityLadder.indexOf(requestedQualityId)
        val lowerKnownQualities = if (knownIndex >= 0) {
            qualityLadder.drop(knownIndex + 1)
        } else {
            qualityLadder.filter { it < requestedQualityId }
        }
        val limitedStepDown = lowerKnownQualities.take(MAX_STEPDOWN_COUNT)
        return buildList {
            add(requestedQualityId)
            addAll(limitedStepDown)
            SAFE_FALLBACK_QUALITIES.forEach(::add)
        }.distinct()
    }

    companion object {
        private val DEFAULT_QUALITY_LADDER = listOf(127, 126, 129, 125, 120, 116, 112, 100, 80, 74, 64, 32, 16, 6)
        private const val FALLBACK_DEFAULT_QUALITY = 80
        private const val MAX_STEPDOWN_COUNT = 6
        private val SAFE_FALLBACK_QUALITIES = listOf(80, 64, 32, 16)
    }
}

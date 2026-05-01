package com.tutu.myblbl.feature.player.sponsor

import android.util.Log

class SponsorBlockUseCase {

    companion object {
        private const val TAG = "SponsorBlockUseCase"
        private const val GRACE_PERIOD_MS = 3000L
        private const val SEEK_BACK_THRESHOLD_MS = 2000L
    }

    enum class SkipAction { AUTO_SKIP, SHOW_BUTTON }

    data class SkipResult(
        val segment: SponsorSegment,
        val action: SkipAction
    )

    private var segments: List<SponsorSegment> = emptyList()
    private var nextSegmentIndex: Int = 0
    private val skippedIds = mutableSetOf<String>()
    private var lastPositionMs: Long = 0
    private var lastAutoSkipTime: Long = 0

    suspend fun loadSegments(bvid: String, cid: Long) {
        reset()
        try {
            segments = SponsorBlockRepository.getSegments(bvid, cid)
            nextSegmentIndex = 0
            Log.d(TAG, "加载了 ${segments.size} 个空降片段")
        } catch (e: Exception) {
            Log.w(TAG, "加载空降片段失败: ${e.message}")
        }
    }

    fun checkPosition(positionMs: Long, autoSkip: Boolean): SkipResult? {
        if (segments.isEmpty()) return null

        val isGracePeriod = System.currentTimeMillis() - lastAutoSkipTime < GRACE_PERIOD_MS

        if (!isGracePeriod) {
            if (positionMs < lastPositionMs - SEEK_BACK_THRESHOLD_MS) {
                resetSkippedForSeek(positionMs)
            }
            lastPositionMs = positionMs
        } else {
            if (positionMs > lastPositionMs) {
                lastPositionMs = positionMs
            }
        }

        while (nextSegmentIndex < segments.size) {
            val candidate = segments[nextSegmentIndex]
            if (candidate.UUID in skippedIds || positionMs > candidate.endTimeMs) {
                nextSegmentIndex++
                continue
            }
            break
        }

        val segment = segments.getOrNull(nextSegmentIndex)
            ?.takeIf { positionMs in it.startTimeMs..it.endTimeMs }
            ?: return null

        val action = if (autoSkip) {
            skippedIds.add(segment.UUID)
            lastAutoSkipTime = System.currentTimeMillis()
            nextSegmentIndex++
            SkipAction.AUTO_SKIP
        } else {
            SkipAction.SHOW_BUTTON
        }

        return SkipResult(segment, action)
    }

    fun skipCurrent(): Long? {
        val segment = segments.getOrNull(nextSegmentIndex) ?: return null
        skippedIds.add(segment.UUID)
        nextSegmentIndex++
        return segment.endTimeMs
    }

    fun dismissCurrent() {
        val segment = segments.getOrNull(nextSegmentIndex) ?: return
        skippedIds.add(segment.UUID)
        nextSegmentIndex++
    }

    fun onUserSeek(positionMs: Long) {
        resetSkippedForSeek(positionMs)
        nextSegmentIndex = segments.indexOfFirst { positionMs <= it.endTimeMs }
            .takeIf { it >= 0 } ?: segments.size
        lastPositionMs = positionMs
        lastAutoSkipTime = 0
    }

    fun getSegments(): List<SponsorSegment> = segments

    fun reset() {
        segments = emptyList()
        nextSegmentIndex = 0
        skippedIds.clear()
        lastPositionMs = 0
        lastAutoSkipTime = 0
    }

    private fun resetSkippedForSeek(positionMs: Long) {
        val keep = skippedIds.filterTo(mutableSetOf()) { id ->
            segments.firstOrNull { it.UUID == id }?.let { positionMs > it.endTimeMs } ?: true
        }
        skippedIds.clear()
        skippedIds.addAll(keep)
    }
}
package com.tutu.myblbl.feature.player

import android.widget.ProgressBar
import androidx.core.view.isVisible

class SlimTimelineRenderer(
    private val progressBar: ProgressBar,
    private val shouldShowProvider: () -> Boolean
) : TimelineRenderer {

    private var active = false

    override fun show(positionMs: Long, durationMs: Long) {
        active = shouldShowProvider()
        if (!active) {
            progressBar.isVisible = false
            return
        }
        progressBar.isVisible = true
        val safeDuration = durationMs.coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val safePosition = positionMs.coerceAtLeast(0L).coerceAtMost(safeDuration.toLong()).toInt()
        progressBar.max = safeDuration
        progressBar.progress = safePosition
    }

    override fun showPreview(targetPositionMs: Long, durationMs: Long) {
        active = true
        progressBar.isVisible = true
        val safeDuration = durationMs.coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val safePosition = targetPositionMs.coerceAtLeast(0L).coerceAtMost(safeDuration.toLong()).toInt()
        progressBar.max = safeDuration
        progressBar.progress = safePosition
    }

    override fun hide() {
        active = false
        progressBar.isVisible = false
    }

    override fun isActive(): Boolean = active
}

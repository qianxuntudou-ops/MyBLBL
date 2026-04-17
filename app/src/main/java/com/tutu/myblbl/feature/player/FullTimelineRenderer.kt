package com.tutu.myblbl.feature.player

import android.view.View
import com.tutu.myblbl.feature.player.view.DefaultTimeBar

class FullTimelineRenderer(
    private val timeBar: DefaultTimeBar
) : TimelineRenderer {

    private var active = false

    override fun show(positionMs: Long, durationMs: Long) {
        active = true
        timeBar.visibility = View.VISIBLE
        val safeDuration = durationMs.coerceAtLeast(0L).toInt()
        val safePosition = positionMs.coerceAtLeast(0L).coerceAtMost(safeDuration.toLong()).toInt()
        timeBar.setDuration(safeDuration.toLong())
        timeBar.setPosition(safePosition.toLong())
        timeBar.setBufferedPosition(0L)
    }

    override fun showPreview(targetPositionMs: Long, durationMs: Long) {
        val safeDuration = durationMs.coerceAtLeast(0L).toInt()
        val safePosition = targetPositionMs.coerceAtLeast(0L).coerceAtMost(safeDuration.toLong()).toInt()
        timeBar.setDuration(safeDuration.toLong())
        timeBar.setPosition(safePosition.toLong())
    }

    override fun hide() {
        active = false
    }

    override fun isActive(): Boolean = active
}

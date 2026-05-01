package com.tutu.myblbl.feature.player.sponsor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class SponsorProgressMarkerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var segments: List<SponsorSegment> = emptyList()
    private var durationMs: Long = 0L
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setSegments(segments: List<SponsorSegment>) {
        this.segments = segments
        invalidate()
    }

    fun setDuration(durationMs: Long) {
        this.durationMs = durationMs
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (segments.isEmpty() || durationMs <= 0L) return

        val width = width.toFloat()
        val height = height.toFloat()

        for (segment in segments) {
            val startRatio = segment.startTimeMs.toFloat() / durationMs
            val endRatio = segment.endTimeMs.toFloat() / durationMs
            val left = startRatio * width
            val right = endRatio * width

            paint.color = (segment.categoryColor() and 0x00FFFFFFL).toInt() or 0x99000000.toInt()
            canvas.drawRect(left, 0f, right, height, paint)
        }
    }
}
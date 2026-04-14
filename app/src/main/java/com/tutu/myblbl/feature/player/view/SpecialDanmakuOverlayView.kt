package com.tutu.myblbl.feature.player.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.graphics.withSave
import com.tutu.myblbl.model.dm.SpecialDanmakuAction
import com.tutu.myblbl.model.dm.SpecialDanmakuModel
import kotlin.math.max

class SpecialDanmakuOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val fillPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeMiter = 10f
    }
    private val fontMetrics = Paint.FontMetrics()
    private var items: List<SpecialDanmakuModel> = emptyList()
    private var enabled = true
    private var globalAlpha = 1f
    private var textScale = 1f
    private var playbackSpeed = 1f
    private var visibleAreaRatio = 1f
    private var allowTop = true
    private var allowBottom = true
    private var isPlaying = false
    private var basePositionMs = 0L
    private var baseRealtimeMs = 0L

    init {
        isClickable = false
        isFocusable = false
    }

    fun setData(items: List<SpecialDanmakuModel>) {
        this.items = items
            .sortedBy { it.progress }
            .map { model ->
                if (model.animations.size <= 1) {
                    model
                } else {
                    model.copy(animations = model.animations.sortedBy { it.startMs })
                }
            }
        invalidate()
    }

    fun setRenderingConfig(
        enabled: Boolean,
        alpha: Float,
        textScale: Float,
        visibleAreaRatio: Float,
        allowTop: Boolean,
        allowBottom: Boolean
    ) {
        val resolvedAlpha = alpha.coerceIn(0f, 1f)
        val resolvedTextScale = textScale.coerceAtLeast(0.5f)
        val resolvedArea = visibleAreaRatio.coerceIn(0f, 1f)
        if (this.enabled == enabled &&
            globalAlpha == resolvedAlpha &&
            this.textScale == resolvedTextScale &&
            this.visibleAreaRatio == resolvedArea &&
            this.allowTop == allowTop &&
            this.allowBottom == allowBottom
        ) {
            return
        }
        this.enabled = enabled
        globalAlpha = resolvedAlpha
        this.textScale = resolvedTextScale
        this.visibleAreaRatio = resolvedArea
        this.allowTop = allowTop
        this.allowBottom = allowBottom
        visibility = if (enabled) VISIBLE else GONE
        invalidate()
    }

    fun syncPlayback(positionMs: Long, playing: Boolean, speed: Float) {
        val resolvedPosition = positionMs.coerceAtLeast(0L)
        val resolvedSpeed = speed.coerceAtLeast(0.1f)
        if (basePositionMs == resolvedPosition &&
            isPlaying == playing &&
            playbackSpeed == resolvedSpeed
        ) {
            return
        }
        basePositionMs = resolvedPosition
        baseRealtimeMs = SystemClock.elapsedRealtime()
        isPlaying = playing
        playbackSpeed = resolvedSpeed
        invalidate()
    }

    fun clear() {
        items = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!enabled || items.isEmpty() || width <= 0 || height <= 0) {
            return
        }

        val currentPositionMs = currentPlaybackPositionMs()
        val maxDurationMs = items.maxOfOrNull { it.durationMs } ?: 0L
        val windowStart = currentPositionMs - maxDurationMs
        val searchStart = binarySearchStart(currentPositionMs)
        for (i in searchStart until items.size) {
            val model = items[i]
            if (model.progress.toLong() > currentPositionMs) break
            val localTimeMs = currentPositionMs - model.progress.toLong()
            if (localTimeMs < 0L || localTimeMs > model.durationMs) {
                continue
            }
            val state = resolveState(model, localTimeMs)
            if (state.alpha <= 0f) {
                continue
            }
            if (state.y > visibleAreaRatio) {
                continue
            }
            if (!allowTop && state.y <= 0.2f) {
                continue
            }
            if (!allowBottom && state.y >= 0.8f) {
                continue
            }
            drawModel(canvas, model, state)
        }

        if (isPlaying && enabled) {
            postInvalidateOnAnimation()
        }
    }

    private fun binarySearchStart(currentPositionMs: Long): Int {
        var low = 0
        var high = items.size - 1
        var result = items.size
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (items[mid].progress.toLong() + items[mid].durationMs >= currentPositionMs) {
                result = mid
                high = mid - 1
            } else {
                low = mid + 1
            }
        }
        return result
    }

    private fun drawModel(canvas: Canvas, model: SpecialDanmakuModel, state: ResolvedState) {
        configurePaint(fillPaint, model, state, Paint.Style.FILL)
        configurePaint(strokePaint, model, state, Paint.Style.STROKE)

        val lines = model.content.split('\n')
        val lineHeight = computeLineHeight(fillPaint)
        val textWidths = lines.map { fillPaint.measureText(it) }
        val contentWidth = textWidths.maxOrNull() ?: 0f
        val contentHeight = max(lineHeight, lineHeight * lines.size)
        val anchorOffsetX = contentWidth * model.anchorX
        val anchorOffsetY = contentHeight * model.anchorY
        val xPx = width * state.x
        val yPx = height * state.y

        canvas.withSave {
            translate(xPx, yPx)
            rotate(state.rotation)
            scale(state.scaleX, state.scaleY)
            val baselineStart = -anchorOffsetY - fillPaint.fontMetrics.ascent
            lines.forEachIndexed { index, line ->
                val baseline = baselineStart + lineHeight * index
                if (state.strokeWidth > 0f && state.strokeColor != 0) {
                    drawText(line, -anchorOffsetX, baseline, strokePaint)
                }
                drawText(line, -anchorOffsetX, baseline, fillPaint)
            }
        }
    }

    private fun configurePaint(
        paint: TextPaint,
        model: SpecialDanmakuModel,
        state: ResolvedState,
        style: Paint.Style
    ) {
        paint.isAntiAlias = true
        paint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            state.fontSize * textScale,
            resources.displayMetrics
        )
        paint.typeface = if (model.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        paint.isFakeBoldText = model.bold
        paint.style = style
        paint.color = if (style == Paint.Style.FILL) state.color.toArgbColor() else state.strokeColor.toArgbColor()
        paint.alpha = (state.alpha * globalAlpha * 255).toInt().coerceIn(0, 255)
        if (style == Paint.Style.STROKE) {
            paint.strokeWidth = state.strokeWidth.coerceAtLeast(1f)
        }
    }

    private fun computeLineHeight(paint: TextPaint): Float {
        paint.getFontMetrics(fontMetrics)
        return fontMetrics.descent - fontMetrics.ascent
    }

    private fun currentPlaybackPositionMs(): Long {
        if (!isPlaying) {
            return basePositionMs
        }
        val elapsedMs = SystemClock.elapsedRealtime() - baseRealtimeMs
        return (basePositionMs + elapsedMs * playbackSpeed).toLong().coerceAtLeast(0L)
    }

    private fun resolveState(model: SpecialDanmakuModel, localTimeMs: Long): ResolvedState {
        var state = ResolvedState(
            x = model.x,
            y = model.y,
            alpha = model.alpha,
            color = model.color,
            fontSize = model.fontSize.toFloat(),
            scaleX = model.scaleX,
            scaleY = model.scaleY,
            rotation = model.rotation,
            strokeColor = model.strokeColor,
            strokeWidth = model.strokeWidth
        )
        if (model.animations.isEmpty()) {
            return state
        }
        model.animations.forEach { action ->
            if (localTimeMs < action.startMs) {
                return@forEach
            }
            val progress = if (action.durationMs <= 0L) {
                1f
            } else {
                ((localTimeMs - action.startMs).toFloat() / action.durationMs)
                    .coerceIn(0f, 1f)
            }
            state = state.apply(action, progress)
        }
        return state
    }

    private data class ResolvedState(
        val x: Float,
        val y: Float,
        val alpha: Float,
        val color: Int,
        val fontSize: Float,
        val scaleX: Float,
        val scaleY: Float,
        val rotation: Float,
        val strokeColor: Int,
        val strokeWidth: Float
    ) {
        fun apply(action: SpecialDanmakuAction, progress: Float): ResolvedState {
            return copy(
                x = action.x?.let { lerp(x, it, progress) } ?: x,
                y = action.y?.let { lerp(y, it, progress) } ?: y,
                alpha = action.alpha?.let { lerp(alpha, it, progress) } ?: alpha,
                color = action.color?.let { lerpColor(color, it, progress) } ?: color,
                fontSize = action.fontSize?.toFloat()?.let { lerp(fontSize, it, progress) } ?: fontSize,
                scaleX = action.scaleX?.let { lerp(scaleX, it, progress) } ?: scaleX,
                scaleY = action.scaleY?.let { lerp(scaleY, it, progress) } ?: scaleY,
                rotation = action.rotation?.let { lerp(rotation, it, progress) } ?: rotation
            )
        }

        private fun lerp(start: Float, end: Float, progress: Float): Float {
            return start + (end - start) * progress
        }

        private fun lerpColor(start: Int, end: Int, progress: Float): Int {
            val sA = start ushr 24 and 0xFF
            val sR = start ushr 16 and 0xFF
            val sG = start ushr 8 and 0xFF
            val sB = start and 0xFF
            val eA = end ushr 24 and 0xFF
            val eR = end ushr 16 and 0xFF
            val eG = end ushr 8 and 0xFF
            val eB = end and 0xFF
            val a = (sA + (eA - sA) * progress).toInt()
            val r = (sR + (eR - sR) * progress).toInt()
            val g = (sG + (eG - sG) * progress).toInt()
            val b = (sB + (eB - sB) * progress).toInt()
            return Color.argb(a, r, g, b)
        }
    }

    private fun Int.toArgbColor(): Int {
        return if (this ushr 24 == 0) {
            this or 0xFF000000.toInt()
        } else {
            this
        }
    }
}

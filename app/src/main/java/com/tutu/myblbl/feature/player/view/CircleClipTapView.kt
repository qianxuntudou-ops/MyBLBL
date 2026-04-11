package com.tutu.myblbl.feature.player.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.DisplayMetrics
import android.view.View
import androidx.core.content.ContextCompat
import com.tutu.myblbl.R

class CircleClipTapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val backgroundPaint = Paint()
    private val circlePaint = Paint()
    private val clipPath = Path()
    private var isForward = true
    private var centerX = 0f
    private var centerY = 0f
    private var circleRadius = 0f
    private var displayWidth = 0
    private var displayHeight = 0
    private val minArcSize: Int
    private val maxArcSize: Int
    private var arcSize = 80f
    private var circleAnimator: ValueAnimator? = null
    private var performAtEnd: (() -> Unit)? = null
    private var isAnimating = false

    init {
        backgroundPaint.apply {
            style = Paint.Style.FILL
            isAntiAlias = true
            color = ContextCompat.getColor(context, R.color.dtpv_yt_background_circle_color)
        }
        circlePaint.apply {
            style = Paint.Style.FILL
            isAntiAlias = true
            color = ContextCompat.getColor(context, R.color.dtpv_yt_tap_circle_color)
        }

        val displayMetrics: DisplayMetrics = context.resources.displayMetrics
        displayWidth = displayMetrics.widthPixels
        displayHeight = displayMetrics.heightPixels
        val density: Float = displayMetrics.density
        minArcSize = (30.0f * density).toInt()
        maxArcSize = (400.0f * density).toInt()

        updateClipPath()
        circleAnimator = createCircleAnimator()
    }

    private fun createCircleAnimator(): ValueAnimator {
        return ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 650L
            addUpdateListener { anim ->
                val value = anim.animatedValue as Float
                circleRadius = value * maxArcSize
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: android.animation.Animator) {
                    visibility = VISIBLE
                }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!isAnimating) {
                        visibility = INVISIBLE
                        performAtEnd?.invoke()
                    }
                }
            })
        }
    }

    fun animate(performAction: () -> Unit) {
        isAnimating = true
        circleAnimator?.end()
        performAction()
        isAnimating = false
        circleAnimator?.start()
    }

    fun cancelAndHide() {
        isAnimating = false
        circleAnimator?.cancel()
        circleRadius = 0f
        visibility = INVISIBLE
        invalidate()
    }

    private fun updateClipPath() {
        val halfWidth = displayWidth * 0.5f
        clipPath.reset()
        val startX = if (isForward) displayWidth.toFloat() else 0f
        val direction = if (isForward) -1 else 1

        val edgePoint = (halfWidth - arcSize) * direction + startX
        val controlPoint = (halfWidth + arcSize) * direction + startX

        clipPath.moveTo(startX, 0f)
        clipPath.lineTo(edgePoint, 0f)
        clipPath.quadTo(controlPoint, displayHeight / 2f, edgePoint, displayHeight.toFloat())
        clipPath.lineTo(startX, displayHeight.toFloat())
        clipPath.close()
        invalidate()
    }

    fun setTapPosition(x: Float, y: Float) {
        centerX = x
        centerY = y
        val shouldBeForward = x > (displayWidth / 2f)
        if (isForward != shouldBeForward) {
            isForward = shouldBeForward
            updateClipPath()
        }
        circleRadius = 0f
    }

    fun getAnimationDuration(): Long {
        return circleAnimator?.duration ?: 650L
    }

    fun getArcSize(): Float {
        return arcSize
    }

    fun getCircleBackgroundColor(): Int {
        return backgroundPaint.color
    }

    fun getCircleColor(): Int {
        return circlePaint.color
    }

    fun setAnimationDuration(duration: Long) {
        circleAnimator?.duration = duration
    }

    fun setArcSize(size: Float) {
        arcSize = size
        updateClipPath()
    }

    fun setCircleBackgroundColor(color: Int) {
        backgroundPaint.color = color
    }

    fun setCircleColor(color: Int) {
        circlePaint.color = color
    }

    fun getPerformAtEnd(): (() -> Unit)? {
        return performAtEnd
    }

    fun setPerformAtEnd(action: (() -> Unit)?) {
        performAtEnd = action
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.clipPath(clipPath)
        canvas.drawPath(clipPath, backgroundPaint)
        canvas.drawCircle(centerX, centerY, circleRadius, circlePaint)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        displayWidth = w
        displayHeight = h
        updateClipPath()
    }
}

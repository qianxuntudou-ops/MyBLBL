package com.tutu.myblbl.feature.player.sponsor

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

class SponsorSkipOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val skipContainer: LinearLayout
    private val categoryLabel: TextView
    private val skipButton: TextView
    private val dismissButton: TextView
    private val toastView: TextView

    private var onSkipListener: (() -> Unit)? = null
    private var onDismissListener: (() -> Unit)? = null
    private var toastHideRunnable: Runnable? = null

    init {
        clipChildren = false
        clipToPadding = false

        toastView = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = GradientDrawable().apply {
                setColor(0xE000C853.toInt())
                cornerRadius = dp(20).toFloat()
            }
            visibility = GONE
        }
        addView(toastView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
            topMargin = dp(60)
        })

        skipContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                setColor(0xCC000000.toInt())
                cornerRadius = dp(12).toFloat()
            }
            visibility = GONE
        }

        categoryLabel = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(0xFFFFA500.toInt())
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }
        skipContainer.addView(categoryLabel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(8) })

        skipButton = TextView(context).apply {
            text = "跳过 ▶"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                setColor(0xFF00C853.toInt())
                cornerRadius = dp(8).toFloat()
            }
            setOnClickListener { onSkipListener?.invoke() }
        }
        skipContainer.addView(skipButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(8) })

        dismissButton = TextView(context).apply {
            text = "✕"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(0x99FFFFFF.toInt())
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setOnClickListener { onDismissListener?.invoke() }
        }
        skipContainer.addView(dismissButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        addView(skipContainer, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.END or Gravity.BOTTOM
            marginEnd = dp(16)
            bottomMargin = dp(80)
        })
    }

    fun setListeners(onSkip: () -> Unit, onDismiss: () -> Unit) {
        onSkipListener = onSkip
        onDismissListener = onDismiss
    }

    fun showSkipButton(segment: SponsorSegment) {
        categoryLabel.text = segment.categoryName()
        skipContainer.visibility = VISIBLE
        skipContainer.alpha = 0f
        skipContainer.animate()
            .alpha(1f)
            .setDuration(200)
            .start()
    }

    fun hideSkipButton() {
        if (skipContainer.visibility != VISIBLE) return
        skipContainer.animate()
            .alpha(0f)
            .setDuration(200)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    skipContainer.visibility = GONE
                    skipContainer.animate().setListener(null)
                }
            })
            .start()
    }

    fun showAutoSkipToast(segment: SponsorSegment) {
        toastHideRunnable?.let { removeCallbacks(it) }
        toastView.text = "已跳过: ${segment.categoryName()}"
        toastView.visibility = VISIBLE
        toastView.alpha = 0f
        toastView.animate()
            .alpha(1f)
            .setDuration(150)
            .start()

        val hideRunnable = Runnable {
            toastView.animate()
                .alpha(0f)
                .setDuration(300)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        toastView.visibility = GONE
                        toastView.animate().setListener(null)
                    }
                })
                .start()
        }
        toastHideRunnable = hideRunnable
        postDelayed(hideRunnable, 2000)
    }

    fun hideAll() {
        hideSkipButton()
        toastHideRunnable?.let { removeCallbacks(it) }
        toastView.visibility = GONE
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()
    }
}
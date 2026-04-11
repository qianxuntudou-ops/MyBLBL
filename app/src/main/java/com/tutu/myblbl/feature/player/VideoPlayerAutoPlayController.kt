package com.tutu.myblbl.feature.player

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.tutu.myblbl.R
import com.tutu.myblbl.core.ui.image.ImageLoader

class VideoPlayerAutoPlayController(
    private val fragment: Fragment,
    private val viewNext: View,
    private val imageNext: AppCompatImageView,
    private val textNext: TextView,
    private val canExecutePendingAction: () -> Boolean
) {

    private val handler = Handler(Looper.getMainLooper())
    private var pendingAutoPlayAction: (() -> Unit)? = null

    private val autoNextRunnable = Runnable {
        if (!canExecutePendingAction()) {
            return@Runnable
        }
        val action = pendingAutoPlayAction ?: return@Runnable
        hideNextPreview(clearPendingAction = false)
        pendingAutoPlayAction = null
        action.invoke()
    }

    fun queueNextAction(title: String, coverUrl: String, action: () -> Unit, delayMs: Long = 1200L) {
        pendingAutoPlayAction = action
        showNextPreview(title, coverUrl)
        handler.removeCallbacks(autoNextRunnable)
        handler.postDelayed(autoNextRunnable, delayMs)
    }

    fun cancelPendingAction() {
        handler.removeCallbacks(autoNextRunnable)
        pendingAutoPlayAction = null
    }

    fun hideNextPreview() {
        hideNextPreview(clearPendingAction = true)
    }

    private fun showNextPreview(title: String, coverUrl: String) {
        textNext.text = title
        ImageLoader.loadVideoCover(imageNext, coverUrl)
        if (viewNext.isVisible) {
            return
        }
        viewNext.clearAnimation()
        viewNext.visibility = View.VISIBLE
        AnimationUtils.loadAnimation(fragment.requireContext(), R.anim.slide_in_to_right).apply {
            viewNext.startAnimation(this)
        }
    }

    private fun hideNextPreview(clearPendingAction: Boolean) {
        handler.removeCallbacks(autoNextRunnable)
        if (clearPendingAction) {
            pendingAutoPlayAction = null
        }
        if (!viewNext.isVisible) {
            return
        }
        viewNext.clearAnimation()
        AnimationUtils.loadAnimation(fragment.requireContext(), R.anim.slide_out_to_right).apply {
            setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(animation: Animation?) = Unit

                override fun onAnimationEnd(animation: Animation?) {
                    viewNext.visibility = View.GONE
                }

                override fun onAnimationRepeat(animation: Animation?) = Unit
            })
            viewNext.startAnimation(this)
        }
    }
}

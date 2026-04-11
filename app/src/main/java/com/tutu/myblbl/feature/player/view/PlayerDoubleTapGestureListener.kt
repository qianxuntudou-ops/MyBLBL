package com.tutu.myblbl.feature.player.view

import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View

/**
 * Encapsulates the double-tap gesture handling used by MyPlayerView.
 * - Keeps gesture wiring in one place
 * - Uses screen double-tap as a play/pause toggle
 */
internal class PlayerDoubleTapGestureListener(
    private val rootView: View
) : GestureDetector.SimpleOnGestureListener() {

    var doubleTapDelay: Long = 700L
    var isDoubleTapping: Boolean = false
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var callback: ((Float, Float) -> Unit)? = null

    private val resetRunnable = Runnable {
        isDoubleTapping = false
    }

    fun setCallback(callback: ((Float, Float) -> Unit)?) {
        this.callback = callback
    }

    fun cancelInDoubleTapMode() {
        handler.removeCallbacks(resetRunnable)
        isDoubleTapping = false
    }

    fun keepInDoubleTapMode() {
        isDoubleTapping = true
        handler.removeCallbacks(resetRunnable)
        handler.postDelayed(resetRunnable, doubleTapDelay)
    }

    override fun onDoubleTap(e: MotionEvent): Boolean {
        cancelInDoubleTapMode()
        callback?.invoke(e.x, e.y)
        return true
    }

    override fun onDoubleTapEvent(e: MotionEvent): Boolean {
        return true
    }

    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
        if (isDoubleTapping) return true
        return rootView.performClick()
    }

    override fun onDown(e: MotionEvent): Boolean {
        return true
    }

    @Suppress("UNUSED_PARAMETER")
    fun handleKeyDown(event: KeyEvent): Boolean {
        return false
    }
}

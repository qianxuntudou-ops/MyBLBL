package com.tutu.myblbl.feature.player.view

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatImageView

/**
 * Player controls on TV are mainly focus-driven. Suppressing the pressed-state
 * fill keeps long-press seek from flashing a full overlay that diverges from
 * the reference player's visual feedback.
 */
class PlayerControlButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private var touchPressed = false

    override fun setPressed(pressed: Boolean) {
        super.setPressed(false)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || !isClickable) {
            return super.onTouchEvent(event)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchPressed = true
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_UP -> {
                val shouldClick = touchPressed && event.x >= 0f && event.x <= width && event.y >= 0f && event.y <= height
                touchPressed = false
                parent?.requestDisallowInterceptTouchEvent(false)
                if (shouldClick) {
                    performClick()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                touchPressed = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (isClickable && isEnabled) {
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (isClickable && isEnabled) {
                performClick()
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }
}

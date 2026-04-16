package com.tutu.myblbl.core.ui.image

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView
import com.tutu.myblbl.R

class AvatarBadgeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    companion object {
        const val BADGE_NONE = 0
        const val BADGE_YELLOW_V = 1
        const val BADGE_BLUE_V = 2
        const val BADGE_VIP = 3
    }

    private var badgeDrawable: Drawable? = null
    private var badgeType: Int = BADGE_NONE

    private val borderWidthPx = 1f
    private val gapPx = 1f

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = borderWidthPx
        color = 0xFF666666.toInt()
        isAntiAlias = true
    }

    init {
        val extra = (gapPx + borderWidthPx).toInt().coerceAtLeast(1)
        setPadding(
            paddingLeft + extra,
            paddingTop + extra,
            paddingRight + extra,
            paddingBottom + extra
        )
    }

    fun setBadge(officialVerifyType: Int, vipStatus: Int = 0, vipType: Int = 0, vipAvatarSubscript: Int = 0) {
        val newBadgeType = resolveBadgeType(officialVerifyType, vipStatus, vipType, vipAvatarSubscript)
        if (newBadgeType == badgeType) return
        badgeType = newBadgeType
        badgeDrawable = when (badgeType) {
            BADGE_YELLOW_V -> resources.getDrawable(R.drawable.ic_avatar_badge_yellow_v, context.theme)
            BADGE_BLUE_V -> resources.getDrawable(R.drawable.ic_avatar_badge_blue_v, context.theme)
            BADGE_VIP -> resources.getDrawable(R.drawable.ic_avatar_badge_vip, context.theme)
            else -> null
        }
        invalidate()
    }

    fun setBadgeType(type: Int) {
        if (type == badgeType) return
        badgeType = type
        badgeDrawable = when (badgeType) {
            BADGE_YELLOW_V -> resources.getDrawable(R.drawable.ic_avatar_badge_yellow_v, context.theme)
            BADGE_BLUE_V -> resources.getDrawable(R.drawable.ic_avatar_badge_blue_v, context.theme)
            BADGE_VIP -> resources.getDrawable(R.drawable.ic_avatar_badge_vip, context.theme)
            else -> null
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawCircleBorder(canvas)
        badgeDrawable?.let { badge ->
            val ratio = if (badgeType == BADGE_VIP) 0.32f else 0.4f
            val contentWidth = width - paddingLeft - paddingRight
            val contentHeight = height - paddingTop - paddingBottom
            val badgeSize = (contentWidth * ratio).toInt()
            val left = width - paddingRight - badgeSize
            val top = height - paddingBottom - badgeSize
            badge.setBounds(left, top, left + badgeSize, top + badgeSize)
            badge.draw(canvas)
        }
    }

    private fun drawCircleBorder(canvas: Canvas) {
        val contentWidth = width - paddingLeft - paddingRight
        val contentHeight = height - paddingTop - paddingBottom
        val cx = paddingLeft + contentWidth / 2f
        val cy = paddingTop + contentHeight / 2f
        val contentRadius = Math.min(contentWidth, contentHeight) / 2f
        val ringRadius = contentRadius + gapPx + borderWidthPx / 2f
        canvas.drawCircle(cx, cy, ringRadius, borderPaint)
    }

    private fun resolveBadgeType(officialVerifyType: Int, vipStatus: Int, vipType: Int, vipAvatarSubscript: Int): Int {
        if (officialVerifyType == 0) return BADGE_YELLOW_V
        if (officialVerifyType == 1) return BADGE_BLUE_V
        if (vipAvatarSubscript == 1 || (vipType > 0 && vipStatus == 1)) return BADGE_VIP
        return BADGE_NONE
    }
}

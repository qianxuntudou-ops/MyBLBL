package com.tutu.myblbl.feature.player.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * 弹幕防挡蒙版宿主容器。
 *
 * 把弹幕主层（[com.kuaishou.akdanmaku.ui.DanmakuView]）和高级弹幕层
 * （[SpecialDanmakuOverlayView]）作为子节点放入本容器后，PorterDuff 蒙版只在父容器层做
 * 一次 [Canvas.saveLayer] + [Canvas.drawBitmap] 合成，避免两个子 view 各自再开一次离屏图层
 * 重复消耗 GPU。
 */
class DanmakuMaskHostLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }

    @Volatile
    var maskBitmap: Bitmap? = null
        set(value) {
            if (field === value) return
            field = value
            invalidate()
        }

    init {
        setWillNotDraw(false)
        // 子 view 仍是普通 view，绘制由 dispatchDraw 触发；clipChildren 默认即可。
    }

    override fun dispatchDraw(canvas: Canvas) {
        val mask = maskBitmap
        if (mask == null || mask.isRecycled) {
            super.dispatchDraw(canvas)
            return
        }
        val w = width
        val h = height
        if (w <= 0 || h <= 0) {
            super.dispatchDraw(canvas)
            return
        }
        val saveCount = canvas.saveLayer(0f, 0f, w.toFloat(), h.toFloat(), null)
        super.dispatchDraw(canvas)
        canvas.drawBitmap(mask, 0f, 0f, maskPaint)
        canvas.restoreToCount(saveCount)
    }
}

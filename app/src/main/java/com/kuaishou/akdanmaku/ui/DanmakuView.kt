package com.kuaishou.akdanmaku.ui

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View

class DanmakuView @JvmOverloads constructor(
  context: Context?,
  attrs: AttributeSet? = null,
  defStyleAttr: Int = 0
) : View(
  context,
  attrs,
  defStyleAttr
) {

  var danmakuPlayer: DanmakuPlayer? = null
  internal val displayer: ViewDisplayer = ViewDisplayer()

  init {
    context?.resources?.displayMetrics?.let { metrics ->
      displayer.density = metrics.density
      @Suppress("DEPRECATION")
      displayer.scaleDensity = metrics.scaledDensity
      displayer.densityDpi = metrics.densityDpi
    }
  }

  override fun onDraw(canvas: Canvas) {
    val width = measuredWidth
    val height = measuredHeight
    if (width == 0 || height == 0) return
    danmakuPlayer?.notifyDisplayerSizeChanged(width, height)
    // 防挡蒙版的 PorterDuff 合成统一交给父级 DanmakuMaskHostLayout，
    // 这里只负责把弹幕画到上层 canvas，不再单独 saveLayer。
    danmakuPlayer?.draw(canvas)
  }

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    danmakuPlayer?.notifyDisplayerSizeChanged(w, h)
  }

  override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
    super.onLayout(changed, left, top, right, bottom)
    danmakuPlayer?.notifyDisplayerSizeChanged(right - left, bottom - top)
  }

  class ViewDisplayer : DanmakuDisplayer {
    override var height: Int = 0
    override var width: Int = 0
    override var margin: Int = 8
    override var allMarginTop: Float = 0f
    override var density: Float = 1f
    override var scaleDensity: Float = 1f
    override var densityDpi: Int = 160
  }
}

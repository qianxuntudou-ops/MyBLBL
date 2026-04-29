/*
 * The MIT License (MIT)
 *
 * Copyright 2021 Kwai, Inc. All rights reserved.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.kuaishou.akdanmaku.layout.retainer

import com.kuaishou.akdanmaku.DanmakuConfig
import com.kuaishou.akdanmaku.data.DanmakuItemData
import com.kuaishou.akdanmaku.data.DanmakuItem
import com.kuaishou.akdanmaku.ext.*
import com.kuaishou.akdanmaku.ui.DanmakuDisplayer

internal class AkTopRetainer(
  private val startRatio: Float = 1f,
  private val endRatio: Float = 1f
) : DanmakuRetainer {

  private companion object {
    const val TAG = "DanmakuRetainer"
  }

  private class Row(
    val top: Int,
    val bottom: Int,
    val items: MutableList<DanmakuItem> = mutableListOf()
  )

  private var maxEnd = 0
  private val rows = mutableListOf<Row>()
  private val itemToRow = mutableMapOf<DanmakuItem, Row>()

  override fun layout(
    drawItem: DanmakuItem,
    currentTimeMills: Long,
    displayer: DanmakuDisplayer,
    config: DanmakuConfig
  ): Float {
    val drawState = drawItem.drawState
    val duration = if (drawItem.data.mode == DanmakuItemData.DANMAKU_MODE_ROLLING) config.rollingDurationMs
    else config.durationMs
    if (drawItem.isOutside(currentTimeMills)) {
      remove(drawItem)
      return -1f
    }
    val needRelayout = drawState.layoutGeneration != config.layoutGeneration
    val isRunning = itemToRow.containsKey(drawItem)
    val topPos: Int
    val visibility: Boolean
    if (needRelayout && !isRunning) {
      val itemHeight = drawState.height.toInt()
      val margin = displayer.margin

      var bestRow: Row? = null
      var bestLoad = Int.MAX_VALUE

      for (row in rows) {
        if (itemHeight > row.bottom - row.top) continue
        val hasCollision = row.items.any { existing ->
          existing.willCollision(drawItem, displayer, currentTimeMills, duration, config.overlapFraction)
        }
        if (!hasCollision && row.items.size < bestLoad) {
          bestLoad = row.items.size
          bestRow = row
        }
      }

      if (bestRow != null) {
        bestRow.items.add(drawItem)
        itemToRow[drawItem] = bestRow
        topPos = bestRow.top
        visibility = true
      } else {
        val gapTop = findGap(itemHeight, margin)
        if (gapTop >= 0) {
          val newRow = Row(gapTop, gapTop + itemHeight, mutableListOf(drawItem))
          insertRowSorted(newRow)
          itemToRow[drawItem] = newRow
          topPos = gapTop
          visibility = true
        } else {
          topPos = -1
          visibility = false
        }
      }
    } else {
      visibility = drawState.visibility
      topPos = drawItem.drawState.positionY.toInt()
    }

    drawState.layoutGeneration = config.layoutGeneration
    drawState.visibility = visibility
    if (!visibility) return -1f
    drawItem.drawState.positionY = topPos.toFloat()
    return topPos.toFloat()
  }

  private fun findGap(itemHeight: Int, margin: Int): Int {
    if (itemHeight <= 0 || maxEnd <= 0) return -1
    if (rows.isEmpty()) {
      return if (itemHeight <= maxEnd) 0 else -1
    }
    if (rows.first().top >= itemHeight) return 0
    for (i in 0 until rows.size - 1) {
      val gapStart = rows[i].bottom + margin
      val gapSize = rows[i + 1].top - gapStart
      if (gapSize >= itemHeight) return gapStart
    }
    val afterLast = rows.last().bottom + margin
    return if (afterLast + itemHeight <= maxEnd) afterLast else -1
  }

  private fun insertRowSorted(row: Row) {
    val idx = rows.binarySearchBy(row.top) { it.top }.let { if (it < 0) -(it + 1) else it }
    rows.add(idx, row)
  }

  override fun clear() {
    rows.clear()
    itemToRow.clear()
  }

  override fun remove(item: DanmakuItem) {
    val row = itemToRow.remove(item) ?: return
    row.items.remove(item)
    if (row.items.isEmpty()) {
      rows.remove(row)
    }
  }

  override fun update(start: Int, end: Int) {
    maxEnd = (end * endRatio).toInt()
    clear()
  }
}

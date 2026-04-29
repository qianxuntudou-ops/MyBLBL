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

package com.kuaishou.akdanmaku.cache

import com.kuaishou.akdanmaku.DanmakuConfig
import com.kuaishou.akdanmaku.ext.AkLog as Log

/**
 * 弹幕绘制缓存池，管理可复用的 DrawingCache 对象。
 *
 * 支持根据屏幕分辨率动态调整池大小，以及基于可用内存的自适应缩放。
 *
 * @param maxMemorySize 池最大内存（字节）
 *
 * @author Xana
 * @since 2021-06-23
 */
class DrawingCachePool(private var maxMemorySize: Int) {

  companion object {
    private const val BUCKET_SIZE = 16
  }

  private val caches = mutableSetOf<DrawingCache>()
  private val bucketMap = mutableMapOf<Long, MutableSet<DrawingCache>>()
  private var memorySize = 0

  private fun bucketKey(width: Int, height: Int): Long {
    return ((width / BUCKET_SIZE).toLong() shl 32) or (height / BUCKET_SIZE).toLong()
  }

  fun acquire(width: Int, height: Int): DrawingCache? {
    synchronized(this) {
      val key = bucketKey(width, height)
      val bucket = bucketMap[key] ?: return null
      val iter = bucket.iterator()
      while (iter.hasNext()) {
        val cache = iter.next()
        if (cache.width >= width && cache.height >= height &&
          cache.width - width < 5 && cache.height - height < 5
        ) {
          iter.remove()
          caches.remove(cache)
          memorySize -= cache.size
          return cache
        }
      }
      return null
    }
  }

  fun release(cache: DrawingCache?): Boolean {
    cache?.get() ?: return true
    if (cache in caches) return false
    return if (cache.size + memorySize > maxMemorySize) {
      Log.v("DrawingCache", "[Release][+] OOM Pool")
      false
    } else {
      synchronized(this) {
        caches.add(cache)
        cache.erase()
        memorySize += cache.size
        val key = bucketKey(cache.width, cache.height)
        bucketMap.getOrPut(key) { mutableSetOf() }.add(cache)
      }
      true
    }
  }

  /**
   * 批量释放池中多余的缓存，单次最多释放 [maxReleasePerDrain] 个。
   * 适用于在播放过程中周期性回收不再需要的缓存。
   */
  fun drainExcess(maxReleasePerDrain: Int = DanmakuConfig.MAX_RELEASE_PER_DRAIN) {
    synchronized(this) {
      if (memorySize <= maxMemorySize / 2) return
      val toRelease = caches
        .sortedByDescending { it.size }
        .take(maxReleasePerDrain)
      toRelease.forEach { cache ->
        caches.remove(cache)
        val key = bucketKey(cache.width, cache.height)
        bucketMap[key]?.remove(cache)
        memorySize -= cache.size
        cache.destroy()
      }
      Log.v(
        "DrawingCache",
        "[drainExcess] released ${toRelease.size} caches, " +
          "remaining=${caches.size}, memorySize=$memorySize"
      )
    }
  }

  /**
   * 根据屏幕分辨率和设备可用内存动态调整池的最大内存上限。
   * 当新上限小于当前已用内存时，会自动释放多余的缓存。
   */
  fun adjustForDevice(screenWidth: Int, screenHeight: Int) {
    val newSize = DanmakuConfig.computeCachePoolMaxMemorySize(screenWidth, screenHeight)
    synchronized(this) {
      maxMemorySize = newSize
      while (memorySize > maxMemorySize && caches.isNotEmpty()) {
        val cache = caches.first()
        caches.remove(cache)
        val key = bucketKey(cache.width, cache.height)
        bucketMap[key]?.remove(cache)
        memorySize -= cache.size
        cache.destroy()
      }
    }
    Log.v(
      "DrawingCache",
      "[adjustForDevice] screenSize=${screenWidth}x${screenHeight}, " +
        "maxMemory=${maxMemorySize / (1024 * 1024)}MB"
    )
  }

  fun clear() {
    synchronized(this) {
      caches.forEach { it.destroy() }
      caches.clear()
      bucketMap.clear()
      memorySize = 0
    }
  }
}

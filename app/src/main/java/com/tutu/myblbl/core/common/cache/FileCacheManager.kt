package com.tutu.myblbl.core.common.cache

import com.google.gson.Gson
import com.tutu.myblbl.MyBLBLApplication
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.core.common.settings.AppSettingsDataStore
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.mp.KoinPlatform

object FileCacheManager {

    private const val KEY_CACHE_LIMIT = "cache_limit"
    private const val DEFAULT_CACHE_SIZE: Long = 50L * 1024L * 1024L
    private const val CACHE_SIZE_200_MB: Long = 200L * 1024L * 1024L
    private const val CACHE_SIZE_500_MB: Long = 500L * 1024L * 1024L
    private const val CACHE_SIZE_1_GB: Long = 1024L * 1024L * 1024L
    private const val MAX_FILE_COUNT = Int.MAX_VALUE

    private val appSettings: AppSettingsDataStore get() = KoinPlatform.getKoin().get()

    private val cacheDir: File by lazy {
        File(MyBLBLApplication.instance.cacheDir, "BBLLCache").also {
            it.mkdirs()
        }
    }

    /**
     * LRU 缓存映射表，accessOrder = true 使得每次 get/put 操作都会将条目移到链表尾部。
     * 链表头部即为最久未访问的条目，evictOldest 只需移除头部，O(1) 复杂度。
     */
    private val fileMap: java.util.LinkedHashMap<File, Long> =
        java.util.LinkedHashMap(16, 0.75f, true)

    private val totalSize = AtomicLong(0)
    private val totalCount = AtomicInteger(0)
    private val gson = Gson()

    @Volatile
    private var initialized = false

    fun init() {
        if (initialized) return
        initialized = true
        Thread {
            scanCacheDir()
            trimToLimit()
        }.start()
    }

    private fun scanCacheDir() {
        val files = cacheDir.listFiles() ?: return
        synchronized(fileMap) {
            for (file in files) {
                if (file.isFile) {
                    totalSize.addAndGet(file.length())
                    totalCount.incrementAndGet()
                    fileMap[file] = file.lastModified()
                }
            }
        }
    }

    fun <T> put(key: String, data: T) {
        init()
        try {
            val json = gson.toJson(data)
            val bytes = json.toByteArray(Charsets.UTF_8)
            writeFile(key, bytes)
        } catch (e: Exception) {
            AppLog.e("FileCacheManager", "put failed: key=$key", e)
        }
    }

    suspend fun <T> putAsync(key: String, data: T) {
        withContext(Dispatchers.IO) {
            put(key, data)
        }
    }

    fun <T> get(key: String, type: java.lang.reflect.Type): T? {
        init()
        try {
            val bytes = readFile(key) ?: return null
            val json = String(bytes, Charsets.UTF_8)
            // 访问时更新 LRU 顺序
            touchFile(key)
            return gson.fromJson<T>(json, type)
        } catch (e: Exception) {
            AppLog.e("FileCacheManager", "get failed: key=$key", e)
            return null
        }
    }

    suspend fun <T> getAsync(key: String, type: java.lang.reflect.Type): T? {
        return withContext(Dispatchers.IO) {
            get(key, type)
        }
    }

    private fun keyToFile(key: String): File {
        return File(cacheDir, key.hashCode().toString())
    }

    private fun writeFile(key: String, data: ByteArray) {
        val file = keyToFile(key)
        try {
            file.writeBytes(data)
        } catch (e: Exception) {
            AppLog.e("FileCacheManager", "writeFile failed: key=$key", e)
        }
        registerFile(file)
    }

    private fun readFile(key: String): ByteArray? {
        val file = keyToFile(key)
        if (!file.exists()) return null
        return try {
            file.readBytes()
        } catch (e: Exception) {
            AppLog.e("FileCacheManager", "readFile failed: key=$key", e)
            null
        }
    }

    /**
     * get 命中后调用，更新 LRU 访问顺序。
     */
    private fun touchFile(key: String) {
        val file = keyToFile(key)
        synchronized(fileMap) {
            if (fileMap.containsKey(file)) {
                val now = System.currentTimeMillis()
                file.setLastModified(now)
                // LinkedHashMap accessOrder=true，put 会将条目移到尾部（最近访问）
                fileMap[file] = now
            }
        }
    }

    private fun registerFile(file: File) {
        val length = file.length()
        synchronized(fileMap) {
            // 如果是覆盖写入，先减去旧文件大小
            val oldSize = if (fileMap.containsKey(file)) {
                file.length()
            } else {
                0L
            }

            totalCount.incrementAndGet()
            val maxCacheSize = resolveMaxCacheSize()
            // 淘汰直到总大小不超限，每次最多移除一个最旧条目
            while (maxCacheSize != Long.MAX_VALUE && totalSize.get() + length - oldSize > maxCacheSize) {
                val evicted = evictOldestInternal()
                if (evicted <= 0L) {
                    break
                }
                totalSize.addAndGet(-evicted)
            }

            while (totalCount.get() > MAX_FILE_COUNT) {
                totalCount.decrementAndGet()
                val evicted = evictOldestInternal()
                if (evicted <= 0L) {
                    break
                }
                totalSize.addAndGet(-evicted)
            }

            totalSize.addAndGet(length - oldSize)
            val now = System.currentTimeMillis()
            file.setLastModified(now)
            fileMap[file] = now
        }
    }

    /**
     * 在已持有 fileMap 锁的上下文中调用。
     * LinkedHashMap accessOrder=true，迭代顺序即访问顺序，第一个条目就是最久未访问的。
     * O(1) 取头部即可，无需全遍历。
     */
    private fun evictOldestInternal(): Long {
        if (fileMap.isEmpty()) return 0L
        val iterator = fileMap.entries.iterator()
        if (!iterator.hasNext()) return 0L
        val (oldestFile, _) = iterator.next()
        val length = oldestFile.length()
        if (oldestFile.delete()) {
            iterator.remove()
            totalCount.decrementAndGet()
        }
        return length
    }

    fun clear() {
        synchronized(fileMap) {
            val files = cacheDir.listFiles() ?: return
            for (file in files) {
                file.delete()
            }
            fileMap.clear()
            totalSize.set(0)
            totalCount.set(0)
        }
    }

    fun trimToLimit() {
        init()
        val maxCacheSize = resolveMaxCacheSize()
        if (maxCacheSize == Long.MAX_VALUE) {
            return
        }
        synchronized(fileMap) {
            while (totalSize.get() > maxCacheSize) {
                val evicted = evictOldestInternal()
                if (evicted <= 0L) {
                    break
                }
                totalSize.addAndGet(-evicted)
            }
        }
    }

    private fun resolveMaxCacheSize(): Long {
        return when (appSettings.getCachedString(KEY_CACHE_LIMIT)?.trim()) {
            "不限制" -> Long.MAX_VALUE
            "200 MB" -> CACHE_SIZE_200_MB
            "500 MB" -> CACHE_SIZE_500_MB
            "1 GB" -> CACHE_SIZE_1_GB
            else -> DEFAULT_CACHE_SIZE
        }
    }
}

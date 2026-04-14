package com.tutu.myblbl.core.common.cache

import com.google.gson.Gson
import com.tutu.myblbl.MyBLBLApplication
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.core.common.settings.AppSettingsDataStore
import java.io.File
import java.util.Collections
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

    private val fileMap: MutableMap<File, Long> =
        Collections.synchronizedMap(HashMap())
    private val totalSize = AtomicLong(0)
    private val totalCount = AtomicInteger(0)
    private val gson = Gson()
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
        for (file in files) {
            if (file.isFile) {
                totalSize.addAndGet(file.length())
                totalCount.incrementAndGet()
                fileMap[file] = file.lastModified()
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
        var fos: java.io.FileOutputStream? = null
        try {
            fos = java.io.FileOutputStream(file)
            fos.write(data)
            fos.flush()
        } catch (e: Exception) {
            AppLog.e("FileCacheManager", "writeFile failed: key=$key", e)
        } finally {
            try {
                fos?.close()
            } catch (_: java.io.IOException) {
            }
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

    private fun registerFile(file: File) {
        while (totalCount.incrementAndGet() > MAX_FILE_COUNT) {
            totalCount.decrementAndGet()
            val evicted = evictOldest()
            if (evicted <= 0L) {
                break
            }
            totalSize.addAndGet(-evicted)
        }
        val length = file.length()
        val maxCacheSize = resolveMaxCacheSize()
        while (maxCacheSize != Long.MAX_VALUE && totalSize.get() + length > maxCacheSize) {
            val evicted = evictOldest()
            if (evicted <= 0L) {
                break
            }
            totalSize.addAndGet(-evicted)
        }
        totalSize.addAndGet(length)
        val now = System.currentTimeMillis()
        file.setLastModified(now)
        fileMap[file] = now
    }

    private fun evictOldest(): Long {
        if (fileMap.isEmpty()) return 0L
        var oldestFile: File? = null
        var oldestTime: Long = Long.MAX_VALUE
        synchronized(fileMap) {
            for ((file, time) in fileMap) {
                if (time < oldestTime) {
                    oldestFile = file
                    oldestTime = time
                }
            }
        }
        val targetFile = oldestFile ?: return 0L
        val length = targetFile.length()
        if (targetFile.delete()) {
            fileMap.remove(targetFile)
        }
        return length
    }

    fun clear() {
        val files = cacheDir.listFiles() ?: return
        for (file in files) {
            file.delete()
        }
        fileMap.clear()
        totalSize.set(0)
        totalCount.set(0)
    }

    fun trimToLimit() {
        init()
        val maxCacheSize = resolveMaxCacheSize()
        if (maxCacheSize == Long.MAX_VALUE) {
            return
        }
        while (totalSize.get() > maxCacheSize) {
            val evicted = evictOldest()
            if (evicted <= 0L) {
                break
            }
            totalSize.addAndGet(-evicted)
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

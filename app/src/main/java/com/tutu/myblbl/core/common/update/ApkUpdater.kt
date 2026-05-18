package com.tutu.myblbl.core.common.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.tutu.myblbl.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

object ApkUpdater {

    private const val GITHUB_OWNER = "qianxuntudou-ops"
    private const val GITHUB_REPO = "MyBili"
    private const val RELEASE_API_URL =
        "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    private const val COOLDOWN_MS = 5_000L

    @Volatile
    private var lastStartedAtMs: Long = 0L

    private val okHttpLazy: Lazy<OkHttpClient> = lazy {
        OkHttpClient.Builder()
            .dns(IPv4OnlyDns)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private object IPv4OnlyDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val addresses = Dns.SYSTEM.lookup(hostname)
            val ipv4 = addresses.filterIsInstance<Inet4Address>()
            if (ipv4.isNotEmpty()) return ipv4
            throw UnknownHostException("No IPv4 address for $hostname")
        }
    }

    private val okHttp: OkHttpClient
        get() = okHttpLazy.value

    fun markStarted(nowMs: Long = System.currentTimeMillis()) {
        lastStartedAtMs = nowMs
    }

    fun cooldownLeftMs(nowMs: Long = System.currentTimeMillis()): Long {
        val left = (lastStartedAtMs + COOLDOWN_MS) - nowMs
        return left.coerceAtLeast(0)
    }

    data class ReleaseInfo(
        val versionName: String,
        val apkUrl: String,
        val releaseNotes: String,
    )

    sealed class Progress {
        data object Connecting : Progress()

        data class Downloading(
            val downloadedBytes: Long,
            val totalBytes: Long?,
            val bytesPerSecond: Long,
        ) : Progress() {
            val percent: Int? =
                totalBytes?.takeIf { it > 0 }?.let { total ->
                    ((downloadedBytes.toDouble() / total.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
                }

            val hint: String =
                buildString {
                    if (totalBytes != null && totalBytes > 0) {
                        append("${formatBytes(downloadedBytes)} / ${formatBytes(totalBytes)}")
                    } else {
                        append(formatBytes(downloadedBytes))
                    }
                    if (bytesPerSecond > 0) append("（${formatBytes(bytesPerSecond)}/s）")
                }
        }

        data object Done : Progress()
    }

    suspend fun fetchLatestRelease(): ReleaseInfo {
        return withContext(Dispatchers.IO) {
            var lastError: Throwable? = null
            val maxAttempts = 3
            for (attempt in 1..maxAttempts) {
                ensureActive()
                try {
                    return@withContext fetchLatestReleaseOnce()
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    lastError = t
                    val shouldRetry =
                        attempt < maxAttempts &&
                            (t is IOException || t.message?.startsWith("HTTP ") == true)
                    if (!shouldRetry) throw t
                    delay(400L * attempt)
                }
            }
            throw lastError ?: IllegalStateException("fetch latest release failed")
        }
    }

    private fun fetchLatestReleaseOnce(): ReleaseInfo {
        val req = Request.Builder()
            .url(RELEASE_API_URL)
            .header("Accept", "application/vnd.github+json")
            .get()
            .build()
        val call = okHttp.newCall(req)
        val res = call.execute()
        res.use { r ->
            check(r.isSuccessful) { "HTTP ${r.code} ${r.message}" }
            val body = r.body ?: error("empty body")
            val json = JSONObject(body.string())

            val tagName = json.optString("tag_name", "").trim().removePrefix("v")
            check(tagName.isNotBlank()) { "Release tag_name 为空" }
            check(parseVersion(tagName) != null) { "版本号格式不正确：$tagName" }

            val releaseNotes = json.optString("body", "").trim()

            val assets = json.optJSONArray("assets") ?: error("没有找到附件")
            var apkUrl = ""
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name", "")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.optString("browser_download_url", "")
                    break
                }
            }
            check(apkUrl.isNotBlank()) { "Release 中没有找到 APK 文件" }

            return ReleaseInfo(
                versionName = tagName,
                apkUrl = apkUrl,
                releaseNotes = releaseNotes,
            )
        }
    }

    fun isRemoteNewer(
        remoteVersionName: String,
        currentVersionName: String = BuildConfig.VERSION_NAME,
    ): Boolean {
        val remote = parseVersion(remoteVersionName) ?: return false
        val current = parseVersion(currentVersionName)
            ?: return remoteVersionName.trim() != currentVersionName.trim()
        return compareVersion(remote, current) > 0
    }

    suspend fun downloadApkToCache(
        context: Context,
        url: String,
        onProgress: (Progress) -> Unit,
    ): File {
        onProgress(Progress.Connecting)

        val dir = File(context.cacheDir, "test_update").apply { mkdirs() }
        val part = File(dir, "update.apk.part")
        val target = File(dir, "update.apk")
        runCatching { part.delete() }
        runCatching { target.delete() }

        val req = Request.Builder().url(url).get().build()
        val call = okHttp.newCall(req)
        val res = call.execute()
        res.use { r ->
            check(r.isSuccessful) { "HTTP ${r.code} ${r.message}" }
            val body = r.body ?: error("empty body")
            val total = body.contentLength().takeIf { it > 0 }
            withContext(Dispatchers.IO) {
                body.byteStream().use { input ->
                    FileOutputStream(part).use { output ->
                        val buf = ByteArray(32 * 1024)
                        var downloaded = 0L

                        var lastEmitAtMs = 0L
                        var speedAtMs = System.currentTimeMillis()
                        var speedBytes = 0L
                        var bytesPerSecond = 0L

                        while (true) {
                            ensureActive()
                            val read = input.read(buf)
                            if (read <= 0) break
                            output.write(buf, 0, read)
                            downloaded += read

                            speedBytes += read
                            val nowMs = System.currentTimeMillis()
                            val speedElapsedMs = nowMs - speedAtMs
                            if (speedElapsedMs >= 1_000) {
                                bytesPerSecond =
                                    (speedBytes * 1_000L / speedElapsedMs.coerceAtLeast(1)).coerceAtLeast(0)
                                speedBytes = 0L
                                speedAtMs = nowMs
                            }

                            if (nowMs - lastEmitAtMs >= 200) {
                                lastEmitAtMs = nowMs
                                onProgress(
                                    Progress.Downloading(
                                        downloadedBytes = downloaded,
                                        totalBytes = total,
                                        bytesPerSecond = bytesPerSecond,
                                    )
                                )
                            }
                        }
                        output.fd.sync()
                    }
                }
            }
        }

        check(part.exists() && part.length() > 0) { "downloaded file is empty" }
        check(part.renameTo(target)) { "rename failed" }
        return target
    }

    fun installApk(context: Context, apkFile: File) {
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun formatBytes(bytes: Long): String {
        val b = bytes.coerceAtLeast(0)
        if (b < 1024) return "${b}B"
        val kb = b / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1fKB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1fMB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2fGB", gb)
    }

    private fun parseVersion(raw: String): List<Int>? {
        val cleaned = raw.trim().removePrefix("v")
        val digitsOnly = cleaned.takeWhile { ch -> ch.isDigit() || ch == '.' }
        if (digitsOnly.isBlank()) return null
        val parts = digitsOnly.split('.').filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        return parts.map { it.toIntOrNull() ?: return null }
    }

    private fun compareVersion(a: List<Int>, b: List<Int>): Int {
        val max = maxOf(a.size, b.size)
        for (i in 0 until max) {
            val ai = a.getOrElse(i) { 0 }
            val bi = b.getOrElse(i) { 0 }
            if (ai != bi) return ai.compareTo(bi)
        }
        return 0
    }
}

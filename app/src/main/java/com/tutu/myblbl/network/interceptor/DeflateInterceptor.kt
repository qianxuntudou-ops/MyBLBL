package com.tutu.myblbl.network.interceptor

import com.tutu.myblbl.core.common.log.AppLog
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * B 站某些接口会返回 `Content-Encoding: deflate`，OkHttp 默认只识别 gzip。
 * 这里手动解压并把响应体替换为明文。
 *
 * 注：本拦截器与 OkHttp 的 [okhttp3.Cache] 无关，仅做内容编码兜底。
 */
class DeflateInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        return processDeflateResponse(response)
    }

    private fun processDeflateResponse(response: Response): Response {
        val contentEncoding = response.header("Content-Encoding")
        if (contentEncoding?.contains("deflate", ignoreCase = true) != true) {
            return response
        }
        val responseBody = response.body ?: return response
        return try {
            val compressedBytes = responseBody.bytes()
            val inflater = Inflater(true)
            val inflaterInputStream = InflaterInputStream(
                ByteArrayInputStream(compressedBytes),
                inflater
            )
            val outputStream = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            var bytesRead: Int
            while (inflaterInputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
            }
            inflaterInputStream.close()
            outputStream.close()
            val decompressedBody = outputStream.toByteArray()
                .toResponseBody(responseBody.contentType())
            response.newBuilder()
                .removeHeader("Content-Encoding")
                .body(decompressedBody)
                .build()
        } catch (e: Exception) {
            AppLog.e(TAG, "deflate decompress failed", e)
            response
        }
    }

    companion object {
        private const val TAG = "DeflateInterceptor"
    }
}

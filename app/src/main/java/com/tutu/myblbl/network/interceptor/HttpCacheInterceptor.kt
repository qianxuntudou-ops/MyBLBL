package com.tutu.myblbl.network.interceptor

import com.tutu.myblbl.core.common.log.AppLog
import okhttp3.Interceptor
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.Response
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

class HttpCacheInterceptor : Interceptor {
    
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        return processDeflateResponse(response)
    }
    
    private fun processDeflateResponse(response: Response): Response {
        val contentEncoding = response.header("Content-Encoding")
        
        if (contentEncoding?.contains("deflate", ignoreCase = true) == true) {
            val responseBody = response.body
            if (responseBody != null) {
                try {
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
                    
                    val bytes = outputStream.toByteArray()
                    val decompressedBody = bytes.toResponseBody(responseBody.contentType())
                    
                    return response.newBuilder()
                        .removeHeader("Content-Encoding")
                        .body(decompressedBody)
                        .build()
                } catch (e: Exception) {
                    AppLog.e("HttpCacheInterceptor", "processDeflateResponse failed", e)
                }
            }
        }
        
        return response
    }
}

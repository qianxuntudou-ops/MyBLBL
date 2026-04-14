package com.tutu.myblbl.network.http

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.tutu.myblbl.BuildConfig
import com.tutu.myblbl.model.adapter.FlexibleIntAdapter
import com.tutu.myblbl.model.adapter.FlexibleLongAdapter
import com.tutu.myblbl.network.interceptor.HeaderInterceptor
import com.tutu.myblbl.network.interceptor.HttpCacheInterceptor
import com.tutu.myblbl.network.cookie.CookieManager
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object NetworkClientFactory {

    private const val HTTP_CACHE_SIZE = 64L * 1024 * 1024

    fun createOkHttpClient(
        cookieManager: CookieManager,
        userAgentProvider: () -> String,
        acceptLanguageProvider: () -> String,
        cacheDir: File? = null
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .cookieJar(cookieManager)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG) {
                    HttpLoggingInterceptor.Level.BODY
                } else {
                    HttpLoggingInterceptor.Level.NONE
                }
            })
            .addInterceptor(
                HeaderInterceptor(
                    userAgentProvider = userAgentProvider,
                    acceptLanguageProvider = acceptLanguageProvider
                )
            )
            .addInterceptor(HttpCacheInterceptor(cookieManager))
            .retryOnConnectionFailure(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)

        if (cacheDir != null) {
            val httpCacheDir = File(cacheDir, "http_cache")
            builder.cache(Cache(httpCacheDir, HTTP_CACHE_SIZE))
        }

        return builder.build()
    }

    fun createGson(): Gson {
        return GsonBuilder()
            .registerTypeAdapter(Long::class.javaPrimitiveType, FlexibleLongAdapter())
            .registerTypeAdapter(Long::class.javaObjectType, FlexibleLongAdapter())
            .registerTypeAdapter(Int::class.javaPrimitiveType, FlexibleIntAdapter())
            .registerTypeAdapter(Int::class.javaObjectType, FlexibleIntAdapter())
            .create()
    }

    fun createRetrofit(
        baseUrl: String,
        client: OkHttpClient,
        gson: Gson
    ): Retrofit {
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }
}

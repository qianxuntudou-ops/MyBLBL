package com.tutu.myblbl.network.http

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.tutu.myblbl.BuildConfig
import com.tutu.myblbl.model.adapter.FlexibleIntAdapter
import com.tutu.myblbl.model.adapter.FlexibleLongAdapter
import com.tutu.myblbl.network.interceptor.HeaderInterceptor
import com.tutu.myblbl.network.interceptor.HttpCacheInterceptor
import com.tutu.myblbl.network.cookie.CookieManager
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object NetworkClientFactory {

    fun createOkHttpClient(
        cookieManager: CookieManager,
        userAgentProvider: () -> String,
        acceptLanguageProvider: () -> String
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .cookieJar(cookieManager)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG) {
                    HttpLoggingInterceptor.Level.BASIC
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
            .build()
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

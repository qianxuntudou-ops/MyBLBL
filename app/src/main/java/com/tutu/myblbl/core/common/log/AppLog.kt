package com.tutu.myblbl.core.common.log

import android.util.Log
import com.tutu.myblbl.BuildConfig

object AppLog {

    private const val ENABLE_DEBUG_LOGS = true

    fun d(tag: String, message: String) {
        if (!BuildConfig.DEBUG || !ENABLE_DEBUG_LOGS) return
        runCatching {
            Log.d(tag, message)
        }
    }

    fun i(tag: String, message: String) {
        if (!BuildConfig.DEBUG || !ENABLE_DEBUG_LOGS) return
        runCatching {
            Log.i(tag, message)
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (!BuildConfig.DEBUG) return
        runCatching {
            Log.e(tag, message, throwable)
        }
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (!BuildConfig.DEBUG) return
        runCatching {
            Log.w(tag, message, throwable)
        }
    }

    fun v(tag: String, message: String) {
        if (!BuildConfig.DEBUG || !ENABLE_DEBUG_LOGS) return
        runCatching {
            Log.v(tag, message)
        }
    }
}


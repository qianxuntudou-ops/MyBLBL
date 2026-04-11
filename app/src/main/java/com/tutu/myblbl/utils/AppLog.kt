package com.tutu.myblbl.utils

import android.util.Log
import com.tutu.myblbl.BuildConfig

object AppLog {

    fun d(tag: String, message: String) {
        if (!BuildConfig.DEBUG) return
        runCatching {
            Log.d(tag, message)
        }
    }

    fun i(tag: String, message: String) {
        if (!BuildConfig.DEBUG) return
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
        if (!BuildConfig.DEBUG) return
        runCatching {
            Log.v(tag, message)
        }
    }
}


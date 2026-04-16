package com.tutu.myblbl.core.ui.system

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.util.DisplayMetrics
import androidx.appcompat.app.AppCompatActivity

object ScreenUtils {

    private fun findActivity(context: Context): AppCompatActivity {
        var ctx = context
        while (ctx is ContextWrapper) {
            if (ctx is AppCompatActivity) return ctx
            ctx = ctx.baseContext
        }
        throw IllegalStateException("Cannot find Activity from context")
    }

    fun getScreenWidth(context: Context): Int {
        val activity = findActivity(context)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.windowManager.currentWindowMetrics.bounds.width()
        } else {
            @Suppress("DEPRECATION")
            val displayMetrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay.getMetrics(displayMetrics)
            displayMetrics.widthPixels
        }
    }

    fun getScreenHeight(context: Context): Int {
        val activity = findActivity(context)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.windowManager.currentWindowMetrics.bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val displayMetrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay.getMetrics(displayMetrics)
            displayMetrics.heightPixels
        }
    }

    fun getScreenDensity(context: Context): Float {
        return context.resources.displayMetrics.density
    }

    fun dpToPx(context: Context, dp: Float): Int {
        return (dp * getScreenDensity(context) + 0.5f).toInt()
    }

    fun pxToDp(context: Context, px: Float): Int {
        return (px / getScreenDensity(context) + 0.5f).toInt()
    }

    @Suppress("DEPRECATION")
    fun spToPx(context: Context, sp: Float): Int {
        val scaledDensity = context.resources.displayMetrics.scaledDensity
        return (sp * scaledDensity + 0.5f).toInt()
    }

    @Suppress("DEPRECATION")
    fun pxToSp(context: Context, px: Float): Int {
        val scaledDensity = context.resources.displayMetrics.scaledDensity
        return (px / scaledDensity + 0.5f).toInt()
    }

    fun isLandscape(context: Context): Boolean {
        return getScreenWidth(context) > getScreenHeight(context)
    }
}

package com.tutu.myblbl.core.ui.system

import android.content.Context
import android.content.ContextWrapper
import android.view.WindowMetrics
import androidx.appcompat.app.AppCompatActivity

object ScreenUtils {

    private fun getWindowMetrics(context: Context): WindowMetrics {
        val activity = findActivity(context)
        return activity.windowManager.currentWindowMetrics
    }

    private fun findActivity(context: Context): AppCompatActivity {
        var ctx = context
        while (ctx is ContextWrapper) {
            if (ctx is AppCompatActivity) return ctx
            ctx = ctx.baseContext
        }
        throw IllegalStateException("Cannot find Activity from context")
    }

    fun getScreenWidth(context: Context): Int {
        return getWindowMetrics(context).bounds.width()
    }

    fun getScreenHeight(context: Context): Int {
        return getWindowMetrics(context).bounds.height()
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

    fun spToPx(context: Context, sp: Float): Int {
        val scaledDensity = context.resources.displayMetrics.scaledDensity
        return (sp * scaledDensity + 0.5f).toInt()
    }

    fun pxToSp(context: Context, px: Float): Int {
        val scaledDensity = context.resources.displayMetrics.scaledDensity
        return (px / scaledDensity + 0.5f).toInt()
    }

    fun isLandscape(context: Context): Boolean {
        return getScreenWidth(context) > getScreenHeight(context)
    }
}

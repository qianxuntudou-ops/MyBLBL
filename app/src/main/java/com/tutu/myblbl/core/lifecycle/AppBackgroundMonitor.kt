package com.tutu.myblbl.core.lifecycle

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.atomic.AtomicBoolean

object AppBackgroundMonitor : Application.ActivityLifecycleCallbacks {

    @Volatile
    var isInBackground: Boolean = false
        private set

    private val listeners = mutableListOf<BackgroundStateListener>()
    private val initialized = AtomicBoolean(false)
    private var startedActivityCount = 0

    fun init(application: Application) {
        if (initialized.compareAndSet(false, true)) {
            application.registerActivityLifecycleCallbacks(this)
        }
    }

    override fun onActivityStarted(activity: Activity) {
        startedActivityCount += 1
        if (startedActivityCount == 1) {
            updateBackgroundState(false)
        }
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
        if (startedActivityCount == 0 && !activity.isChangingConfigurations) {
            updateBackgroundState(true)
        }
    }

    fun addListener(listener: BackgroundStateListener) {
        synchronized(listeners) { listeners.add(listener) }
    }

    fun removeListener(listener: BackgroundStateListener) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit

    private fun updateBackgroundState(inBackground: Boolean) {
        if (isInBackground == inBackground) return
        isInBackground = inBackground
        notifyListeners(inBackground)
    }

    private fun notifyListeners(inBackground: Boolean) {
        val snapshot: List<BackgroundStateListener>
        synchronized(listeners) { snapshot = listeners.toList() }
        snapshot.forEach { it.onAppBackgroundStateChanged(inBackground) }
    }

    interface BackgroundStateListener {
        fun onAppBackgroundStateChanged(isInBackground: Boolean)
    }
}

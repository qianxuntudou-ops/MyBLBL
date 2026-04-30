package com.tutu.myblbl.core.lifecycle

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

object AppBackgroundMonitor : DefaultLifecycleObserver {

    @Volatile
    var isInBackground: Boolean = false
        private set

    private val listeners = mutableListOf<BackgroundStateListener>()

    fun init(context: Context) {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        isInBackground = false
        notifyListeners(false)
    }

    override fun onStop(owner: LifecycleOwner) {
        isInBackground = true
        notifyListeners(true)
    }

    fun addListener(listener: BackgroundStateListener) {
        synchronized(listeners) { listeners.add(listener) }
    }

    fun removeListener(listener: BackgroundStateListener) {
        synchronized(listeners) { listeners.remove(listener) }
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

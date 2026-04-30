package com.tutu.myblbl.core.startup

import android.os.Handler
import android.os.Looper
import com.tutu.myblbl.core.common.log.AppLog
import java.util.concurrent.atomic.AtomicBoolean

class AppStartupScheduler {

    enum class Phase {
        IMMEDIATE,
        DELAYED,
        IDLE
    }

    private data class StartupTask(
        val name: String,
        val phase: Phase,
        val delayMs: Long = 0,
        val action: () -> Unit
    )

    private val tasks = mutableListOf<StartupTask>()
    private val executed = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun addTask(
        name: String,
        phase: Phase,
        delayMs: Long = 0,
        action: () -> Unit
    ): AppStartupScheduler {
        tasks.add(StartupTask(name, phase, delayMs, action))
        return this
    }

    fun execute() {
        if (!executed.compareAndSet(false, true)) return

        tasks.filter { it.phase == Phase.IMMEDIATE }.forEach { runTask(it) }

        tasks.filter { it.phase == Phase.DELAYED }.forEach { task ->
            mainHandler.postDelayed({ runTask(task) }, task.delayMs)
        }

        tasks.filter { it.phase == Phase.IDLE }.forEach { task ->
            Looper.myQueue().addIdleHandler {
                runTask(task)
                false
            }
        }
    }

    private fun runTask(task: StartupTask) {
        val startMs = System.currentTimeMillis()
        runCatching { task.action() }.onFailure {
            AppLog.e(TAG, "Startup task '${task.name}' failed", it)
        }
        AppLog.i(TAG, "Task '${task.name}' completed in ${System.currentTimeMillis() - startMs}ms")
    }

    companion object {
        private const val TAG = "AppStartup"
    }
}

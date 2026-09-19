package com.pocketllm

import android.app.Application
import android.content.Context
import android.os.PowerManager
import android.os.Build
import androidx.work.Configuration
import androidx.work.WorkManager
import com.pocketllm.di.AppContainer
import com.pocketllm.util.ThermalMonitor

class PocketLLMApp : Application(), Configuration.Provider {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        installCrashLogger()
        super.onCreate()
        instance = this
        container = AppContainer(this)

        // 启动热感知监控（异步、低功耗，只读不强制降温）
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        container.thermalMonitor.start()

        // 初始化 WorkManager（用于断点续传下载）
        WorkManager.initialize(this, workManagerConfiguration)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    /** 把未捕获异常堆栈写到 filesDir/crash.log，便于定位启动闪退。 */
    private fun installCrashLogger() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val trace = android.util.Log.getStackTraceString(e)
                val msg = "=== crash on ${java.util.Date()} thread=${t.name} ===\n$trace\n\n"
                openFileOutput("crash.log", MODE_APPEND).bufferedWriter().use { it.write(msg) }
            } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }

    companion object {
        @Volatile lateinit var instance: PocketLLMApp
            private set
    }
}

package com.pocketllm.util

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * 热感知监控。
 *
 * 数据来源（按优先级）：
 * 1. PowerManager.THERMAL_STATUS_* （API 29+，最权威但分辨率低）
 * 2. /sys/class/thermal/thermal_zoneN/temp （sysfs，分辨率高）
 *
 * 只读，不写、不强制降温。给 [ThermalGovernor] 用。
 */
class ThermalMonitor {

    private val _thermalPercent = MutableStateFlow(0)
    val thermalPercent: StateFlow<Int> = _thermalPercent.asStateFlow()

    private var job: Job? = null
    private var sysfsPath: String? = null

    fun start() {
        if (job?.isActive == true) return
        sysfsPath = findCpuTempZone()
        job = CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                _thermalPercent.value = readPercent()
                delay(2000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun thermalPercent(): Int = _thermalPercent.value

    /**
     * 综合 PowerManager + sysfs 读出 0-100 的热档位。
     * PowerManager 给的是离散等级（0~6），sysfs 给连续值。
     */
    private fun readPercent(): Int {
        // 1. sysfs（更细）
        val sysPercent = sysfsPath?.let { p ->
            try {
                val raw = File(p).readText().trim()
                // 通常是毫摄氏度，例如 45000 = 45°C
                val milli = raw.toLong()
                val celsius = milli / 1000.0
                // 经验阈值：35°C=0%, 60°C=100%
                ((celsius - 35) * 100 / 25).toInt().coerceIn(0, 100)
            } catch (_: Exception) { -1 }
        } ?: -1

        if (sysPercent >= 0) return sysPercent

        // 2. PowerManager fallback（API 29+）
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val pm = appContext?.getSystemService(Context.POWER_SERVICE) as? PowerManager
                val status = pm?.thermalStatus ?: PowerManager.THERMAL_STATUS_NONE
                when (status) {
                    PowerManager.THERMAL_STATUS_NONE -> 0
                    PowerManager.THERMAL_STATUS_LIGHT -> 20
                    PowerManager.THERMAL_STATUS_MODERATE -> 40
                    PowerManager.THERMAL_STATUS_SEVERE -> 70
                    PowerManager.THERMAL_STATUS_CRITICAL -> 90
                    PowerManager.THERMAL_STATUS_EMERGENCY -> 100
                    else -> 0
                }
            } else 0
        } catch (_: Exception) { 0 }
    }

    private fun findCpuTempZone(): String? {
        // 试几个常见路径
        val candidates = listOf(
            "/sys/class/thermal/thermal_zone0/temp",
            "/sys/class/thermal/thermal_zone1/temp",
            "/sys/class/thermal/thermal_zone2/temp",
            "/sys/devices/virtual/thermal/thermal_zone0/temp"
        )
        return candidates.firstOrNull { File(it).exists() }
    }

    companion object {
        @Volatile var appContext: Context? = null
    }
}

package com.jarvis.assistant.core.resource

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager

data class ResourceSnapshot(val batteryPercent: Int?, val lowPower: Boolean, val memoryUsedMb: Long?, val memoryTotalMb: Long?, val cpuCores: Int, val networkExpected: Boolean = true)
enum class ResourceStrategy { NORMAL, CONSERVE, DEFER_EXPENSIVE }

class ResourceManager(private val context: Context) {
    fun snapshot(): ResourceSnapshot {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val battery = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        return ResourceSnapshot(battery, battery?.let { it <= 15 } == true, (info.totalMem - info.availMem) / 1_048_576L, info.totalMem / 1_048_576L, Runtime.getRuntime().availableProcessors())
    }
    fun strategy(expensive: Boolean): ResourceStrategy {
        val s = snapshot()
        return when { !expensive -> ResourceStrategy.NORMAL; s.lowPower -> ResourceStrategy.DEFER_EXPENSIVE; s.memoryUsedMb != null && s.memoryTotalMb != null && s.memoryUsedMb!! > s.memoryTotalMb!! * .85 -> ResourceStrategy.CONSERVE; else -> ResourceStrategy.NORMAL }
    }
}

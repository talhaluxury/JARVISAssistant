package com.jarvis.assistant.hud

import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.notifications.JarvisNotificationListenerService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class DeviceTelemetry(
    val batteryPercent: Int? = null,
    val charging: Boolean? = null,
    val batteryTemperatureC: Float? = null,
    val networkConnected: Boolean? = null,
    val wifiConnected: Boolean? = null,
    val bluetoothEnabled: Boolean? = null,
    val deviceModel: String = "UNAVAILABLE",
    val androidVersion: String = "UNAVAILABLE",
    val ramUsedGb: Float? = null,
    val ramTotalGb: Float? = null,
    val storageUsedGb: Float? = null,
    val storageTotalGb: Float? = null,
    val foregroundApp: String? = null,
    val notificationCount: Int? = null
)

class DeviceTelemetryRepository(private val context: Context) {
    private val _telemetry = MutableStateFlow(readNow())
    val telemetry: StateFlow<DeviceTelemetry> = _telemetry.asStateFlow()
    private var job: Job? = null

    fun start(scope: CoroutineScope, intervalMs: Long = 2000L) {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                _telemetry.value = readNow()
                delay(intervalMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun readNow(): DeviceTelemetry {
        val battery = context.getSystemService(BatteryManager::class.java)
        val level = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
        val temp = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }
            ?.div(10f)

        val batteryIntent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        val cm = context.getSystemService(ConnectivityManager::class.java)
        val network = cm?.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val networkConnected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val wifiConnected = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)

        val bluetooth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { context.getSystemService(BluetoothAdapter::class.java)?.isEnabled }.getOrNull()
        } else {
            runCatching { BluetoothAdapter.getDefaultAdapter()?.isEnabled }.getOrNull()
        }

        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mem)
        val totalRam = mem.totalMem.takeIf { it > 0 }?.toGb()
        val usedRam = totalRam?.let { (mem.totalMem - mem.availMem).toGb() }

        val stat = StatFs(context.filesDir.absolutePath)
        val totalStorage = stat.totalBytes.takeIf { it > 0 }?.toGb()
        val freeStorage = stat.availableBytes.takeIf { it >= 0 }?.toGb()
        val usedStorage = if (totalStorage != null && freeStorage != null) totalStorage - freeStorage else null

        val foreground = JarvisAccessibilityService.currentPackageName()
            ?.takeIf { it != context.packageName }

        val notificationCount = if (JarvisNotificationListenerService.isEnabled)
            JarvisNotificationListenerService.notifications.value.size
        else null

        return DeviceTelemetry(
            batteryPercent = level,
            charging = charging,
            batteryTemperatureC = temp,
            networkConnected = networkConnected,
            wifiConnected = wifiConnected,
            bluetoothEnabled = bluetooth,
            deviceModel = Build.MODEL.takeIf { it.isNotBlank() } ?: "UNAVAILABLE",
            androidVersion = Build.VERSION.RELEASE.takeIf { it.isNotBlank() } ?: "UNAVAILABLE",
            ramUsedGb = usedRam,
            ramTotalGb = totalRam,
            storageUsedGb = usedStorage,
            storageTotalGb = totalStorage,
            foregroundApp = foreground,
            notificationCount = notificationCount
        )
    }

    private fun Long.toGb(): Float = this / 1_073_741_824f
}

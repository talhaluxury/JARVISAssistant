package com.jarvis.assistant.agent

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.view.WindowManager
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PhoneContext(
    val packageName: String?, val appLabel: String?, val screen: String,
    val screenWidth: Int, val screenHeight: Int, val orientation: String,
    val networkConnected: Boolean, val wifiEnabled: Boolean, val bluetoothEnabled: Boolean,
    val batteryPercent: Int, val charging: Boolean, val accessibilityEnabled: Boolean,
    val microphoneGranted: Boolean, val notificationGranted: Boolean,
    val time: String
) {
    fun toPromptString() = buildString {
        appendLine("CURRENT APP: ${appLabel ?: packageName ?: "unknown"}")
        appendLine("PACKAGE: ${packageName ?: "unknown"}")
        appendLine("SCREEN: $screen")
        appendLine("DISPLAY: ${screenWidth}x$screenHeight $orientation")
        appendLine("NETWORK: ${if (networkConnected) "connected" else "offline"}; WIFI: ${if (wifiEnabled) "on" else "off"}")
        appendLine("BLUETOOTH: ${if (bluetoothEnabled) "on" else "off"}")
        appendLine("BATTERY: $batteryPercent%${if (charging) " (charging)" else ""}")
        appendLine("ACCESSIBILITY: ${if (accessibilityEnabled) "ready" else "disabled"}")
        appendLine("MICROPHONE: ${if (microphoneGranted) "granted" else "denied"}; NOTIFICATIONS: ${if (notificationGranted) "granted" else "denied"}")
        append("TIME: $time")
    }
}

class PhoneContextEngine(private val context: Context) {
    fun snapshot(): PhoneContext {
        val pm = context.packageManager
        val screen = JarvisAccessibilityService.captureScreenContext()
        val pkg = screen?.packageName
        val label = pkg?.let { runCatching { pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString() }.getOrNull() }
        val wm = context.getSystemService(WindowManager::class.java)
        val (width, height) = if (Build.VERSION.SDK_INT >= 30) {
            val b = wm?.currentWindowMetrics?.bounds
            (b?.width() ?: 0) to (b?.height() ?: 0)
        } else {
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION") wm?.defaultDisplay?.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val connected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val battery = context.getSystemService(BatteryManager::class.java)
        val percent = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.coerceIn(0, 100) ?: -1
        val chargingIntent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val status = chargingIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val bluetooth = if (Build.VERSION.SDK_INT >= 31 &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.isEnabled == true
        } else false
        val mic = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notification = Build.VERSION.SDK_INT < 33 || androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return PhoneContext(pkg, label, screen?.toPromptString() ?: "No accessibility screen context available.", width, height,
            if (width >= height) "landscape" else "portrait", connected, wifi, bluetooth, percent, charging,
            JarvisAccessibilityService.isEnabled, mic, notification,
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
    }
}

package com.jarvis.assistant.remote

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.jarvis.assistant.BuildConfig
import com.jarvis.assistant.security.AppLock
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Internet relay client. The Android device makes the outbound WebSocket connection,
 * so the target phone does not need an open inbound port or public IP.
 *
 * The relay is intentionally dumb: it only forwards an already-paired session.
 *
 * This client now:
 *  - registers under a fixed, per-install device ID (see [DeviceIdentity]) instead
 *    of a server-issued random code, so Phone 2 can reuse a single saved link.
 *  - automatically reconnects with backoff if the connection drops (server
 *    restart, network blip, hosting provider sleep/wake) instead of leaving the
 *    UI stuck on "Connecting…" forever.
 */
object RemoteRelayClient {
    private var ws: WebSocket? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var reconnectAttempt = 0
    private var appContext: Context? = null

    @Volatile var pairingCode: String = ""
        private set
    @Volatile var controllerConnected: Boolean = false
        private set
    @Volatile var connectedToServer: Boolean = false
        private set
    @Volatile var lastLocation: LocationInfo? = null
        private set
    @Volatile var relayUrl: String = BuildConfig.DEFAULT_REMOTE_RELAY_URL

    data class LocationInfo(val lat: Double, val lng: Double, val accuracy: Double?, val at: Long)

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    /** Call from a Context (Service/Activity) so we can read/create the stable device id. */
    fun start(context: Context, url: String = relayUrl) {
        appContext = context.applicationContext
        relayUrl = url.trimEnd('/')
        reconnectAttempt = 0
        pairingCode = DeviceIdentity.get(appContext!!)
        connectNow()
    }

    private fun connectNow() {
        if (!RemoteSession.active) return
        if (relayUrl.isBlank() || relayUrl.contains("REPLACE_WITH_YOUR_RELAY_URL")) return
        try { ws?.close(1000, "reconnecting") } catch (_: Exception) {}
        val ctx = appContext ?: return
        val id = DeviceIdentity.get(ctx)
        val wsUrl = relayUrl.replaceFirst(Regex("^https://"), "wss://")
            .replaceFirst(Regex("^http://"), "ws://") + "/ws/device?id=" + id
        val request = Request.Builder().url(wsUrl).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectAttempt = 0
                connectedToServer = true
                webSocket.send("""{"type":"register"}""")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val o = JSONObject(text)
                    when (o.optString("type")) {
                        "session" -> pairingCode = o.optString("code").ifBlank { pairingCode }
                        "controller" -> controllerConnected = o.optBoolean("connected")
                        "paired" -> controllerConnected = true
                        "location" -> lastLocation = LocationInfo(
                            o.optDouble("lat"), o.optDouble("lng"),
                            if (o.has("accuracy") && !o.isNull("accuracy")) o.optDouble("accuracy") else null,
                            o.optLong("at", System.currentTimeMillis())
                        )
                        "cmd" -> handleCommand(o)
                    }
                } catch (_: Exception) {}
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connectedToServer = false
                controllerConnected = false
                scheduleReconnect()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connectedToServer = false
                controllerConnected = false
                scheduleReconnect()
            }
        })
    }

    /** Backoff: 3s, 6s, 9s ... capped at 20s, forever while the session is active. */
    private fun scheduleReconnect() {
        if (!RemoteSession.active) return
        reconnectAttempt++
        val delayMs = minOf(3000L * reconnectAttempt, 20000L)
        mainHandler.postDelayed({ if (RemoteSession.active) connectNow() }, delayMs)
    }

    fun sendFrame(jpeg: ByteArray) {
        ws?.send(ByteString.of(*jpeg))
    }

    fun stop() {
        mainHandler.removeCallbacksAndMessages(null)
        try { ws?.close(1000, "stopped") } catch (_: Exception) {}
        ws = null
        connectedToServer = false
        controllerConnected = false
    }

    private fun handleCommand(o: JSONObject) {
        when (o.optString("cmd")) {
            "wake" -> { wakeScreen(); return }
            "get_location" -> { sendCurrentLocation(); return }
            "lock" -> { lockPhone(); return }
        }
        val svc = com.jarvis.assistant.accessibility.JarvisAccessibilityService.current() ?: return
        when (o.optString("cmd")) {
            "tap" -> svc.remoteTap(o.optDouble("x",-1.0).toFloat(), o.optDouble("y",-1.0).toFloat())
            "swipe" -> svc.remoteSwipe(
                o.optDouble("x1",0.0).toFloat(), o.optDouble("y1",0.0).toFloat(),
                o.optDouble("x2",0.0).toFloat(), o.optDouble("y2",0.0).toFloat())
            "back" -> svc.remoteBack()
            "home" -> svc.remoteHome()
            "recents" -> svc.remoteRecents()
        }
    }

    /**
     * Reads the phone's last known location (no new browser/user click needed -
     * this uses the location permission granted ahead of time in the app itself)
     * and sends it back over the already-open connection to whoever is
     * currently controlling this session.
     */
    private fun sendCurrentLocation() {
        val ctx = appContext ?: return
        val fine = ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            ws?.send("""{"type":"location_error","message":"Location permission not granted on this phone"}""")
            return
        }
        try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = lm.getProviders(true)
            val best = providers.mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (_: SecurityException) { null } }
                .maxByOrNull { it.time }
            if (best == null) {
                ws?.send("""{"type":"location_error","message":"No last known location available yet"}""")
                return
            }
            ws?.send(
                JSONObject().apply {
                    put("type", "location")
                    put("lat", best.latitude)
                    put("lng", best.longitude)
                    put("accuracy", best.accuracy)
                    put("at", best.time)
                }.toString()
            )
        } catch (_: Exception) {
            ws?.send("""{"type":"location_error","message":"Could not read location"}""")
        }
    }

    /**
     * Locks the screen immediately using whatever screen lock is already
     * configured on the phone (PIN/pattern/password/biometric). Requires
     * this app to already be an active Device Admin (see AppLock /
     * UNINSTALL PROTECTION). If no secure lock is set up on the phone, this
     * only turns the screen off - it does not create a new password itself
     * (Android has not allowed regular apps to do that since Android 8).
     */
    private fun lockPhone() {
        val ctx = appContext ?: return
        try {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (dpm.isAdminActive(AppLock.deviceAdminComponent(ctx))) {
                dpm.lockNow()
                ws?.send("""{"type":"locked","message":"Phone locked"}""")
            } else {
                ws?.send("""{"type":"location_error","message":"Enable Uninstall Protection (Device Admin) in the app first to allow remote lock"}""")
            }
        } catch (_: Exception) {
            ws?.send("""{"type":"location_error","message":"Could not lock the phone"}""")
        }
    }

    /**
     * Turns Phone 1's display on (does NOT bypass its lock screen PIN/pattern/
     * fingerprint - it only wakes the screen, same as pressing the power
     * button). Screen mirroring generally freezes while the physical display
     * is off, so this lets the owner wake it remotely to resume viewing/
     * controlling it. Requires the WAKE_LOCK permission.
     */
    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        val ctx = appContext ?: return
        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val wl = pm.newWakeLock(
                android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    android.os.PowerManager.ON_AFTER_RELEASE,
                "jarvis:remote-wake"
            )
            wl.acquire(10_000L)
        } catch (_: Exception) {}
    }
}

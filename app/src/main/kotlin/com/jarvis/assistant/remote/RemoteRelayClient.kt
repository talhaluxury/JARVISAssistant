package com.jarvis.assistant.remote

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.jarvis.assistant.BuildConfig
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
    @Volatile var relayUrl: String = BuildConfig.DEFAULT_REMOTE_RELAY_URL

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
}

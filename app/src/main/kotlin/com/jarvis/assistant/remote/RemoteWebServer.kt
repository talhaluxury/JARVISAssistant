package com.jarvis.assistant.remote

import android.content.Context
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/** Small dependency-free HTTP server for an explicitly paired device on the local network. */
class RemoteWebServer(private val context: Context, private val port: Int = 8787) {
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val token = AtomicReference<String?>(null)
    private val pairedIp = AtomicReference<String?>(null)
    private var code: String = ""

    fun start(): String {
        if (server != null) return pairingUrl()
        code = (100000..999999).random().toString()
        pool.execute {
            try {
                val s = ServerSocket(port)
                server = s
                while (!s.isClosed) {
                    try { pool.execute { handle(s.accept()) } } catch (_: Exception) { break }
                }
            } catch (_: Exception) { }
        }
        return pairingUrl()
    }

    fun stop() { try { server?.close() } catch (_: Exception) {}; server = null; token.set(null); pairedIp.set(null); pool.shutdownNow() }
    fun pairingCode(): String = code
    fun pairingUrl(): String = "http://${localIp()}:$port/"

    private fun localIp(): String {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        val ip = wm.connectionInfo.ipAddress
        return listOf(ip and 255, ip shr 8 and 255, ip shr 16 and 255, ip shr 24 and 255).joinToString(".")
    }

    private fun handle(socket: Socket) {
        try {
        socket.use {
            val ip = socket.inetAddress.hostAddress ?: return
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val request = reader.readLine() ?: return
            val parts = request.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val target = parts[1]
            while (reader.readLine()?.isNotEmpty() == true) { }
            val path = target.substringBefore('?')
            val q = parseQuery(target.substringAfter('?', ""))
            val response = when (path) {
                "/" -> html()
                "/pair" -> pair(ip, q["code"].orEmpty())
                "/screen.jpg" -> imageResponse(ip, q)
                "/tap" -> command(ip, q) { svc -> svc.remoteTap(q["x"]?.toFloatOrNull() ?: -1f, q["y"]?.toFloatOrNull() ?: -1f) }
                "/swipe" -> command(ip, q) { svc -> svc.remoteSwipe(q["x1"]?.toFloatOrNull() ?: 0f, q["y1"]?.toFloatOrNull() ?: 0f, q["x2"]?.toFloatOrNull() ?: 0f, q["y2"]?.toFloatOrNull() ?: 0f) }
                "/back" -> command(ip, q) { it.remoteBack() }
                "/home" -> command(ip, q) { it.remoteHome() }
                "/recents" -> command(ip, q) { it.remoteRecents() }
                "/status" -> textResponse(200, "OK", "text/plain")
                else -> textResponse(404, "NOT_FOUND", "text/plain")
            }
            val out = socket.getOutputStream()
            out.write(response); out.flush()
        }
        } catch (_: Exception) { }
    }

    private fun pair(ip: String, supplied: String): ByteArray {
        if (supplied != code) return textResponse(403, "PAIRING_FAILED", "text/plain")
        pairedIp.set(ip); token.set(UUID.randomUUID().toString().replace("-", ""));
        return textResponse(200, token.get()!!, "text/plain")
    }

    private fun allowed(ip: String, q: Map<String,String>): Boolean = pairedIp.get() == ip && q["token"] == token.get()

    private fun command(ip: String, q: Map<String,String>, block: (JarvisAccessibilityService) -> Boolean): ByteArray {
        if (!allowed(ip,q)) return textResponse(403,"NOT_PAIRED","text/plain")
        val svc = JarvisAccessibilityService.current() ?: return textResponse(503,"ACCESSIBILITY_DISABLED","text/plain")
        val ok = block(svc)
        return textResponse(if (ok) 200 else 409, if (ok) "OK" else "FAILED", "text/plain")
    }

    private fun imageResponse(ip: String, q: Map<String,String>): ByteArray {
        if (!allowed(ip,q)) return textResponse(403,"NOT_PAIRED","text/plain")
        val jpeg = RemoteScreenService.latestJpeg.get() ?: return textResponse(503,"SCREEN_CAPTURE_NOT_READY","text/plain")
        return httpBytes(200,"OK","image/jpeg",jpeg)
    }

    private fun html(): ByteArray = """<!doctype html><html><head><meta name=viewport content="width=device-width,initial-scale=1"><title>JARVIS Remote</title><style>body{background:#02070d;color:#38bdf8;font-family:monospace;text-align:center}img{max-width:95vw;max-height:70vh;border:1px solid #38bdf8}button{margin:5px;padding:12px;background:#06131c;color:#38bdf8;border:1px solid #38bdf8;border-radius:5px}#screen{display:none}</style></head><body><h2>JARVIS REMOTE</h2><div id=login><input id=code inputmode=numeric maxlength=6 placeholder="6-digit code"><button onclick=pair()>PAIR</button><p id=msg></p></div><div id=ctrl style="display:none"><img id=screen><br><button onclick=cmd('back')>BACK</button><button onclick=cmd('home')>HOME</button><button onclick=cmd('recents')>RECENTS</button><button onclick=swipe('up')>↑</button><button onclick=swipe('down')>↓</button><button onclick=swipe('left')>←</button><button onclick=swipe('right')>→</button></div><script>let t='';let img=document.getElementById('screen');async function pair(){let c=document.getElementById('code').value;let r=await fetch('/pair?code='+encodeURIComponent(c));t=await r.text();if(r.ok){document.getElementById('login').style.display='none';document.getElementById('ctrl').style.display='block';img.style.display='inline';img.onclick=e=>tap(e);loop()}else document.getElementById('msg').textContent=t}function loop(){img.src='/screen.jpg?token='+t+'&x='+Date.now();setTimeout(loop,450)}async function cmd(x){await fetch('/'+x+'?token='+t)}async function tap(e){let r=img.getBoundingClientRect();let nw=img.naturalWidth,nh=img.naturalHeight;await fetch('/tap?token='+t+'&x='+((e.clientX-r.left)*nw/r.width)+'&y='+((e.clientY-r.top)*nh/r.height))}async function swipe(d){let w=img.naturalWidth,h=img.naturalHeight;let a={up:[w/2,h*.75,w/2,h*.25],down:[w/2,h*.25,w/2,h*.75],left:[w*.8,h/2,w*.2,h/2],right:[w*.2,h/2,w*.8,h/2]}[d];await fetch('/swipe?token='+t+'&x1='+a[0]+'&y1='+a[1]+'&x2='+a[2]+'&y2='+a[3])}</script></body></html>""".toByteArray()

    private fun parseQuery(raw: String): Map<String,String> = raw.split('&').filter { it.contains('=') }.associate { val p=it.split('=',limit=2); URLDecoder.decode(p[0],"UTF-8") to URLDecoder.decode(p[1],"UTF-8") }
    private fun textResponse(code: Int, body: String, type: String): ByteArray = httpBytes(code, "OK", type, body.toByteArray())
    private fun httpBytes(code: Int, status: String, type: String, body: ByteArray): ByteArray = ("HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray() + body
}

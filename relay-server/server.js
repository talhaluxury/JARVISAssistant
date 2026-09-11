const http = require("http");
const crypto = require("crypto");
const WebSocket = require("ws");

const PORT = process.env.PORT || 8787;
// code -> { device, controller, createdAt, fixed }
const sessions = new Map();

function code6() { return String(Math.floor(100000 + Math.random() * 900000)); }

const html = `<!doctype html>
<html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<title>JARVIS Remote</title>
<style>body{background:#02070d;color:#38bdf8;font-family:monospace;text-align:center;margin:20px}
#screen{max-width:96vw;max-height:72vh;border:1px solid #38bdf8;touch-action:none}
button{margin:5px;padding:12px;background:#06131c;color:#38bdf8;border:1px solid #38bdf8;border-radius:6px}
input{padding:12px;width:170px;text-align:center;text-transform:uppercase}</style></head>
<body><h2>JARVIS REMOTE</h2>
<div id="login"><input id="code" inputmode="text" maxlength="16" placeholder="Device code">
<button onclick="manualPair()">PAIR</button><p id="msg"></p></div>
<div id="ctrl" style="display:none"><img id="screen"><br>
<button onclick="cmd('back')">BACK</button><button onclick="cmd('home')">HOME</button>
<button onclick="cmd('recents')">RECENTS</button><button onclick="swipe('up')">↑</button>
<button onclick="swipe('down')">↓</button><button onclick="swipe('left')">←</button><button onclick="swipe('right')">→</button>
<p id="status"></p></div>
<script>
let ws, retryTimer, currentCode, manualStop = false;
const img = document.getElementById('screen');

function connect(c) {
  clearTimeout(retryTimer);
  currentCode = c;
  ws = new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws/control?code=' + encodeURIComponent(c));
  ws.binaryType = 'blob';
  ws.onopen = () => { login.style.display = 'none'; ctrl.style.display = 'block'; status.textContent = 'Connected'; msg.textContent = ''; };
  ws.onmessage = e => {
    if (typeof e.data === 'string') {
      try { const m = JSON.parse(e.data); status.textContent = m.message || m.type; } catch (_) {}
    } else { img.src = URL.createObjectURL(e.data); }
  };
  ws.onclose = () => {
    status.textContent = 'Disconnected - retrying…';
    if (!manualStop) retryTimer = setTimeout(() => connect(c), 3000);
  };
  ws.onerror = () => {
    msg.textContent = 'Not paired yet, retrying…';
    if (!manualStop) retryTimer = setTimeout(() => connect(c), 3000);
  };
}

function manualPair() {
  manualStop = false;
  const c = document.getElementById('code').value.trim();
  if (!c) return;
  history.replaceState(null, '', '?code=' + encodeURIComponent(c));
  connect(c);
}

// Auto-pair straight from a saved/bookmarked link like ?code=XXXXXXXX
window.addEventListener('load', () => {
  const p = new URLSearchParams(location.search);
  const c = p.get('code') || p.get('id');
  if (c) { document.getElementById('code').value = c; manualPair(); }
});

function cmd(c) { if (ws && ws.readyState === 1) ws.send(JSON.stringify({ type: 'cmd', cmd: c })); }
function tap(e) {
  const r = img.getBoundingClientRect();
  const x = (e.clientX - r.left) * img.naturalWidth / r.width;
  const y = (e.clientY - r.top) * img.naturalHeight / r.height;
  if (ws && ws.readyState === 1) ws.send(JSON.stringify({ type: 'cmd', cmd: 'tap', x, y }));
}
img.addEventListener('pointerup', tap);
function swipe(d) {
  const w = img.naturalWidth, h = img.naturalHeight;
  const a = { up: [w/2,h*.75,w/2,h*.25], down: [w/2,h*.25,w/2,h*.75], left: [w*.8,h/2,w*.2,h/2], right: [w*.2,h/2,w*.8,h/2] }[d];
  if (ws && ws.readyState === 1) ws.send(JSON.stringify({ type: 'cmd', cmd: 'swipe', x1: a[0], y1: a[1], x2: a[2], y2: a[3] }));
}
</script></body></html>`;

const server = http.createServer((req, res) => {
  if (req.url === "/health") { res.writeHead(200, { "Content-Type": "text/plain; charset=utf-8", "Cache-Control": "no-store" }); res.end("ok"); return; }
  if (req.url === "/" || req.url.startsWith("/?")) {
    res.writeHead(200, { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store" }); res.end(html); return;
  }
  res.writeHead(404); res.end("Not found");
});

const wss = new WebSocket.Server({ server, path: "/ws/device" });
const wsc = new WebSocket.Server({ noServer: true });

wss.on("connection", (ws, req) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  const fixedId = (url.searchParams.get("id") || "").trim().toUpperCase().slice(0, 32);
  let code;

  if (fixedId) {
    // Fixed per-install device ID: reuse the same session slot across
    // reconnects so a bookmarked controller link keeps working, and so a
    // controller can pair in advance and just wait for the phone to reconnect.
    code = fixedId;
    const existing = sessions.get(code);
    if (existing) {
      if (existing.device && existing.device !== ws && existing.device.readyState === WebSocket.OPEN) {
        try { existing.device.close(); } catch (_) {}
      }
      existing.device = ws;
      existing.createdAt = Date.now();
      if (existing.controller && existing.controller.readyState === WebSocket.OPEN) {
        existing.controller.send(JSON.stringify({ type: "controller", connected: true }));
      }
    } else {
      sessions.set(code, { device: ws, controller: null, createdAt: Date.now(), fixed: true });
    }
  } else {
    do { code = code6(); } while (sessions.has(code));
    sessions.set(code, { device: ws, controller: null, createdAt: Date.now(), fixed: false });
  }

  ws.send(JSON.stringify({ type: "session", code }));

  ws.on("message", (data, isBinary) => {
    if (isBinary) {
      const s = sessions.get(code);
      if (s && s.controller && s.controller.readyState === WebSocket.OPEN) s.controller.send(data, { binary: true });
    }
  });

  ws.on("close", () => {
    const s = sessions.get(code);
    if (!s || s.device !== ws) return; // a newer connection already replaced this one
    if (s.controller && s.controller.readyState === WebSocket.OPEN) {
      s.controller.send(JSON.stringify({ type: "controller", connected: false }));
    }
    if (s.fixed) {
      s.device = null; // keep the session slot so a paired controller can wait for reconnect
    } else {
      sessions.delete(code);
    }
  });
});

server.on("upgrade", (req, socket, head) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  if (url.pathname !== "/ws/control") { socket.destroy(); return; }
  const code = (url.searchParams.get("code") || "").trim().toUpperCase();
  const s = sessions.get(code);
  if (!s) { socket.write("HTTP/1.1 404 Not Found\r\n\r\n"); socket.destroy(); return; }

  wsc.handleUpgrade(req, socket, head, (ws) => {
    if (s.controller && s.controller !== ws && s.controller.readyState === WebSocket.OPEN) {
      try { s.controller.close(); } catch (_) {}
    }
    s.controller = ws;
    const online = s.device && s.device.readyState === WebSocket.OPEN;
    ws.send(JSON.stringify({ type: "paired", message: online ? "Paired" : "Paired - waiting for phone to come online" }));
    ws.on("message", (data, isBinary) => {
      if (!isBinary && s.device && s.device.readyState === WebSocket.OPEN) s.device.send(data.toString());
    });
    ws.on("close", () => {
      if (s.controller === ws) s.controller = null;
      if (s.device && s.device.readyState === WebSocket.OPEN) s.device.send(JSON.stringify({ type: "controller", connected: false }));
    });
  });
});

// Fixed-id sessions whose device never comes back would otherwise sit in
// memory forever; sweep out ones that have been offline for a long time.
setInterval(() => {
  const now = Date.now();
  for (const [code, s] of sessions) {
    const deviceOffline = !s.device || s.device.readyState !== WebSocket.OPEN;
    const controllerOffline = !s.controller || s.controller.readyState !== WebSocket.OPEN;
    if (deviceOffline && controllerOffline && now - s.createdAt > 24 * 60 * 60 * 1000) {
      sessions.delete(code);
    }
  }
}, 60 * 60 * 1000);

server.listen(PORT, () => console.log(`JARVIS relay listening on ${PORT}`));

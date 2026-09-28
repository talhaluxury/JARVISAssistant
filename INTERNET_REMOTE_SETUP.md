# JARVIS Internet Remote Setup

This is an explicit-consent remote-control feature for the owner's Android phone.

## 1. Deploy relay-server

Deploy `relay-server` as a public Node.js Web Service (Render/Railway/Fly/etc.).

For Render:
- Root directory: `jarvis_final/relay-server`
- Build command: `npm install`
- Start command: `npm start`
- The service should expose `https://YOUR-RELAY-DOMAIN/health` and return `ok`.

Production should use the HTTPS URL supplied by the hosting provider. The Android client automatically changes `https://` to `wss://` for the device WebSocket.

## 2. Configure GitHub Actions

In the GitHub repository:
Settings -> Secrets and variables -> Actions -> Variables -> New repository variable

Name: `REMOTE_RELAY_URL`
Value: `https://YOUR-RELAY-DOMAIN`

Do not include `/ws/device` or a trailing slash.

The workflow passes this value to Gradle at build time; it is not a secret.

## 3. Build APK

Run GitHub Actions -> Build JARVIS APK -> Run workflow.
Download the `jarvis-debug-apk` artifact.

## 4. Phone 1 (target)

Install the APK. Open JARVIS -> CTRL.
The screen immediately shows a fixed 8-character pairing code for this install
(saved locally, generated once - the same code is reused for every future
session, reconnect, and app restart).
Tap **COPY DIRECT LINK FOR PHONE 2** to copy `https://YOUR-RELAY-DOMAIN/?code=<code>`.
Then tap START + SHARE SCREEN. Android will show the system screen-sharing
consent dialog - accept it. JARVIS keeps a visible foreground notification
while sharing.

## 5. Phone 2 (controller)

Open the copied link in a browser (or type the relay URL and paste the code
into the PAIR box). The link auto-pairs on load - no typing needed once it's
bookmarked. The browser receives live JPEG screen frames and can send tap,
swipe, Back, Home, and Recents commands.

Both phones can be on different networks. Phone 1 makes the outbound WebSocket
connection, so it does not need a public IP or port-forwarding.

## Reconnects

Both the app and the controller page retry automatically if the relay
connection drops (server restart, network blip, or the free hosting plan
sleeping and waking up). Phone 1's pairing code never changes, so a paired
controller will simply pick back up once the phone reconnects - no need to
regenerate or re-copy a code.

Note: the free Render plan sleeps after inactivity and can take 20-40 seconds
to wake on the first request. Opening `https://YOUR-RELAY-DOMAIN/health` first
is a quick way to wake it before you need the remote.

## Security

Pairing is explicit and visible. The relay forwards only the paired session and does not persist screen frames. Stop the session on Phone 1 to end it.

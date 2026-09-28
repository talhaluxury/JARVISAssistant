# JARVIS Remote Control

This feature is for the owner's own Android device. It uses Android Accessibility for input gestures and MediaProjection for an explicitly approved screen capture session.

## GitHub Actions
`.github/workflows/build.yml` builds a debug APK on pushes to `main`, pull requests, or manual dispatch and uploads it as the `jarvis-debug-apk` artifact.

## Pairing
1. Install on the target phone (Phone 1).
2. Open **Remote**. The screen shows a fixed 8-character pairing code for this
   install (generated once and stored locally - it does not change between
   sessions, reconnects, or app restarts).
3. Tap **COPY DIRECT LINK FOR PHONE 2** and send/open that link
   (`https://<your-relay>/?code=<code>`) in a browser on Phone 2. Bookmark it -
   it keeps working every time.
4. On Phone 1, tap **START + SHARE SCREEN** and approve Android's
   MediaProjection dialog.
5. Phone 2's page auto-pairs from the link. Once Phone 1's screen-share is
   active, control starts flowing; until then it shows "waiting for phone to
   come online" and keeps retrying on its own.
6. Stop the session on Phone 1 when finished.

The server rejects control commands until the remote browser has paired with
the device's code. If the connection between either phone and the relay drops
(server restart, network blip, host sleep/wake), both sides automatically
retry the connection - you no longer need to force-close the app or generate
a new code by hand. The app does not provide hidden/background control or
bypass Android permission dialogs.

# JARVIS HUD Upgrade

This build upgrades the existing JARVISAssistant project with a real Android `WallpaperService` HUD and a shared app↔wallpaper state bridge.

## Implemented
- Real animated `WallpaperService` with concentric rings, reactor core, scanline, grid, telemetry panels and adaptive frame pacing.
- Shared `WallpaperEventBus` using `StateFlow` plus durable SharedPreferences recovery.
- HUD states: IDLE, LISTENING, THINKING, PLANNING, EXECUTING, VERIFYING, COMPLETED, ERROR, PERMISSION_REQUIRED.
- Real telemetry: battery, charging, temperature, network, Wi-Fi, Bluetooth, RAM, storage, model, Android version, foreground package when Accessibility is enabled, and notification count when Notification Access is enabled.
- Offline/local HUD commands: activate, standby, full, minimal, system status, battery, network, notifications, power-saving.
- AI command schema updated so the model can request HUD actions through the existing closed command parser.
- Accessibility and notification services publish HUD-relevant state changes.
- Main command-center screen redesigned around the HUD visual language instead of a chatbot layout.
- Settings now exposes HUD mode, brightness, animation intensity/speed, telemetry visibility and power-saving renderer.
- Android live wallpaper manifest registration and picker flow remain real system APIs.
- Added unit coverage for local and AI HUD command routing.

## Android security boundaries
The upgrade does not silently enable AccessibilityService or Notification Listener access and does not use hidden APIs. Foreground-app and notification telemetry become `UNAVAILABLE`/omitted when the corresponding user authorization is not active.

## Build
The repository intentionally uses the existing GitHub Actions Gradle setup (`gradle/actions/setup-gradle`, Gradle 8.7). The project does not require a committed Gradle wrapper.

Run the existing workflow or, with Gradle 8.7 installed:

    gradle assembleDebug

The APK output is `app/build/outputs/apk/debug/app-debug.apk`.

## Chart candle reading + history loading (latest)
- ChartCandleDetector: candles are now found by colour run + width (touching same-colour candles are cut into equal
  slots; body = rows mostly inked). Fixes "no chart candles read / 0 clean candles".
- New HistoryStitcher + "LOAD CHART HISTORY" button (Quotex Analyzer screen): reads the live chart, scrolls it back
  (you drag it, or JARVIS does with "Let JARVIS scroll the chart" ON), stitches overlapping frames by exact open/close
  match, and saves older candles. Frames without an exact overlap are rejected.
- The only gesture JARVIS can make on Quotex is JarvisAccessibilityService.panChart: one horizontal drag inside the
  chart band. Buy/Sell taps and typing stay blocked. Setting is OFF by default.
- Chart timeframe in Quotex MUST equal JARVIS candle length (Settings -> candle seconds), otherwise candle times are wrong.
- Not compiled or run on a phone in the authoring session: build it and test on the device.

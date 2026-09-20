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

## One-Tap Accessibility Setup (2026-09-20)

Added a first-run JARVIS Phone Control setup screen.

### Behavior
- On first launch, JARVIS checks whether `JarvisAccessibilityService` is already enabled.
- If it is not enabled, JARVIS shows **ENABLE JARVIS CONTROL**.
- On Android 12+ it attempts to open the JARVIS-specific Accessibility details page directly.
- If the device/OEM Settings app does not support that deep link, it falls back to the main Accessibility settings page.
- When the user enables JARVIS and returns, the app detects the service automatically and continues to the normal boot sequence.
- A **CONTINUE WITHOUT PHONE CONTROL** option remains available so the assistant can be used without automation.

### Security boundary
Android does not permit an ordinary app to silently enable its own AccessibilityService. The setup therefore removes the need to manually search for JARVIS in Settings, while keeping the required human confirmation.

### Files changed
- `app/src/main/kotlin/com/jarvis/assistant/MainActivity.kt`
- `app/src/main/kotlin/com/jarvis/assistant/ui/screens/setup/AccessibilitySetupScreen.kt`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/res/values/strings.xml`

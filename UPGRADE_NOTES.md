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
  chart band. Buy/Sell taps and typing stay blocked. Setting is ON by default (switch it off on the Quotex Analyzer screen to drag the chart yourself).
- Chart timeframe in Quotex MUST equal JARVIS candle length (Settings -> candle seconds), otherwise candle times are wrong.
- Not compiled or run on a phone in the authoring session: build it and test on the device.
- Timeframe is now read from the screen (candle spacing in px / px-per-minute of the HH:MM time labels, snapped to a
  standard timeframe, confirmed on 3 reads) and JARVIS's candle length follows it automatically. No taps needed.
- Auto chart scroll: slow 0.9 s drags, retry with a small drag back if the chart coasted past the overlap, then
  drags back to the live edge (the chart stops there by itself).

## Demo trading engine - completion pass
Not compiled or run in the authoring session (no Android toolchain there): push to GitHub, let the build workflow run, and send back any error.

- **Trade history in Room.** Closed demo trades now live in the app database (`JarvisDatabase` v4, table `demo_trades`, migration 3->4). The engine keeps its last 500 in memory; the database keeps up to 5000. The JSON state file keeps only account/settings/open trades plus a 50-trade safety copy. Trades found in an old JSON file are moved into the database on first start. If the database cannot be opened the engine falls back to the JSON file.
- **VWAP and average volume.** Quotex shows no real traded volume, so the screen reader cannot read any. JARVIS now counts price readings per candle (`Candle.ticks`, tick activity) and computes a rolling 60-candle VWAP and an activity ratio (newest candle vs the previous 20) from it. Candles with no tick count (chart-image candles, candles loaded from storage) get the window's median count; with no tick data at all, VWAP is equal-weighted and the activity ratio is simply unavailable. VWAP is a scored component (weight 4; trend/EMA/momentum weights were lowered so the total stays 100) and a strong tick surge in the signal's direction adds 2 confidence points, a dead market takes 3.
- **Foreground service.** `DemoEngineService` (type specialUse) starts when Auto Demo Trading is switched ON and stops when it is OFF (or from the notification's Stop button). It keeps the process alive while paper trades wait for expiry and shows the engine status. It does not read the screen: prices still come through the Quotex monitoring service, and the notification says "waiting for Quotex data" when that is not running.
- **Settings screen.** The full demo settings are now in Settings -> "Demo Trading (Paper)" and still in the Demo Trading screen's Settings tab (same composable, always in sync).
- **New strategies** (on by default for new installs; existing installs can switch them on in the strategy list): Squeeze Breakout (Bollinger squeeze + close outside the previous band), VWAP (trend pullback / range stretch), Stochastic Reversal (range markets only, at the Bollinger band). In a range market a Stochastic Reversal vote now also satisfies the range filter, next to Support/Resistance rejection.
- **Tests added.** `FullEngineScenarioTest` (whole engine over sideways, mixed, trending and shock markets: hourly/daily/cooldown/stake limits, money conservation, determinism, auto-off), `TradeHistoryTest`, `VwapActivityTest`, `ExtraStrategiesTest`.

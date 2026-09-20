# JARVIS WinGo Analyzer

A read-only analysis module for the WinGo Big/Small game history shown on your own screen.
It reads results, stores them, runs a statistical ensemble and **reports what it can measure** – nothing more.

## What it will and will not do

* It never bets, taps, swipes or types. It has no access to the accessibility "perform action" APIs
  (a unit test, `wingoModuleContainsNoAutomationOrInputInjection`, fails the build if that ever changes).
* It never claims certainty. Every message says "lean" / "signal", and the app shows measured walk-forward
  accuracy, not a promised one.
* It says **WAIT** when: history < 100 rounds, ensemble confidence < 55 %, the models disagree, or (default)
  no statistically verified edge has been measured yet.
* Screen capture only starts after Android's own consent dialog and shows a permanent notification with a Stop button.

### Read this before relying on any signal

If the game's numbers are random (which is what a fair game is), no model can beat a coin flip in the long run,
and payouts below 2x mean a coin flip loses money over time. The analyzer is built to *show you that honestly*:

* **Test mode → "Random control"** generates 3,000 truly random rounds and backtests them. Expect ~50 % accuracy.
* Compare that with the backtest of your **real recorded history**. If they look the same, there is no edge to find.
* The edge gate (z ≥ 2.33 over ≥ 200 candidate calls) suppresses almost all false signals on random data,
  but it is a statistical test re-checked every round, so occasional short false bursts still happen
  (about 1 random 2,500-round series in 12 in the Python prototype). A signal is evidence to check, not a promise.

## Architecture (package `com.jarvis.assistant.wingo`)

| Area | Files |
|---|---|
| Domain | `domain/WinGoModels.kt` – Big/Small rule, colours, period format, `WinGoConfig` |
| Models A–G | `analysis/WinGoModels.kt` – recent frequency, rolling windows, streak, transition, deviation, weighted recency, pattern |
| Ensemble + gate | `analysis/WinGoAnalysisEngine.kt` – weights from walk-forward record, agreement check, edge gate |
| Model H (backtest) | `analysis/WinGoBacktestEngine.kt`, `analysis/PerformanceAnalyzer.kt` |
| OCR | `ocr/MlKitTextReader.kt` (ML Kit, on-device), `WinGoOCRParser`, `ResultValidator`, `ResultStabilizer`, `GameRegionDetector`, `FrameDiff`, `CsvImporter` |
| Capture | `capture/ScreenCaptureManager.kt`, `WinGoMonitorService.kt` (foreground, mediaProjection), `WinGoCaptureConsentActivity.kt` |
| Storage | `data/*` – **separate** Room database `wingo_db` (JarvisDatabase and its migrations are untouched) |
| Glue | `WinGoCoordinator.kt`, `WinGoModule.kt` (wired as `container.winGo`), `WinGoSettings.kt` |
| UI | `overlay/WinGoOverlayService.kt` (floating HUD), `ui/screens/wingo/*` (setup, analytics, test mode) |
| Chat/voice | `voice/*` – hooked before `LocalIntentRouter` in `AssistantViewModel` and `OverlayService` |

### Trust pipeline for every screen reading
OCR words → row grouping → `ResultValidator` (period format, number 0-9, Big/Small label agrees with number,
colour consistent, OCR confidence ≥ 0.70, period sequence chains ±1) → `ResultStabilizer` (identical in 2 samples)
→ database (unique period index drops duplicates). Anything unsure is counted as "uncertain" and **never stored**.

### No look-ahead
`RoundHistory.window(all, i, cap)` only exposes rounds before `i`. Backtests, warm-up and live predictions all use
`WinGoAnalysisEngine.walkForward`: predict from the past, compare with the real result, then update weights.
A unit test with a spy model checks that no model ever sees the round it is predicting.

## Using it

1. Open JARVIS → long-press the core → **WIN** (or navigate to the `wingo` route).
2. Allow "display over other apps", tap **Start monitoring**, approve Android's capture dialog.
3. Open the game yourself and show the results history. Detection is automatic; use the region sliders if needed.
4. Let it collect ≥ 100 verified rounds, then switch on **Live analysis**.

Voice/chat examples (they only trigger when they mention the game, or while monitoring is on):
"JARVIS analyze game", "show signal", "show accuracy", "why", "backtest last 100 rounds", "start/stop game monitoring".

CSV test mode format: `Period,Number,BigSmall,Color` (last two optional), one round per line.

## Build and test

```
gradle testDebugUnitTest --tests "com.jarvis.assistant.wingo.*"
gradle assembleDebug
gradle assembleRelease bundleRelease        # add -PRELEASE_KEYSTORE_PATH/-PASSWORD/-KEY_ALIAS/-KEY_PASSWORD to sign
```
The GitHub Actions workflow `wingo-verify.yml` runs the tests and builds; the existing `build.yml` is unchanged.

## Known limitations
* Portrait screen assumed; region is stored as fractions, so it survives resolution changes but not rotation.
* Only the visible history page is read; flipping pages by hand back-fills older rounds.
* Colour dots cannot be OCR'd, so colour is derived from the number (and only cross-checked if colour *words* are visible).
* Overlay uses `FLAG_SECURE` so the analyzer never reads its own HUD; this also blocks screenshots of the HUD.
* This module was written without a Kotlin compiler available. Run the CI workflow first and fix any compile message it reports.

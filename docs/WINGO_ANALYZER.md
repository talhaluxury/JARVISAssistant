# JARVIS WinGo Analyzer (and Quotex Analyzer, see the end)

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


## Deep history analysis and next-round estimate (WinGo)

The engine now goes well beyond "recent count says BIG":

* **Ten models**, each returning a probability, sample size and evidence: Recent Frequency, Rolling Windows
  (5 to 500+ rounds), Streak, Transition (order 1-3), Baseline Deviation, Weighted Recent, exact **Pattern**
  matching (context length 3-8, at least 20 earlier occurrences), **Similar Pattern** matching (near-misses,
  weighted by closeness), **Sequence Outcome** (same BIG/SMALL composition regardless of order) and **Regime**
  (streaky/balanced/alternating stretches).
* Every model and every one of its sub-rules (a window size, a pattern length, a similarity rule) has its own
  walk-forward record. A sub-rule that stops working out of sample loses weight and can be **switched off**
  automatically — it keeps being tracked in case it starts working again.
* **Signal strength is capped by what was actually measured.** Even a confident-looking ensemble estimate is
  held to MEDIUM or LOW (or WAIT) if the real walk-forward accuracy of comparable calls hasn't reached that
  level yet — the app never claims more than it has verified.
* The next-round estimate is generated and stored **before** the result exists, then verified once the real
  number appears. The overlay's STATUS line shows where the round is: ANALYZING → PREDICTION READY → WAITING
  FOR RESULT → RESULT DETECTED → VERIFYING → PREDICTION VERIFIED. The predicted side is never shown as if it
  were the result.
* Ask "what pattern", "matching patterns", "why big" / "why small", "model performance", or "backtest this
  pattern" — every answer cites real stored numbers (occurrences, historical hit rate, out-of-sample accuracy).
* The backtest report now also breaks accuracy down by pattern length, by pattern sample size, and shows
  last-100 / last-500 / all-time walk-forward accuracy, matching what the app is allowed to claim.

## History pages and missing rounds (WinGo)

The game keeps about 50 pages of 10 rounds. You can walk back through them yourself and JARVIS reads what is on screen:

* Open *Game history* and press the ‹ arrow one page at a time. Stay about 2 seconds on each page: a round is only saved
  after it was read identically in two samples (the sample interval is 1 s). JARVIS never presses anything for you.
* Rows older than the newest stored round are back-filled into their place. The page indicator ("3/50") is read too,
  because the capture area now extends down to it.
* While an older page is showing, live signals are **paused** (a signal for "the next round" would be stale) and the HUD
  says HISTORY. Go back to page 1 and they resume. The engine is rebuilt once, about 2.5 s after the last page, not per page.
* **Missing rounds:** if rounds were skipped (you were on another screen, or a row could not be read cleanly), the app lists
  the missing period ranges with a rough page number ("around page 3"; pages shift by one row every round). Look for those
  periods in the game history - the app saves them when they appear. Ask the chat: "missing rounds".
* A row whose digit or label is unreadable (the coloured digits are the hardest to OCR) is skipped rather than guessed, so it
  will show up as missing; revisit that page and it usually reads on the second try.

## Data backup and restore (WinGo)

*Setup screen → DATA BACKUP → Export data* saves every verified round as `wingo_history.csv` (pick Downloads).
After a reinstall, *Restore data* adds every round that is missing; rounds already stored are kept, and the engine is
rebuilt from the merged history. Only verified rounds are exported; live accuracy statistics start fresh and are
recomputed by the backtest from the restored rounds.

---

# JARVIS Quotex Analyzer

Same idea as WinGo, for a Quotex chart on your own screen: it reads the asset name and the live price from the chart,
builds candles, runs a walk-forward ensemble, and answers in a small floating chat. **Analysis only.** It cannot place,
prepare or confirm a trade (the existing `trading/QuotexModels.kt` boundary is kept, and the same automated test that
guards WinGo now also scans the Quotex folder for tap/gesture/automation APIs).

Open it from the radial menu → **QTX**, or say "JARVIS Quotex signal / why / accuracy / backtest".

## How it works
1. Screen capture (Android consent dialog, visible notification) → OCR of the region you choose (default: top 6% to 85% of the screen).
2. `GridPriceFinder`: the axis labels are evenly spaced; the live price is the one label that is *off that grid*, and it is
   cross-checked against where it should sit vertically. If anything is ambiguous, no price is recorded.
3. Ticks → candles (10/15/30/60 s) → stored in a separate database (`quotex_db`).
4. Six data-driven models (EMA trend, RSI zone, Bollinger zone, momentum, last-3-candles, recent drift) look at what price
   did *expiry* candles after past candles in the same state. Weights come from each model's walk-forward record.
5. The same gates as WinGo: confidence ≥ 55%, models agree, and a **verified edge**. Because overlapping expiries are
   correlated, the edge test and the backtest verdict use only non-overlapping calls.
6. The backtest report shows break-even accuracy for your payout and a simulated flat-stake profit/loss.

## Honest limits
* Short-term price moves are close to random; OTC assets are priced by the broker. On a fair random walk the analyzer
  shows ~50% and (almost) no signals - use *Test mode → Random control* to see that yourself.
* With an 85% payout you must win ~54% of trades just to break even.
* The chart reader is a heuristic. Check it on your phone: the *Chart* line should say PRICE READ and the last price
  should match the screen. If it does not, adjust the screen area sliders, or add prices by hand to test the rest.
* Only one screen-capture monitor (WinGo or Quotex) should run at a time.
* Backup: *DATA BACKUP → Export data* (`quotex_candles.csv`) and *Restore data* work exactly like WinGo's.

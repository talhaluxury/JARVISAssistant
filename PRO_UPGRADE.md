# Quotex analysis assistant - pro upgrade (v8)

Analysis only. No order placement, no Buy/Sell taps, no hidden interaction with Quotex. Nothing here predicts the next candle.

## New (package `quotex/pro/`)
- `FakeBreakoutDetector` - fake breakout / breakdown + 0-100 pattern quality. WIRED: a fake move against the setup (quality >= 60) => WAIT.
- `EntryQuality` - EARLY / FORMING / CONFIRMED / OPTIMAL / LATE / INVALID. WIRED: LATE or INVALID => WAIT, whatever the score.
- `SetupScore` - 0-100 setup STRENGTH (fixed evidence weights, penalties, timing cap). Never labelled as a win probability. WIRED into `AgentReport.setupScore`.
- `SignalMemory` - repeat failures in a regime, duplicates, CALL/PUT flip-flop => REDUCE / WAIT. WIRED in `AgentRuntime` and fed by journal outcomes.
- `MonteCarlo` - shuffle/bootstrap, drawdown percentiles, ruin probability, break-even win rate, sensitivity, parameter robustness.
- `StrategyLab` - user rules (EMA / RSI / ADX), `LabBacktester` (chronological, TRAIN 60 / VALIDATION 20 / TEST 20, 5 walk-forward windows, no look-ahead), `StrategyDeployGate` (nothing is enabled without OOS >= 100 trades, positive OOS expectancy, walk-forward, robustness, 30 paper trades).
- `ProNarrator` / `ExplanationBuilder` - explainable signal text built only from structured fields; says "incomplete" when inputs are missing.

## Wiring / UI
- New screen `QuotexProScreen` (button on the Quotex Analyzer screen): LIVE (signal, score, entry quality, countdown, reasons), STRUCTURE, PAPER, STATS (with sample-size guard), STRATEGY LAB, BACKTEST, JOURNAL (signal IDs), SETTINGS, DIAGNOSTICS.
- Settings added: minimum setup score (default 60), minimum data quality (default 60 -> "DATA QUALITY TOO LOW, analysis paused"), debug logging.
- Overlay now shows setup score, entry quality and top warnings.
- Voice/chat: "analyze chart", "show current signal", "show reasons", "show market structure", "show risk", "show historical performance", "monte carlo", "pause analysis", "resume analysis", plus all earlier commands.
- SAFETY: `auto_chart_pan` (JARVIS dragging the chart) now defaults to OFF. Buy/Sell taps remain blocked.
- CI: `build.yml` runs `gradle testDebugUnitTest` before assembling.
- Tests: `quotex/pro/ProModulesTest.kt` (fake breakout, entry quality, memory, Monte Carlo, gate, lab look-ahead, score caps, stats).

## Already in the project (kept)
Data validation, multi-timeframe resampling, EMA 9-200 / RSI / MACD / ATR / ADX / Bollinger / Stochastic, regime classifier, BOS/CHoCH/equal highs-lows/sweeps, price action, 10+ strategies, confluence, risk engine, signal state machine, walk-forward backtester, news filter (UNAVAILABLE when no feed), journal, overlay, voice/chat.

## Round 3 (everything that was missing)
- Strategy Lab is now fully editable in-app: create / edit / delete rules (EMA fast/slow, RSI band, ADX min, CALL or PUT side), BACKTEST (train/validation/test + walk-forward + Monte Carlo + parameter sweep), COMPARE table, PAPER TEST on live candles (virtual, persisted), ENABLE only through `StrategyDeployGate`. Editing a rule resets it to DRAFT and wipes its paper stats.
- Enabled lab rules act ONLY as extra evidence: agreeing adds a passed check; disagreeing adds a warning and downgrades a setup to WATCH. They can never create a CALL/PUT.
- Parameter robustness is now real: each rule is re-run with every parameter nudged one step (`RuleSpec.neighbours`, `LabBacktester.sweep`).
- News: you can maintain an event calendar in Pro > Settings (title, minutes from now, impact, HIGH window). With the switch OFF the app shows NEWS DATA UNAVAILABLE; with it ON your list is the truth (an empty list means "no events").
- Settings now apply at the next candle with no restart (`coordinator.applySettings()`), and include: timeframe hierarchy (middle / higher, AUTO default), block HIGH volatility, per-strategy on/off (indicators are evidence inside strategies), minimum score, minimum data quality, debug logging.
- Voice/chat: "strategy lab", "news".
- New tests: `LabStoreTest.kt` (round trips, corrupt data, no-peeking paper settlement, sweep, news codec).

## Limits (be honest)
- NOT compiled or run here (no Android SDK / Gradle / network). First CI run may show compile errors - send them back.
- The ENABLE gate is strict on purpose (>= 100 out-of-sample trades from validation+test, >= 30 paper trades, robust neighbourhood). On short histories it will stay closed.
- Lab rules support EMA / RSI / ADX conditions only.
- News is manual (no live economic-calendar feed is bundled; none is invented).
- Paper results are virtual; a fixed expiry and payout (0.85) are assumed.
- A statistical setup score is not a prediction and never guarantees profit.

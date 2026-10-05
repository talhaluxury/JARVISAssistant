# Quotex Trading Intelligence Agent

Analysis, alerts and journaling, plus an opt-in AUTO DEMO TRADE switch on the overlay (demo accounts only, see below).
It never asks for or stores Quotex credentials. Default mode is SIMULATION.

Code lives in `app/src/main/kotlin/com/jarvis/assistant/quotex/agent/`.

| Spec section | File |
|---|---|
| 3 Data validation, DATA QUALITY | `DataValidator.kt` |
| 4 Multi-timeframe | `CandleResampler.kt`, `MultiTimeframeConfluenceStrategy` in `MoreStrategies.kt` |
| 7 Price action | `PriceAction.kt` |
| 10 Regime, 23 Sessions | `Regime.kt` |
| 11 Strategy library (10 strategies) | `MoreStrategies.kt` (+ the 4 original ones in `analysis/Strategies.kt`) |
| 12-14 Confluence, entry confirmation, gates | `AgentAnalyzer.kt` |
| 16-17 Expiry, countdown | `SignalLifecycle.kt`, `CandleClock.kt` |
| 18-21 Walk-forward, train/validation/out-of-sample, calibration | `AgentBacktester.kt` |
| 24 News risk (never fabricated) | `NewsRisk.kt` |
| 26 Modes | `AgentRuntime.kt` |
| 27 Journal | `QuotexJournal.kt` (stored in `files/quotex_journal.tsv`) |
| 28-30 Explanations, voice, overlay text | `ExplanationEngine.kt`, `AgentNarrator.kt` |
| 36 Versioning | `Versioning.kt` |

Wired into: `QuotexCoordinator` (runs once per closed candle), `QuotexUiState.agent`, the overlay, the Quotex
dashboard panel "TRADING INTELLIGENCE (FULL PIPELINE)" and the chat/voice controller.

Chat / voice phrases (say "Quotex" for voice): "why no trade", "should I wait", "explain", "show confluence",
"current setup", "journal" / "last 20 setups", "today performance", "why did it fail", "agent backtest".

Two journals exist side by side: the agent's setup journal (plain text file `files/quotex_journal.tsv`, drives "journal",
"last 20 setups", "why did it fail", "today performance") and the Room `quotex_journal` table (every surfaced signal, shown in the
TRADE JOURNAL panel; chat: "signal journal" / "signal history").

Not done on purpose: no economic-calendar feed (pass events
into `NewsRiskFilter` yourself; without them news risk shows UNAVAILABLE), no order execution.

## Update: data input, timeframes, overlay (NOT compiled or test-run)

- OCR confidence: `QuotexReading.confidence` (weaker of live-price label and mean grid label; null if the engine reports 0) ->
  `QuotexCoordinator` drops ticks below 0.35 and passes the per-candle mean to `AgentRuntime.onCandleClosed`, so the 0.6 DATA UNCERTAIN gate now runs.
- Timeframes: UI offers 1M/5M/15M/30M/1H; `AgentAnalyzer` builds the strategy library from `TimeframePlan.forEntry(...)`;
  Strategy 10 uses only the levels that exist and abstains when none do.
- Overlay: shows only the five final states (🟢🟡🔵🔴⚪) from the agent, DETAILS/HISTORY/CHAT tabs, no CALL/PUT percentage.
- `ocr/ChartCandleDetector.kt`: reads real OHLC from chart pixels + `PriceAxisCalibration` from `QuotexReading.gridLabels`.
  Wired in: `QuotexMonitorService.detectChart` runs on the same frame as the OCR reading, `QuotexCoordinator.onChartDetection`
  accepts it only if the forming candle's close matches the OCR live price (0.02%) and confidence >= 0.7, and
  `ChartRefiner` lets a chart candle replace the sampled one ONLY when open/close agree (0.03%) and wicks are sane.
  Otherwise sampled candles are used unchanged. Switch: Quotex setup -> "Read real candle highs/lows from the chart image".
  Colours (Quotex green/red) and thresholds are untested on real screenshots - check "Chart candles:" status line.

## Update 2: duplicates merged, backtest -> live gate, small items (NOT compiled or test-run)

- Removed the dead duplicates `analysis/PriceAction.kt`, `analysis/Regime.kt`, `analysis/NewsRisk.kt`; `agent/` versions are the only ones.
- Edge gate: `QuotexCoordinator` re-runs `AgentBacktester` every 100 closed candles (last 1200 candles, step = 2 x expiry) and gives only
  the OUT-OF-SAMPLE segment to `AgentRuntime.setBacktest`. The gate uses it instead of the live journal (never pooled: same candles).
  Live results below break-even over >= 30 resolved setups veto the backtest (setup downgraded to WATCH). The gate still needs
  `edgeMinSamples` (100) OOS setups and z >= 2.33, so it stays closed on short histories - by design.
- State machine: new WAITING state (weak evidence). Risk engine: max trades/day, max trades/hour, max daily stake exposure
  (settings keys `max_trades_day`, `max_trades_hour`, `max_daily_exposure`, no UI sliders yet).
- Dashboard: new MARKET REGIME panel (regime, session, news, strategies allowed by `RegimeGate`).
- Accessibility service refuses tap/long-press/type/swipe while a window whose package contains "quotex" is in the foreground. The single exception is `tradeTap` (AUTO DEMO TRADE switch): it taps Buy/Sell only when the top of the Quotex screen reads DEMO, refuses on LIVE or an unreadable label, fires at most once per candle in its first 30%, and the switch turns off after 20 taps.
  (A browser tab showing Quotex cannot be detected this way.)
- EMA100/200 were already used by EMA Structure; it now reads `series.ema100/ema200`.

## Update 3: chart detector (not tested on real pixels)
- Real Quotex screenshots showed thin, touching same-colour candles ("Adjacent candles merged"). `ChartCandleDetector` now cuts a same-colour
  run wherever the body height jumps (gluing the wick column back to its body), and ignores anti-aliased edge pixels (brighter colour thresholds).
  Identical neighbouring bodies stay merged and are refused. The "CHART:" status line shows the reason when a read is rejected.
- Detector no longer refuses the whole chart when some candles are merged: it uses only the clean, evenly spaced candles at the right edge
  (>= 5), stopping at the first over-wide run or hole. Older merged candles are ignored, never guessed.

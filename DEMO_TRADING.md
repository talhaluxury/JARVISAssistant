# JARVIS Trading Core: DEMO / PAPER auto-trading

Everything here is a **local simulation**. There is no code path to a broker, no tap on Buy/Sell, no deposit or withdrawal.
The engine reads the prices JARVIS already collects from the Quotex screen (Quotex Analyzer -> Start monitoring) and trades a
simulated balance with them.

## Where it is
* Open it from the Home screen: long-press the core, tap **DEMO** (route `demotrade`).
* Package `com.jarvis.assistant.demotrade` (engine), `ui/screens/demotrade` (dashboard).
* Hook into the existing app: `QuotexCoordinator.demoFeed` (2 lines) and `AppContainer.demoTrading`.

## Pipeline
Market data -> `DataValidator` -> `IndicatorSet` (EMA 9/21/50/200, SMA, RSI, MACD, Bollinger, ATR, ADX, Stochastic, momentum,
ROC, swings, S/R) -> `PatternDetector` -> `RegimeDetector` -> `MultiTimeframe` -> 7 strategies -> `StrategyEnsemble` ->
`SignalScorer` (11 weighted categories, 0-100) -> consensus (`SignalPipeline.finalize`, optional AI) -> `RiskManager` ->
`PaperTradeExecutor` -> settlement on expiry -> `PerformanceAnalyzer` / `TradeJournal` / `AdaptiveFilter`.

| File | Role |
|---|---|
| `DemoModels.kt` | settings, signal, trade, account models |
| `TechnicalAnalysis.kt` | indicators (`TA`, `IndicatorSet`) |
| `PatternsRegime.kt` | candlestick patterns, regime, multi-timeframe |
| `Strategies.kt` | the 7 strategies + ensemble |
| `ScoringAndData.kt` | data validation, weighted scorer |
| `AiLayer.kt` | strict-JSON AI opinion (timeout / bad JSON -> WAIT) |
| `SignalPipeline.kt` | analysis + consensus decision |
| `RiskAndExecution.kt` | risk manager, paper executor |
| `DemoTradingEngine.kt` | account state, active trades, persistence, events |
| `DemoRuntime.kt` | live feed wiring, 1-second clock, notifications |
| `Backtest.kt` | walk-forward backtest + artificial candle simulator |
| `Analytics.kt` | statistics, adaptive filter, trade journal |

## Rules the engine follows
* WAIT is the default. A trade needs: data valid, regime tradeable, technical score and strategy ensemble agreeing, no
  higher-timeframe veto, AI not disagreeing, confidence >= minimum, no cooldown, hourly/daily limits OK, no duplicate or
  rapid flip, no active halt, stake >= minimum.
* "Model confidence: 82/100" is a score, **not** a win probability. The UI also shows the break-even win rate for your payout.
* Stake never increases after a loss. No martingale exists in the code.
* Missing price at expiry -> trade is VOIDED and refunded; a result is never invented.
* Feed silent for 10 s -> `MARKET DATA UNAVAILABLE - WAITING FOR DATA`, no new trades.
* Backtest: at candle *i* the engine only receives candles `0..i`. The AI layer is not replayed.
* Adaptive filter (off by default) can only make the engine stricter, within the limits in Settings.

## Build
Same as before (GitHub Actions or `gradle assembleDebug`). Unit tests: `gradle testDebugUnitTest`
(tests are in `app/src/test/kotlin/com/jarvis/assistant/demotrade`).

## Honest expectations
At 80% payout you need to win about 55.6% of decided trades just to break even (77% payout: 56.5%). Paper results over a few
dozen trades say very little. Judge it over hundreds of trades, and compare the Stats tab win rate with the break-even line.

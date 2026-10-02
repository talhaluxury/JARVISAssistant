# Quotex Trading Intelligence Agent

Analysis, alerts and journaling only. Nothing in this code places, prepares or confirms a trade, and it never
asks for or stores Quotex credentials. Default mode is SIMULATION.

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

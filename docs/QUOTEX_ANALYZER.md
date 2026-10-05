# JARVIS Quotex Trading Intelligence — this round's upgrade

This adds to the existing analysis-only Quotex module (see `WINGO_ANALYZER.md`'s Quotex section for the
original design) rather than replacing it, per the "inspect first, upgrade incrementally, don't rewrite"
brief. It targets a scoped, verifiable slice of a much larger 40-section professional-trading-agent spec.

## What was added

**Indicator engine** (`quotex/analysis/Indicators.kt`, `PriceSeries`): ATR (Wilder), MACD (line/signal/
histogram), raw Bollinger bands (lower/mid/upper, in addition to the existing %B), Stochastic %K/%D, and
ADX — all causal (index i only ever depends on data up to i). `PriceSeries` now also exposes `highs`/`lows`
and an `ema50`, needed for these.

**Market structure** (new file `quotex/analysis/MarketStructure.kt`):
- `swings()` — confirmed swing highs/lows (a local extreme confirmed once enough later candles are known).
- `structureLabel()` — HIGHER_HIGH / HIGHER_LOW / LOWER_HIGH / LOWER_LOW / UNCLEAR from the last two swings.
- `trendLabel()` — STRONG_UP / WEAK_UP / RANGE / WEAK_DOWN / STRONG_DOWN / UNSTABLE, from ADX (strength) and
  EMA9/21/50 ordering (direction).
- `volatilityLabel()` — LOW / NORMAL / HIGH / EXTREME / UNKNOWN, from the current ATR relative to the
  *median* ATR of the recent window (relative, not an arbitrary fixed number).

**Three new ensemble models** (same architecture as the existing six — a `StateModel` bucketing history
and asking what happened next): MACD histogram sign, Stochastic zone, ADX trend-strength/direction. They
vote alongside the existing six; nothing about the ensemble, the WAIT/edge gating, or the backtest engine
changed.

**Prediction now carries `trend` and `volatility`** (`QuotexPrediction.trend`, `.volatility`) as descriptive
context — shown on the Quotex screen and the overlay ("TREND ... VOLATILITY ..."). These are context, not a
vote: they don't change the ensemble's lean or the WAIT/edge gate.

**Tests**: `MarketStructureTest.kt` covers the new indicators (ATR, MACD, Stochastic, ADX, raw Bollinger
bands) and the structure/trend/volatility classifiers, using synthetic trending/flat/volatile candle series
with known expected behaviour.

## This round: Strategy Library + Confluence Engine + Signal State Machine (sections 11-14)

Added on top of last round's indicators/market-structure work, still additive - the existing ensemble,
WAIT/edge gating and backtest are untouched.

**Strategy library** (`quotex/analysis/Strategies.kt`) - four independently-evaluated, testable strategies,
each returning a direction (or WAIT) plus the specific conditions it checked and whether each was met:
- *Trend Continuation* - trend established, price on the trend side of EMA21, MACD agrees, momentum not exhausted.
- *Pullback* - a STRONG trend, price pulled back within one ATR of EMA21, RSI mid-range (not a reversal), latest candle resumed the trend.
- *Breakout + Retest* - price closed beyond a recent swing high/low, retested that level, and held beyond it.
- *Support/Resistance Reversal* - price near a recent swing level, a rejection candle (long wick vs body), RSI at an extreme, volatility not extreme.

**Confluence engine** (`ConfluenceEngine.kt`) - never lets one strategy or one indicator decide anything by
itself. It only considers strategies that currently lean a direction, requires them to agree with each other,
and grades the result: `NO_SETUP` → `WEAK_SETUP` → `WATCH` → `SETUP_DETECTED` → `HIGH_CONFLUENCE_SETUP`,
based on how many strategies agree and how many of their own conditions are met. Extreme volatility always
lowers the grade by at least one step, per section 9, regardless of how many strategies agree.

**Signal state machine** (`SignalStateMachine.kt`) - `SCANNING → WATCHLIST → PRE_CONFIRMATION →
CONFIRMED_SETUP`, never jumping straight from scanning to a confirmed setup (a setup needs to be seen twice
in a row first). A direction flip while something is active immediately `INVALIDATED`s it; a confirmed setup
that sits unresolved too long `EXPIRE`s rather than staying on screen forever; poor data quality forces
`NO_TRADE`.

**Surfaced in**: the Quotex screen's new "CONFLUENCE / SETUP" panel (shows the grade, state, and each
strategy's own conditions), the overlay's new SETUP line, and chat/voice - "confluence" / "show setup" /
"what strategy" now answer with the same detail (`QuotexNarrator.confluence()`).

**Tests**: `ConfluenceTest.kt` covers each strategy's condition logic on synthetic trending/flat/breakout
candle series, the confluence engine's grading (agreement, disagreement, volatility cap) using fake
strategies with controlled scores, and every state-machine transition including invalidation and expiry.

**Known gap in this round**: strategies are currently weighted equally; section 21's calibration (reduce a
strategy's influence automatically once its own walk-forward record is poor) is not wired in yet - that
needs a per-strategy performance tracker similar to WinGo's `ModelTracker`, which is a reasonable next step.

## This round: Strategy Performance Tracking + Calibration + Confluence Backtesting (sections 18-19, 21)

Closes the "known gap" from last round (strategies were equal-weighted with no walk-forward record).

**`StrategyPerformanceTracker`** - one per strategy, same design as WinGo's `ModelTracker`: a rolling
walk-forward hit record whose `weight()` rises above 1.0 when a strategy is genuinely beating a coin flip and
falls toward 0.1 when it is not (section 21: "automatically reduce confidence when calibration deteriorates").

**`ConfluenceEngine` now takes optional per-strategy weights** - missing entries default to 1.0, so every
existing call and test that doesn't pass weights behaves exactly as before (verified: with equal weights the
weighted average exactly equals the old plain average). With real weights, a strategy whose record has gone
bad can no longer out-vote a strategy that is actually working, even if they'd otherwise tie.

**Live calibration is now wired end-to-end**, not just theoretical: `QuotexCoordinator` keeps one tracker per
strategy, records each strategy's own directional call (independent of whether the overall confluence ever
reached SETUP_DETECTED) once its expiry passes, and feeds the resulting weights into the very next confluence
read - the same order live and in the backtest below, so what you see live is what the backtest would have
shown.

**`ConfluenceBacktestEngine`** - a walk-forward test of the strategy library + confluence engine together
(distinct from the existing ensemble backtest): freezes data, generates a confluence read, waits `expiry`
candles, reveals the outcome, updates that round's strategies, moves forward. Reports total/valid setups,
win/loss streaks, hit rate by setup quality, and each strategy's own hit rate and current weight. Available as
*Run confluence backtest* on the Quotex screen, and via chat: "confluence backtest", "strategy backtest",
"strategy performance".

**Tests**: `CalibrationTest.kt` - the tracker's weight curve, that equal weights reproduce the exact old
behaviour, that a downweighted strategy stops being able to tie/outvote a healthy one, and that the backtest
never looks ahead and doesn't overclaim on a random walk.

## This round: Risk Engine (section 25)

**A discipline layer.** The only automatic action is the opt-in AUTO DEMO TRADE switch (demo accounts only), so
the risk engine can only ever pause *JARVIS's own displayed setups* - never your account, and never Quotex's
own risk controls, which remain the real safeguard (use Quotex's daily-loss limit too).

You declare three numbers in the new **RISK** panel: a hypothetical **stake per trade**, a **daily loss
limit**, and how many **losses in a row** should pause setups. The engine then tracks JARVIS's *signalled*
calls (only real, gated signals - not every raw lean) as wins/losses at that fixed stake and your payout.

- **No martingale, structurally.** The stake is read from your setting on every outcome; nothing in the
  code path can raise it after a loss. (There is a test: three losses cost exactly three fixed stakes.)
- **Paused states**: `PAUSED_DAILY_LOSS`, `PAUSED_CONSECUTIVE_LOSSES`, `PAUSED_BY_USER`. While paused the
  screen/overlay show "TRADING PAUSED - <reason>" instead of a setup; the analysis keeps running underneath,
  so it resumes seamlessly.
- **Counters freeze while paused.** Setups you are not being shown are not trades you took, so their results
  are not counted - otherwise hidden wins could quietly drift your P/L and lift a daily-loss pause.
- **What lifts a pause**: a daily-loss pause lifts on the next day (or when *you* deliberately raise the
  limit); *Resume* clears a manual pause or a losing-streak cool-down (an explicit human acknowledgement).
  A losing streak that spans midnight still counts; only the daily counters reset at day change.
- Chat/voice: "risk", "am I paused", "daily loss" -> `QuotexNarrator.risk()`.
- **Tests**: `RiskEngineTest.kt` (fixed stake, both pause triggers, freeze-while-paused, next-day rollover,
  resume semantics, deliberately raising the limit, invalid config).

## This round: Trade Journal (section 27)

Every real signal JARVIS surfaces (not every raw lean - only when `prediction.isSignal` is true) is saved the
moment it is shown, with its full context **frozen at that instant**: asset, timeframe (candle length +
expiry), direction, confidence, model agreement, trend, volatility, the confluence quality and signal state
at the time, the entry price, and the plain-language reason JARVIS gave. It is a separate table
(`quotex_journal`) added via a proper Room migration (version 1→2) - existing candle history is untouched,
nothing is wiped on update.

Once the signal's expiry passes, the same row is updated with the actual direction and whether it was
correct - never a new row, never a silent rewrite of what was originally said.

**Chat/voice**: "today's performance", "show my last 20 setups" (or "last N setups"), "journal", and "why did
this fail" / "why did it fail" - the failure explainer quotes the original `reasonSummary` verbatim rather
than reconstructing a story afterward, and always closes on "this is one resolved case, not a pattern" so a
single loss is never over-read.

**UI**: a new **TRADE JOURNAL** panel on the Quotex screen shows today's resolved win/loss count and the most
recent logged signals with their outcome marker.

**Tests**: `JournalTest.kt` covers the narrator's summary wording (empty, all-pending, mixed win/loss) and the
failure explainer (quotes the frozen reasoning, never says "guaranteed" or implies a loss won't repeat). The
Room entity/DAO/migration itself is not unit-tested here (this project has no instrumented-test setup), so
please check on a device that: (a) a signal appears in the journal the moment it is surfaced, (b) it resolves
correctly after its expiry, and (c) upgrading from a build before this change does not lose candle history.

## What this does NOT include (from the 40-section brief)

This was an intentionally scoped increment, not the full brief. Left out this round, and why:

- **Multi-timeframe engine (section 4)** — needs building and reconciling several simultaneous candle
  resolutions from the same OCR feed; a real architectural addition, not safe to do unverified in one pass.
- **Strategy library + signal state machine + confluence engine (11-14)** — the existing ensemble already
  does evidence-weighted combination with a state machine of sorts (WAIT → candidate → signal, tracked via
  `RoundPhase`-equivalent for Quotex is not yet built the way WinGo's is); a full named-strategy library on
  top would be a large, separate piece of work best done as its own reviewable step.
- **Session analysis, news filter (23-24)** — genuinely new subsystems each; none exist yet for Quotex
  (the risk engine and trade journal are now built, see above). The existing backtest report already shows break-even accuracy and simulated
  P/L, and CSV export/import already gives a data trail, but a dedicated risk engine and journal UI are not
  built. News/economic-calendar data has no reliable local source in this app, so per the brief's own
  instruction ("do not fabricate news data if no reliable source is available") this is skipped rather than
  faked.
- **Automated execution** — not built, and won't be: section 33 of this same brief already states the
  default must be analysis + alerts + user confirmation with no automatic real-money trades, which matches
  the boundary already in place (the same automation-guard test that covers WinGo also scans the Quotex
  folder).

## Account security (section 32)

Already satisfied by design: the module never asks for or stores a Quotex password, 2FA code, OTP, or any
credential, and it never touches Quotex's own login flow — it only reads prices and candles from the screen.


## Trading Intelligence Agent

The full agent pipeline (data validation, multi-timeframe, price action, regime, 10 strategies, walk-forward
backtest, news risk, modes, explanations) lives in `quotex/agent/` - see `QUOTEX_AGENT.md`. It runs alongside the
Room trade journal above.

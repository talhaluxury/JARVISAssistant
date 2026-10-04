package com.jarvis.assistant.demotrade

import com.jarvis.assistant.quotex.domain.Candle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

sealed class DemoEvent {
    data class StrongSignal(val signal: TradeSignal) : DemoEvent()
    data class TradeOpened(val trade: PaperTrade) : DemoEvent()
    data class TradeClosed(val trade: PaperTrade) : DemoEvent()
    data class DailyTarget(val pnl: Double) : DemoEvent()
    data class RiskHalt(val reason: HaltReason) : DemoEvent()
    data class EngineStopped(val reason: String) : DemoEvent()
}

@Serializable
data class PersistedState(
    val version: Int = 1,
    val settings: DemoSettings,
    val account: DemoAccount,
    val active: List<PaperTrade>,
    val closed: List<PaperTrade>,
    val signals: List<SignalRecord>,
    val nextId: Int,
    val lastProcessedCandleMs: Long = 0L
)

interface DemoStateStore {
    fun load(): PersistedState?
    fun save(state: PersistedState)
}

class InMemoryDemoStateStore : DemoStateStore {
    @Volatile var saved: PersistedState? = null
    override fun load(): PersistedState? = saved
    override fun save(state: PersistedState) { saved = state }
}

/** JSON file in app-private storage, written atomically (temp file + rename) so a crash can never leave half a file. */
class FileDemoStateStore(private val file: File) : DemoStateStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun load(): PersistedState? = try {
        if (!file.exists()) null else json.decodeFromString(PersistedState.serializer(), file.readText())
    } catch (e: Exception) {
        null
    }

    override fun save(state: PersistedState) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(PersistedState.serializer(), state))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}

data class DemoUiState(
    val settings: DemoSettings = DemoSettings(),
    val account: DemoAccount = DemoAccount.fresh(10_000.0, 0L),
    val equity: Double = 10_000.0,
    val todayPnl: Double = 0.0,
    val winRateToday: Double? = null,
    val tradesToday: Int = 0,
    val winsToday: Int = 0,
    val lossesToday: Int = 0,
    val drawdownPct: Double = 0.0,
    val consecutiveLosses: Int = 0,
    val phase: EnginePhase = EnginePhase.WAITING_FOR_DATA,
    val phaseNote: String = "",
    val lastSignal: TradeSignal? = null,
    val activeTrades: List<PaperTrade> = emptyList(),
    val currentPrice: Double? = null,
    val history: List<PaperTrade> = emptyList(),
    val stats: TradeStats = TradeStats(),
    val halt: HaltReason = HaltReason.NONE,
    val dataMessage: String? = null,
    val dataLog: List<String> = emptyList(),
    val signals: List<SignalRecord> = emptyList(),
    val adaptive: AdaptiveAdvice = AdaptiveAdvice.NONE,
    val nowMs: Long = 0L
)

/**
 * The stateful half of the trading core: account, active trades, history, risk state, persistence.
 * (The pure half - analysis and decision - lives in [SignalPipeline].)
 *
 * Thread-safety: every public method takes one lock, so a candle callback and a price tick can never open or settle the same
 * trade twice. Listener callbacks (notifications) are invoked after the lock has been released.
 * DEMO / PAPER ONLY: there is no code path to any broker.
 */
class DemoTradingEngine(
    initialSettings: DemoSettings = DemoSettings(),
    private val store: DemoStateStore? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val listener: ((DemoEvent, DemoSettings) -> Unit)? = null,
    /** Permanent home of closed trades (Room in the app). When null the closed trades live in the JSON state only. */
    private val history: DemoTradeHistoryStore? = null
) {
    companion object {
        const val MAX_CLOSED = 500
        /** With a history store the JSON state keeps only this many recent closed trades (a safety net, not the archive). */
        const val JSON_KEEP_CLOSED = 50
        const val MAX_SIGNALS = 150
        const val MAX_LOG = 50
    }

    private val lock = Any()
    private var settings: DemoSettings = initialSettings.coerced()
    private var account: DemoAccount = DemoAccount.fresh(settings.initialBalance, clock())
    private val active = ArrayList<PaperTrade>()
    private val closed = ArrayList<PaperTrade>()
    private val signals = ArrayList<SignalRecord>()
    private val dataLog = ArrayList<String>()
    private var nextId = 1
    private var lastProcessedCandleMs = 0L
    private var lastSignal: TradeSignal? = null
    private var phase = EnginePhase.WAITING_FOR_DATA
    private var phaseNote = "Waiting for market data"
    private var tradeClosedUntilMs = 0L
    private var currentPrice: Double? = null
    private var feedMessage: String? = null
    private var validationMessage: String? = null
    private var advice: AdaptiveAdvice = AdaptiveAdvice.NONE
    private var statsCache: TradeStats = TradeStats()
    private var lastNowMs = clock()

    private val stateFlow = MutableStateFlow(DemoUiState())
    val state: StateFlow<DemoUiState> = stateFlow.asStateFlow()

    init {
        statsCache = PerformanceAnalyzer.analyze(closed, account.initialBalance, settings.payoutPercent)
        stateFlow.value = buildState(lastNowMs)
    }

    // ---------------------------------------------------------------- public API

    fun settingsSnapshot(): DemoSettings = synchronized(lock) { settings }

    fun hasActiveTrade(): Boolean = synchronized(lock) { active.isNotEmpty() }

    /** Pure analysis with the current settings (no state change). Used to decide whether the AI is worth asking. */
    fun preview(candles: List<Candle>, candleMs: Long, nowMs: Long): PipelineResult =
        SignalPipeline.analyze(candles, settingsSnapshot(), nowMs, candleMs)

    fun markPhase(p: EnginePhase, note: String) = locked { _ ->
        phase = p
        phaseNote = note
        publish(clock())
    }

    fun updateSettings(newSettings: DemoSettings) = locked { _ ->
        val s = newSettings.coerced()
        val turnedOn = s.autoDemoTrading && !settings.autoDemoTrading
        settings = s
        if (turnedOn) clearSoftHalts()
        statsCache = PerformanceAnalyzer.analyze(closed, account.initialBalance, settings.payoutPercent)
        advice = AdaptiveFilter.advise(closed, settings)
        persist()
        publish(clock())
    }

    /** Clears the consecutive-loss and session-loss stops (daily-loss and max-drawdown stops stay until their own reset). */
    fun resume() = locked { _ ->
        clearSoftHalts()
        persist()
        publish(clock())
    }

    fun resetAccount() = locked { _ ->
        account = DemoAccount.fresh(settings.initialBalance, clock())
        active.clear()
        closed.clear()
        history?.clear()
        signals.clear()
        dataLog.clear()
        nextId = 1
        lastSignal = null
        lastProcessedCandleMs = 0L
        tradeClosedUntilMs = 0L
        phase = EnginePhase.MARKET_SCANNING
        phaseNote = "Demo account reset"
        statsCache = PerformanceAnalyzer.analyze(closed, account.initialBalance, settings.payoutPercent)
        advice = AdaptiveAdvice.NONE
        persist()
        publish(clock())
    }

    /** Load persisted state (process-death recovery). Active trades whose expiry passed are settled or voided on the next tick. */
    fun restore(): Boolean = locked { _ ->
        val st = store?.load()
        if (st == null) {
            false
        } else {
            settings = st.settings.coerced()
            account = st.account
            active.clear(); active.addAll(st.active)
            closed.clear()
            val h = history
            if (h == null) {
                closed.addAll(st.closed)
            } else {
                val stored = h.loadRecent(MAX_CLOSED)
                if (stored.isNotEmpty()) {
                    closed.addAll(stored)
                } else if (st.closed.isNotEmpty()) {
                    // first start after the upgrade: move the trades from the old JSON file into the database
                    closed.addAll(st.closed)
                    h.upsertAll(st.closed)
                }
            }
            signals.clear(); signals.addAll(st.signals)
            nextId = max(st.nextId, (closed.maxOfOrNull { it.id } ?: 0) + 1)
            nextId = max(nextId, (active.maxOfOrNull { it.id } ?: 0) + 1)
            lastProcessedCandleMs = st.lastProcessedCandleMs
            statsCache = PerformanceAnalyzer.analyze(closed, account.initialBalance, settings.payoutPercent)
            advice = AdaptiveFilter.advise(closed, settings)
            publish(clock())
            true
        }
    }

    /** The data feed (screen reader / network) is not delivering. No new trades; nothing is guessed. */
    fun setFeedUnavailable(reason: String?, nowMs: Long) = locked { _ ->
        val before = feedMessage
        feedMessage = if (reason == null) null else "MARKET DATA UNAVAILABLE - WAITING FOR DATA ($reason)"
        if (reason != null) {
            currentPrice = null
            phase = EnginePhase.WAITING_FOR_DATA
            phaseNote = "Waiting for market data"
            if (before != feedMessage) logData(reason, nowMs)
        } else if (before != null && phase == EnginePhase.WAITING_FOR_DATA) {
            phase = EnginePhase.MARKET_SCANNING
            phaseNote = "Data is back; validating before resuming"
        }
        publish(nowMs)
    }

    /**
     * A new candle has CLOSED. Runs the pipeline and, if auto demo trading is ON and every check passes, opens a paper trade.
     * Idempotent per candle: calling it twice for the same candle can never open two trades.
     */
    fun onCandleClosed(
        candles: List<Candle>,
        candleMs: Long,
        asset: String,
        price: Double?,
        nowMs: Long,
        ai: AiOpinion?
    ): TradeSignal? = locked { ev ->
        lastNowMs = nowMs
        account = account.rolled(nowMs)
        val s = settings
        val lastCandle = candles.lastOrNull()
        if (lastCandle != null && lastCandle.openTimeMs == lastProcessedCandleMs) {
            null
        } else {
            val p = SignalPipeline.analyze(candles, s, nowMs, candleMs)
            if (lastCandle != null) lastProcessedCandleMs = lastCandle.openTimeMs
            if (!p.data.ok) {
                validationMessage = if (p.data.unavailable) "MARKET DATA UNAVAILABLE - WAITING FOR DATA (${p.data.reason})" else p.data.reason
                logData(p.data.reason ?: "invalid data", nowMs)
                phase = if (p.data.unavailable) EnginePhase.WAITING_FOR_DATA else EnginePhase.MARKET_SCANNING
                phaseNote = p.data.reason ?: ""
                lastSignal = SignalPipeline.finalize(p, ai, s, asset, price ?: p.lastClose, nowMs)
                publish(nowMs)
                null
            } else {
                validationMessage = null
                var sig = SignalPipeline.finalize(p, ai, s, asset, price ?: p.lastClose, nowMs)
                advice = AdaptiveFilter.advise(closed, s)
                val halt = RiskManager.checkHalts(account, s)
                if (halt != HaltReason.NONE) applyHalt(halt, ev)
                val attempt = attemptOpen(sig, nowMs, ev)
                sig = attempt.signal
                val traded = attempt.traded
                val note = attempt.note
                lastSignal = sig
                signals.add(SignalRecord(nowMs, sig.direction, sig.confidence, sig.regime, traded, note))
                while (signals.size > MAX_SIGNALS) signals.removeAt(0)
                phase = if (sig.direction != Dir.WAIT && !traded) EnginePhase.SIGNAL_READY else EnginePhase.MARKET_SCANNING
                phaseNote = note
                persist()
                publish(nowMs)
                sig
            }
        }
    }

    /**
     * Price tick (about once a second). Tracks the best/worst move of every open trade and settles trades at expiry with the
     * first valid price at or after the expiry time. If no valid price arrives within the grace period the trade is VOIDED and
     * refunded - a result is never invented.
     */
    fun onTick(nowMs: Long, price: Double?) = locked { ev ->
        lastNowMs = nowMs
        account = account.rolled(nowMs)
        currentPrice = price
        val grace = settings.settlementGraceSeconds * 1000L
        var changed = false
        for (trade in active.toList()) {
            var t = trade
            if (price != null && nowMs <= t.expiresAtMs) {
                val favorable = if (t.direction == Dir.CALL) price - t.entryPrice else t.entryPrice - price
                val updated = t.copy(
                    maxFavorable = max(t.maxFavorable, favorable),
                    maxAdverse = max(t.maxAdverse, -favorable)
                )
                if (updated != t) {
                    val idx = active.indexOfFirst { it.id == t.id }
                    if (idx >= 0) active[idx] = updated
                    t = updated
                }
            }
            if (nowMs >= t.expiresAtMs) {
                val late = nowMs - t.expiresAtMs > grace
                if (late) {
                    settleTrade(t, null, TradeResult.VOID, nowMs, ev)
                    changed = true
                } else if (price != null) {
                    settleTrade(t, price, PaperTradeExecutor.decide(t.direction, t.entryPrice, price), nowMs, ev)
                    changed = true
                }
            }
        }
        if (changed) persist()
        publish(nowMs)
    }

    // ---------------------------------------------------------------- internals

    private fun <T> locked(block: (MutableList<DemoEvent>) -> T): T {
        val events = ArrayList<DemoEvent>()
        val out = synchronized(lock) { Pair(block(events), settings) }
        val l = listener
        if (l != null) {
            for (e in events) {
                try { l(e, out.second) } catch (ignored: Exception) { /* a notification problem must never break trading logic */ }
            }
        }
        return out.first
    }

    private class OpenAttempt(val signal: TradeSignal, val traded: Boolean, val note: String)

    /** Strong-signal alert + every gate (auto switch, duplicate/flip guard, risk manager) + opening the paper trade. Lock must be held. */
    private fun attemptOpen(signal: TradeSignal, nowMs: Long, ev: MutableList<DemoEvent>): OpenAttempt {
        val s = settings
        var sig = signal
        if (sig.direction == Dir.WAIT) return OpenAttempt(sig, false, sig.blockReason ?: "WAIT")
        if (sig.strength >= SignalStrength.STRONG) ev.add(DemoEvent.StrongSignal(sig))
        if (!s.autoDemoTrading) return OpenAttempt(sig, false, "Auto Demo Trading is OFF - signal shown only")
        val dup = duplicateReason(sig, nowMs)
        if (dup != null) return OpenAttempt(sig, false, dup)
        val d = RiskManager.evaluate(sig, account, s, nowMs, active.size, advice)
        if (!d.approved) return OpenAttempt(sig, false, d.reason)
        sig = sig.copy(stake = d.stake)
        val opened = PaperTradeExecutor.open(account, nextId++, sig, d.stake, s, nowMs)
        account = opened.first
        active.add(opened.second)
        ev.add(DemoEvent.TradeOpened(opened.second))
        return OpenAttempt(sig, true, TradeJournal.entryNote(opened.second))
    }

    /** Test / simulation entry point: push an already-built signal through the same gates a live signal passes. */
    internal fun submitSignal(signal: TradeSignal, nowMs: Long): Boolean = locked { ev ->
        lastNowMs = nowMs
        account = account.rolled(nowMs)
        val halt = RiskManager.checkHalts(account, settings)
        if (halt != HaltReason.NONE) applyHalt(halt, ev)
        val attempt = attemptOpen(signal, nowMs, ev)
        lastSignal = attempt.signal
        signals.add(SignalRecord(nowMs, attempt.signal.direction, attempt.signal.confidence, attempt.signal.regime, attempt.traded, attempt.note))
        persist()
        publish(nowMs)
        attempt.traded
    }

    internal fun accountSnapshot(): DemoAccount = synchronized(lock) { account }

    internal fun activeSnapshot(): List<PaperTrade> = synchronized(lock) { active.toList() }

    internal fun closedSnapshot(): List<PaperTrade> = synchronized(lock) { closed.toList() }

    private fun clearSoftHalts() {
        val h = account.halt
        account = account.copy(
            halt = if (h == HaltReason.SESSION_LOSS || h == HaltReason.CONSECUTIVE_LOSSES) HaltReason.NONE else h,
            consecutiveLosses = if (h == HaltReason.CONSECUTIVE_LOSSES) 0 else account.consecutiveLosses,
            sessionStartBalance = account.equity
        )
    }

    private fun applyHalt(h: HaltReason, ev: MutableList<DemoEvent>) {
        if (account.halt != HaltReason.NONE) return
        account = account.copy(halt = h)
        if (settings.autoDemoTrading) {
            ev.add(DemoEvent.RiskHalt(h))
            ev.add(DemoEvent.EngineStopped("AUTO DEMO TRADING STOPPED - ${h.message}"))
        }
    }

    private fun duplicateReason(sig: TradeSignal, nowMs: Long): String? {
        val last = (active + closed.takeLast(1)).maxByOrNull { it.openedAtMs } ?: return null
        val flipLockMs = max(120_000L, settings.expirySeconds * 3_000L)
        if (last.direction == sig.direction.opposite() && nowMs - last.openedAtMs < flipLockMs) {
            return "Anti-flip: an opposite-direction demo trade was opened recently"
        }
        if (last.direction == sig.direction && last.entryPrice == sig.entryPrice && nowMs - last.openedAtMs < settings.expirySeconds * 2_000L) {
            return "Duplicate: identical to the previous signal"
        }
        return null
    }

    private fun settleTrade(t: PaperTrade, exit: Double?, result: TradeResult, nowMs: Long, ev: MutableList<DemoEvent>) {
        val r = PaperTradeExecutor.settle(account, t, exit, result, nowMs)
        account = r.first
        val done = r.second.copy(explanation = TradeJournal.explain(r.second))
        active.removeAll { it.id == t.id }
        closed.add(done)
        history?.upsert(done)
        while (closed.size > MAX_CLOSED) closed.removeAt(0)
        statsCache = PerformanceAnalyzer.analyze(closed, account.initialBalance, settings.payoutPercent)
        advice = AdaptiveFilter.advise(closed, settings)
        ev.add(DemoEvent.TradeClosed(done))
        val h = RiskManager.checkHalts(account, settings)
        if (h != HaltReason.NONE) applyHalt(h, ev)
        val target = settings.dailyTargetPercent
        if (target > 0.0 && !account.dailyTargetNotified && account.dayPnl >= account.dayStartBalance * target / 100.0) {
            account = account.copy(dailyTargetNotified = true)
            ev.add(DemoEvent.DailyTarget(account.dayPnl))
        }
        tradeClosedUntilMs = nowMs + 10_000L
    }

    private fun logData(message: String, nowMs: Long) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(nowMs))
        val line = "$stamp $message"
        if (dataLog.lastOrNull()?.substring(9) == message) return
        dataLog.add(line)
        while (dataLog.size > MAX_LOG) dataLog.removeAt(0)
    }

    private fun persist() {
        val st = store ?: return
        try {
            st.save(
                PersistedState(
                    settings = settings,
                    account = account,
                    active = active.toList(),
                    closed = if (history != null) closed.takeLast(JSON_KEEP_CLOSED) else closed.toList(),
                    signals = signals.toList(),
                    nextId = nextId,
                    lastProcessedCandleMs = lastProcessedCandleMs
                )
            )
        } catch (ignored: Exception) {
            // Persistence is best effort; trading logic must not depend on it.
        }
    }

    private fun publish(nowMs: Long) {
        stateFlow.value = buildState(nowMs)
    }

    private fun buildState(nowMs: Long): DemoUiState {
        val acct = account
        val halted = acct.halt != HaltReason.NONE && settings.autoDemoTrading
        val effective = when {
            halted -> EnginePhase.RISK_LIMIT
            active.isNotEmpty() -> EnginePhase.DEMO_TRADE_ACTIVE
            nowMs < tradeClosedUntilMs -> EnginePhase.TRADE_CLOSED
            else -> phase
        }
        val decided = acct.dayWins + acct.dayLosses
        return DemoUiState(
            settings = settings,
            account = acct,
            equity = acct.equity,
            todayPnl = acct.dayPnl,
            winRateToday = if (decided == 0) null else acct.dayWins.toDouble() / decided,
            tradesToday = acct.dayTrades,
            winsToday = acct.dayWins,
            lossesToday = acct.dayLosses,
            drawdownPct = acct.drawdownPct,
            consecutiveLosses = acct.consecutiveLosses,
            phase = effective,
            phaseNote = if (halted) "AUTO DEMO TRADING STOPPED - ${acct.halt.message}" else phaseNote,
            lastSignal = lastSignal,
            activeTrades = active.toList(),
            currentPrice = currentPrice,
            history = closed.asReversed().toList(),
            stats = statsCache,
            halt = acct.halt,
            dataMessage = feedMessage ?: validationMessage,
            dataLog = dataLog.toList(),
            signals = signals.asReversed().toList(),
            adaptive = advice,
            nowMs = nowMs
        )
    }
}

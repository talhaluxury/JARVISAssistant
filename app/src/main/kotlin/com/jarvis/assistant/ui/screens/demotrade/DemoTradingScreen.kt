package com.jarvis.assistant.ui.screens.demotrade

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jarvis.assistant.demotrade.DemoSettings
import com.jarvis.assistant.demotrade.DemoUiState
import com.jarvis.assistant.demotrade.Dir
import com.jarvis.assistant.demotrade.EnginePhase
import com.jarvis.assistant.demotrade.PaperTrade
import com.jarvis.assistant.demotrade.Scenario
import com.jarvis.assistant.demotrade.StakeMode
import com.jarvis.assistant.demotrade.StrategyLibrary
import com.jarvis.assistant.demotrade.TieMode
import com.jarvis.assistant.demotrade.TradeResult
import com.jarvis.assistant.demotrade.dayKeyOf
import com.jarvis.assistant.ui.theme.JarvisBackground
import com.jarvis.assistant.ui.theme.JarvisCyan
import com.jarvis.assistant.ui.theme.JarvisError
import com.jarvis.assistant.ui.theme.JarvisSuccess
import com.jarvis.assistant.ui.theme.JarvisTextPrimary
import com.jarvis.assistant.ui.theme.JarvisTextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class DemoTab(val label: String) { LIVE("Live"), HISTORY("History"), STATS("Stats"), LAB("Backtest"), SETTINGS("Settings") }

private enum class HistoryFilter(val label: String) {
    ALL("All"), WINS("Wins"), LOSSES("Losses"), CALL("Call"), PUT("Put"), TODAY("Today"), WEEK("Week")
}

@Composable
fun DemoTradingScreen(onBack: () -> Unit, vm: DemoTradingViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    var tab by rememberSaveable { mutableStateOf(DemoTab.LIVE.name) }
    val current = DemoTab.valueOf(tab)

    Column(Modifier.fillMaxSize().background(JarvisBackground)) {
        Header(state, onBack)
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DemoTab.values().forEach { t -> Chip(t.label, t == current) { tab = t.name } }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            when (current) {
                DemoTab.LIVE -> LiveTab(state, vm)
                DemoTab.HISTORY -> HistoryTab(state)
                DemoTab.STATS -> StatsTab(state)
                DemoTab.LAB -> LabTab(vm)
                DemoTab.SETTINGS -> SettingsTab(state, vm)
            }
            Body(
                "DEMO / PAPER TRADING. Simulated balance, no broker connection, no real orders. Model confidence is a score, not a probability of winning.",
                JarvisTextSecondary, 10
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Header(state: DemoUiState, onBack: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val alpha by pulse.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha")
    Column(Modifier.fillMaxWidth().background(Color(0xEE02070D)).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("J.A.R.V.I.S.", color = JarvisCyan, fontSize = 20.sp, fontFamily = Mono, fontWeight = FontWeight.Bold)
                Text("TRADING CORE", color = JarvisTextPrimary, fontSize = 12.sp, fontFamily = Mono)
            }
            Chip("Back", false, onBack)
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val online = state.phase != EnginePhase.WAITING_FOR_DATA && state.halt == com.jarvis.assistant.demotrade.HaltReason.NONE
            Box(Modifier.size(9.dp).alpha(if (online) alpha else 1f).background(if (online) JarvisSuccess else Amber, CircleShape))
            Spacer(Modifier.width(6.dp))
            Body(if (online) "DEMO ENGINE ONLINE" else "DEMO ENGINE ${state.phase.label}", if (online) JarvisSuccess else Amber, 11)
            Spacer(Modifier.width(10.dp))
            Surface(color = Amber.copy(alpha = 0.2f), shape = RoundedCornerShape(3.dp)) {
                Text("DEMO / PAPER TRADING", color = Amber, fontSize = 9.sp, fontFamily = Mono, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ LIVE

@Composable
private fun LiveTab(state: DemoUiState, vm: DemoTradingViewModel) {
    val s = state.settings
    val a = state.account

    if (state.halt != com.jarvis.assistant.demotrade.HaltReason.NONE) {
        Panel("Risk limit") {
            Body(if (s.autoDemoTrading) "AUTO DEMO TRADING STOPPED" else "Risk limit active", JarvisError, 13)
            Body(state.halt.message, JarvisError)
            if (state.halt == com.jarvis.assistant.demotrade.HaltReason.CONSECUTIVE_LOSSES || state.halt == com.jarvis.assistant.demotrade.HaltReason.SESSION_LOSS) {
                Chip("Resume demo trading", false) { vm.resume() }
            } else {
                Body("Daily limits clear automatically at the next local day; reset the demo account to clear a drawdown stop.", JarvisTextSecondary, 10)
            }
        }
    }

    state.dataMessage?.let {
        Panel("Market data") {
            Body(it, Amber, 12)
            state.dataLog.takeLast(3).forEach { line -> Body(line, JarvisTextSecondary, 10) }
        }
    }

    Panel("Demo account") {
        StatRow {
            Stat("Demo balance", money(a.balance), JarvisCyan)
            Stat("Today P/L", signedMoney(state.todayPnl), pnlColor(state.todayPnl))
            Stat("Win rate", pct(state.winRateToday))
        }
        StatRow {
            Stat("Trades", state.tradesToday.toString())
            Stat("Wins", state.winsToday.toString(), JarvisSuccess)
            Stat("Losses", state.lossesToday.toString(), JarvisError)
        }
        StatRow {
            Stat("Drawdown", "${f1(state.drawdownPct)}%", if (state.drawdownPct > 10.0) JarvisError else JarvisTextPrimary)
            Stat("Loss streak", "${state.consecutiveLosses}/${s.maxConsecutiveLosses}")
            Stat("Equity", money(state.equity))
        }
        SwitchRow("Auto Demo Trading", s.autoDemoTrading, { on -> vm.update { it.copy(autoDemoTrading = on) } },
            "Opens simulated trades by itself when every check passes")
    }

    Panel("Engine status") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val pulse = rememberInfiniteTransition(label = "scan")
            val alpha by pulse.animateFloat(0.3f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "scanAlpha")
            Box(Modifier.size(12.dp).alpha(alpha).background(JarvisCyan, CircleShape))
            Spacer(Modifier.width(8.dp))
            Body(state.phase.label, JarvisCyan, 14)
        }
        if (state.phaseNote.isNotBlank()) Body(state.phaseNote, JarvisTextSecondary, 11)
        state.currentPrice?.let { Body("Live price: ${price(it)}", JarvisTextPrimary, 12) }
    }

    SignalCard(state)

    state.activeTrades.forEach { ActiveTradeCard(it, state) }

    if (state.signals.isNotEmpty()) {
        Panel("Recent signals") {
            state.signals.take(8).forEach { r ->
                val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(r.timeMs))
                Body("$t  ${r.direction.name}  ${r.confidence}  ${if (r.traded) "TRADED" else "-"}", if (r.traded) JarvisCyan else dirColor(r.direction), 11)
                Body(r.note.take(110), JarvisTextSecondary, 10)
            }
        }
    }
}

@Composable
private fun SignalCard(state: DemoUiState) {
    val sig = state.lastSignal
    Panel("Signal") {
        if (sig == null) {
            Body("No signal yet. The engine evaluates every closed candle.", JarvisTextSecondary)
            return@Panel
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(sig.direction.name, color = dirColor(sig.direction), fontSize = 34.sp, fontFamily = Mono, fontWeight = FontWeight.Bold)
            Column(horizontalAlignment = Alignment.End) {
                Body(sig.modelConfidenceLabel, JarvisCyan, 13)
                Body("Signal strength: ${sig.strength.label}", JarvisTextPrimary, 11)
            }
        }
        StatRow {
            Stat("Market", sig.regime.name.replace('_', ' '))
            Stat("Entry", if (sig.entryPrice > 0.0) price(sig.entryPrice) else "--")
            Stat("Expiry", "${sig.expirySeconds}s")
        }
        if (sig.direction == Dir.WAIT) {
            sig.blockReason?.let { Body("WAIT: $it", Amber, 11) }
        } else {
            Body("Risk: ${sig.riskLevel.name}   Confirmations: ${sig.votes.count { it.direction == sig.direction }}/${sig.votes.size}", JarvisTextSecondary, 11)
            sig.reasons.forEach { Body("✓ $it", JarvisSuccess, 11) }
        }
        if (sig.votes.isNotEmpty()) {
            Body("Strategy votes", JarvisTextSecondary, 10)
            sig.votes.forEach { v -> Body("${v.strategy}: ${v.direction.name} ${v.score}", dirColor(v.direction), 10) }
        }
        sig.aiDirection?.let { Body("AI layer: ${it.name}${sig.aiConfidence?.let { c -> " $c" } ?: " (no usable answer, treated as WAIT)"}", JarvisTextSecondary, 10) }
        if (sig.mtfNote.isNotBlank()) Body(sig.mtfNote, JarvisTextSecondary, 10)
        Body("Break-even win rate at ${f1(sig.payoutRatio * 100)}% payout: ${f1(sig.breakEvenWinRate * 100)}%", JarvisTextSecondary, 10)
    }
}

@Composable
private fun ActiveTradeCard(t: PaperTrade, state: DemoUiState) {
    val remaining = ((t.expiresAtMs - state.nowMs) / 1000L).coerceAtLeast(0L)
    val cur = state.currentPrice
    val moving = if (cur == null) null else if (t.direction == Dir.CALL) cur - t.entryPrice else t.entryPrice - cur
    Panel("Active demo trade #${t.id}") {
        StatRow {
            Stat("Direction", t.direction.name, dirColor(t.direction))
            Stat("Stake", money(t.stake))
            Stat("Remaining", String.format(Locale.US, "%02d:%02d", remaining / 60, remaining % 60), JarvisCyan)
        }
        StatRow {
            Stat("Entry", price(t.entryPrice))
            Stat("Current", cur?.let { price(it) } ?: "--")
            Stat("Status", if (remaining == 0L) "SETTLING" else if (moving == null) "NO PRICE" else if (moving > 0.0) "IN PROFIT" else if (moving < 0.0) "LOSING" else "LEVEL",
                if (moving == null) Amber else pnlColor(moving))
        }
        Body("Payout ${f1(t.payoutPercent)}% | Model confidence ${t.confidence}/100 | ${t.strategies.joinToString().take(60)}", JarvisTextSecondary, 10)
    }
}

// ------------------------------------------------------------------------------------------------ HISTORY

@Composable
private fun HistoryTab(state: DemoUiState) {
    var filter by rememberSaveable { mutableStateOf(HistoryFilter.ALL.name) }
    val f = HistoryFilter.valueOf(filter)
    val now = state.nowMs
    val shown = state.history.filter { t ->
        when (f) {
            HistoryFilter.ALL -> true
            HistoryFilter.WINS -> t.result == TradeResult.WIN
            HistoryFilter.LOSSES -> t.result == TradeResult.LOSS
            HistoryFilter.CALL -> t.direction == Dir.CALL
            HistoryFilter.PUT -> t.direction == Dir.PUT
            HistoryFilter.TODAY -> dayKeyOf(t.openedAtMs) == dayKeyOf(now)
            HistoryFilter.WEEK -> now - t.openedAtMs < 7L * 86_400_000L
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        HistoryFilter.values().take(4).forEach { x -> Chip(x.label, x == f) { filter = x.name } }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        HistoryFilter.values().drop(4).forEach { x -> Chip(x.label, x == f) { filter = x.name } }
    }
    if (shown.isEmpty()) {
        Panel("History") { Body("No demo trades match this filter.", JarvisTextSecondary) }
    }
    shown.take(100).forEach { t ->
        Panel("Trade #${t.id}  ${SimpleDateFormat("dd MMM HH:mm:ss", Locale.US).format(Date(t.openedAtMs))}") {
            StatRow {
                Stat("Dir", t.direction.name, dirColor(t.direction))
                Stat("Result", t.result?.name ?: "OPEN", resultColor(t.result))
                Stat("P/L", signedMoney(t.pnl ?: 0.0), pnlColor(t.pnl ?: 0.0))
            }
            Body("Entry ${price(t.entryPrice)}  Exit ${t.exitPrice?.let { price(it) } ?: "--"}  Expiry ${t.expirySeconds}s  Stake ${money(t.stake)}", JarvisTextPrimary, 11)
            Body("Confidence ${t.confidence}/100  Regime ${t.regime.name}", JarvisTextSecondary, 10)
            Body("Strategy: ${t.strategies.joinToString().ifEmpty { "-" }}", JarvisTextSecondary, 10)
            if (t.explanation.isNotBlank()) Body(t.explanation, JarvisTextSecondary, 10)
        }
    }
}

// ------------------------------------------------------------------------------------------------ STATS

@Composable
private fun StatsTab(state: DemoUiState) {
    val st = state.stats
    Panel("Performance") {
        StatRow {
            Stat("Win rate", pct(st.winRate), if ((st.winRate ?: 0.0) >= st.breakEvenWinRate) JarvisSuccess else JarvisError)
            Stat("Profit factor", st.profitFactor?.let { f2(it) } ?: "--")
            Stat("Expectancy", signedMoney(st.expectancy), pnlColor(st.expectancy))
        }
        StatRow {
            Stat("Total P/L", signedMoney(st.totalPnl), pnlColor(st.totalPnl))
            Stat("Avg P/L", signedMoney(st.avgPnl), pnlColor(st.avgPnl))
            Stat("Max DD", "${money(st.maxDrawdown)} (${f1(st.maxDrawdownPct)}%)")
        }
        StatRow {
            Stat("Win streak", st.longestWinStreak.toString(), JarvisSuccess)
            Stat("Loss streak", st.longestLossStreak.toString(), JarvisError)
            Stat("Trades", "${st.total} (${st.wins}W/${st.losses}L/${st.ties}T)")
        }
        Body("Break-even win rate at the configured payout: ${f1(st.breakEvenWinRate * 100)}%. Below it, the demo account loses money on average.", JarvisTextSecondary, 10)
        if (st.total < 30) Body("Fewer than 30 closed trades: these numbers are statistically weak.", Amber, 10)
    }
    Panel("Best / worst (min 5 trades each)") {
        Body("Best strategy: ${st.bestStrategy ?: "--"}", JarvisSuccess, 11)
        Body("Worst strategy: ${st.worstStrategy ?: "--"}", JarvisError, 11)
        Body("Best regime: ${st.bestRegime ?: "--"}", JarvisSuccess, 11)
        Body("Worst regime: ${st.worstRegime ?: "--"}", JarvisError, 11)
    }
    Panel("Balance curve") { LineChart(st.balanceCurve, JarvisCyan) }
    Panel("P/L curve") { LineChart(st.pnlCurve, JarvisSuccess, zeroLine = true) }
    Panel("Wins vs losses") {
        val total = (st.wins + st.losses).coerceAtLeast(1)
        Row(Modifier.fillMaxWidth().height(14.dp)) {
            if (st.wins > 0) Box(Modifier.weight(st.wins.toFloat()).height(14.dp).background(JarvisSuccess))
            if (st.losses > 0) Box(Modifier.weight(st.losses.toFloat()).height(14.dp).background(JarvisError))
            if (st.wins + st.losses == 0) Box(Modifier.weight(1f).height(14.dp).background(Color(0x33FFFFFF)))
        }
        Body("${st.wins} wins / ${st.losses} losses (${f1(st.wins * 100.0 / total)}% of decided)", JarvisTextSecondary, 10)
    }
    Panel("By confidence") { BucketBars(st.byConfidence, st.breakEvenWinRate) }
    Panel("By strategy") { BucketBars(st.byStrategy, st.breakEvenWinRate) }
    Panel("By market regime") { BucketBars(st.byRegime, st.breakEvenWinRate) }
    Panel("By hour") { BucketBars(st.byHour, st.breakEvenWinRate) }
    if (state.adaptive.notes.isNotEmpty() || state.settings.adaptiveEnabled) {
        Panel("Adaptive filter (limits set in Settings)") {
            Body("Extra confidence required: +${state.adaptive.minConfidenceBump}", JarvisTextPrimary, 11)
            state.adaptive.notes.forEach { Body(it, JarvisTextSecondary, 10) }
        }
    }
}

// ------------------------------------------------------------------------------------------------ BACKTEST / SIMULATION

@Composable
private fun LabTab(vm: DemoTradingViewModel) {
    val bt by vm.backtest.collectAsState()
    Panel("Backtest on stored chart candles") {
        Body("Replays this exact engine over the candles JARVIS stored from your screen. At each candle it only sees the past (no look-ahead). The AI layer is not replayed.", JarvisTextSecondary, 10)
        Chip("Run backtest", false) { vm.runBacktestOnStoredCandles() }
    }
    Panel("Simulation mode (artificial candles)") {
        Body("Feed generated candle sequences into the engine to see how it behaves. Offline; never touches your demo account.", JarvisTextSecondary, 10)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Scenario.values().take(3).forEach { sc -> Chip(sc.label, false) { vm.runSimulation(sc) } }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Scenario.values().drop(3).forEach { sc -> Chip(sc.label, false) { vm.runSimulation(sc) } }
        }
    }
    if (bt.running) Panel("Running") { Body("Calculating ${bt.title}...", JarvisCyan) }
    bt.message?.let { Panel("Result") { Body(it, Amber) } }
    bt.report?.let { r ->
        val st = r.stats
        Panel(bt.title) {
            StatRow {
                Stat("Trades", st.total.toString())
                Stat("Wins", st.wins.toString(), JarvisSuccess)
                Stat("Losses", st.losses.toString(), JarvisError)
            }
            StatRow {
                Stat("Win rate", pct(st.winRate), if ((st.winRate ?: 0.0) >= st.breakEvenWinRate) JarvisSuccess else JarvisError)
                Stat("Total P/L", signedMoney(st.totalPnl), pnlColor(st.totalPnl))
                Stat("Profit factor", st.profitFactor?.let { f2(it) } ?: "--")
            }
            StatRow {
                Stat("Avg win", signedMoney(st.avgWin), JarvisSuccess)
                Stat("Avg loss", signedMoney(st.avgLoss), JarvisError)
                Stat("Expectancy", signedMoney(st.expectancy), pnlColor(st.expectancy))
            }
            StatRow {
                Stat("Max DD", "${money(st.maxDrawdown)} (${f1(st.maxDrawdownPct)}%)")
                Stat("Win streak", st.longestWinStreak.toString())
                Stat("Loss streak", st.longestLossStreak.toString())
            }
            Body("Candles tested ${r.candlesTested}, evaluated ${r.signalsEvaluated}, WAIT ${r.waits}. Break-even win rate ${f1(st.breakEvenWinRate * 100)}%.", JarvisTextSecondary, 10)
            r.halts.forEach { Body("Stopped: $it", JarvisError, 10) }
            Body(r.note, JarvisTextSecondary, 10)
        }
        Panel("Balance curve") { LineChart(st.balanceCurve, JarvisCyan) }
        Panel("Trades by strategy") { BucketBars(st.byStrategy, st.breakEvenWinRate) }
        Panel("Trades by market regime") { BucketBars(st.byRegime, st.breakEvenWinRate) }
        if (r.blocked.isNotEmpty()) {
            Panel("Why it waited") { r.blocked.entries.sortedByDescending { it.value }.take(8).forEach { Body("${it.key}: ${it.value}", JarvisTextSecondary, 10) } }
        }
    }
}

// ------------------------------------------------------------------------------------------------ SETTINGS

@Composable
internal fun SettingsTab(state: DemoUiState, vm: DemoTradingViewModel) {
    val s = state.settings
    var confirmReset by remember { mutableStateOf(false) }
    fun upd(change: (DemoSettings) -> DemoSettings) = vm.update(change)

    Panel("Account") {
        Stepper("Demo balance (applies on reset)", money(s.initialBalance), { upd { it.copy(initialBalance = (it.initialBalance - 1000.0)) } }, { upd { it.copy(initialBalance = (it.initialBalance + 1000.0)) } })
        SwitchRow("Auto Demo Trading", s.autoDemoTrading, { on -> upd { it.copy(autoDemoTrading = on) } })
        Chip("RESET DEMO ACCOUNT", false) { confirmReset = true }
    }
    Panel("Signal thresholds") {
        Stepper("Minimum confidence to trade", "${s.minConfidence}", { upd { it.copy(minConfidence = it.minConfidence - 1) } }, { upd { it.copy(minConfidence = it.minConfidence + 1) } },
            "Model confidence, not a win probability")
        Stepper("Weak starts at", "${s.weakThreshold}", { upd { it.copy(weakThreshold = it.weakThreshold - 1) } }, { upd { it.copy(weakThreshold = it.weakThreshold + 1) } })
        Stepper("Valid signal from", "${s.validThreshold}", { upd { it.copy(validThreshold = it.validThreshold - 1) } }, { upd { it.copy(validThreshold = it.validThreshold + 1) } })
        Stepper("Strong from", "${s.strongThreshold}", { upd { it.copy(strongThreshold = it.strongThreshold - 1) } }, { upd { it.copy(strongThreshold = it.strongThreshold + 1) } })
        Stepper("Very strong from", "${s.veryStrongThreshold}", { upd { it.copy(veryStrongThreshold = it.veryStrongThreshold - 1) } }, { upd { it.copy(veryStrongThreshold = it.veryStrongThreshold + 1) } })
        Stepper("Min strategies agreeing", "${s.minConfirmations}", { upd { it.copy(minConfirmations = it.minConfirmations - 1) } }, { upd { it.copy(minConfirmations = it.minConfirmations + 1) } })
    }
    Panel("Stake") {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            StakeMode.values().forEach { m -> Chip(m.name.replace('_', ' '), s.stakeMode == m) { upd { it.copy(stakeMode = m) } } }
        }
        Stepper("Fixed stake", money(s.fixedStake), { upd { it.copy(fixedStake = it.fixedStake - 1.0) } }, { upd { it.copy(fixedStake = it.fixedStake + 1.0) } })
        Stepper("Percentage stake", "${f1(s.stakePercent)}%", { upd { it.copy(stakePercent = it.stakePercent - 0.1) } }, { upd { it.copy(stakePercent = it.stakePercent + 0.1) } })
        Stepper("Risk %", "${f1(s.riskPercent)}%", { upd { it.copy(riskPercent = it.riskPercent - 0.1) } }, { upd { it.copy(riskPercent = it.riskPercent + 0.1) } })
        Stepper("Maximum stake", money(s.maxStake), { upd { it.copy(maxStake = it.maxStake - 5.0) } }, { upd { it.copy(maxStake = it.maxStake + 5.0) } })
        Stepper("Max risk per trade", "${f1(s.maxRiskPerTradePercent)}%", { upd { it.copy(maxRiskPerTradePercent = it.maxRiskPerTradePercent - 0.5) } }, { upd { it.copy(maxRiskPerTradePercent = it.maxRiskPerTradePercent + 0.5) } })
        Body("The stake never increases after a loss (no martingale).", JarvisTextSecondary, 10)
    }
    Panel("Trade rules") {
        Stepper("Expiry (seconds)", "${s.expirySeconds}", { upd { it.copy(expirySeconds = (it.expirySeconds - 15).coerceAtLeast(5)) } }, { upd { it.copy(expirySeconds = it.expirySeconds + 15) } })
        Stepper("Payout %", f1(s.payoutPercent), { upd { it.copy(payoutPercent = it.payoutPercent - 1.0) } }, { upd { it.copy(payoutPercent = it.payoutPercent + 1.0) } },
            "Break-even win rate: ${f1(s.breakEvenWinRate * 100)}%")
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Body("Tie:", JarvisTextSecondary, 11)
            TieMode.values().forEach { m -> Chip(m.name, s.tieMode == m) { upd { it.copy(tieMode = m) } } }
        }
        Stepper("Cooldown after trade (s)", "${s.cooldownSeconds}", { upd { it.copy(cooldownSeconds = it.cooldownSeconds - 30) } }, { upd { it.copy(cooldownSeconds = it.cooldownSeconds + 30) } })
        Stepper("Cooldown after loss (s)", "${s.cooldownAfterLossSeconds}", { upd { it.copy(cooldownAfterLossSeconds = it.cooldownAfterLossSeconds - 60) } }, { upd { it.copy(cooldownAfterLossSeconds = it.cooldownAfterLossSeconds + 60) } })
    }
    Panel("Risk limits") {
        Stepper("Max trades / hour", "${s.maxTradesPerHour}", { upd { it.copy(maxTradesPerHour = it.maxTradesPerHour - 1) } }, { upd { it.copy(maxTradesPerHour = it.maxTradesPerHour + 1) } })
        Stepper("Max trades / day", "${s.maxDailyTrades}", { upd { it.copy(maxDailyTrades = it.maxDailyTrades - 5) } }, { upd { it.copy(maxDailyTrades = it.maxDailyTrades + 5) } })
        Stepper("Max consecutive losses", "${s.maxConsecutiveLosses}", { upd { it.copy(maxConsecutiveLosses = it.maxConsecutiveLosses - 1) } }, { upd { it.copy(maxConsecutiveLosses = it.maxConsecutiveLosses + 1) } })
        Stepper("Max daily loss", "${f1(s.maxDailyLossPercent)}%", { upd { it.copy(maxDailyLossPercent = it.maxDailyLossPercent - 0.5) } }, { upd { it.copy(maxDailyLossPercent = it.maxDailyLossPercent + 0.5) } })
        Stepper("Daily target", "${f1(s.dailyTargetPercent)}%", { upd { it.copy(dailyTargetPercent = it.dailyTargetPercent - 0.5) } }, { upd { it.copy(dailyTargetPercent = it.dailyTargetPercent + 0.5) } })
        Stepper("Max drawdown", "${f1(s.maxDrawdownPercent)}%", { upd { it.copy(maxDrawdownPercent = it.maxDrawdownPercent - 1.0) } }, { upd { it.copy(maxDrawdownPercent = it.maxDrawdownPercent + 1.0) } })
        Stepper("Session loss limit", "${f1(s.sessionLossPercent)}%", { upd { it.copy(sessionLossPercent = it.sessionLossPercent - 1.0) } }, { upd { it.copy(sessionLossPercent = it.sessionLossPercent + 1.0) } })
    }
    Panel("Strategies") {
        StrategyLibrary.all.forEach { st ->
            SwitchRow(st.name, st.id in s.enabledStrategies, { on ->
                upd { cur -> cur.copy(enabledStrategies = if (on) cur.enabledStrategies + st.id else cur.enabledStrategies - st.id) }
            })
        }
        SwitchRow("AI analysis layer", s.useAi, { on -> upd { it.copy(useAi = on) } }, "Needs an AI key in Settings. If the AI fails, it counts as WAIT.")
        SwitchRow("Multi-timeframe analysis", s.useMultiTimeframe, { on -> upd { it.copy(useMultiTimeframe = on) } })
    }
    Panel("Adaptive filter") {
        SwitchRow("Adaptive filtering", s.adaptiveEnabled, { on -> upd { it.copy(adaptiveEnabled = on) } }, "Can only make the engine stricter, within the limits below")
        Stepper("Max confidence bump", "+${s.adaptiveMaxBump}", { upd { it.copy(adaptiveMaxBump = it.adaptiveMaxBump - 1) } }, { upd { it.copy(adaptiveMaxBump = it.adaptiveMaxBump + 1) } })
        Stepper("Min trades before adapting", "${s.adaptiveMinTrades}", { upd { it.copy(adaptiveMinTrades = it.adaptiveMinTrades - 10) } }, { upd { it.copy(adaptiveMinTrades = it.adaptiveMinTrades + 10) } })
    }
    Panel("Alerts") {
        SwitchRow("Notifications (always labelled DEMO TRADE)", s.notificationsOn, { on -> upd { it.copy(notificationsOn = on) } })
        SwitchRow("Sound", s.soundOn, { on -> upd { it.copy(soundOn = on) } })
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset demo account?") },
            text = { Text("This clears the simulated balance, open demo trades, history and statistics. Settings are kept.") },
            confirmButton = { TextButton(onClick = { vm.resetAccount(); confirmReset = false }) { Text("RESET") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("CANCEL") } }
        )
    }
}

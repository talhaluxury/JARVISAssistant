package com.jarvis.assistant.ui.screens.quotex

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jarvis.assistant.quotex.agent.AgentStatus
import com.jarvis.assistant.quotex.agent.ExplanationEngine
import com.jarvis.assistant.quotex.analysis.QuotexBacktestReport
import com.jarvis.assistant.quotex.analysis.SetupQuality
import com.jarvis.assistant.quotex.capture.QuotexCaptureConsentActivity
import com.jarvis.assistant.quotex.overlay.QuotexOverlayService
import com.jarvis.assistant.trading.QuotexDecision
import com.jarvis.assistant.wingo.ScreenStatus
import com.jarvis.assistant.wingo.analysis.PerfStats
import com.jarvis.assistant.wingo.domain.Fmt
import kotlin.math.roundToInt

private val Cyan = Color(0xFF38BDF8)
private val Bg = Color(0xFF0A0E14)
private val CardBg = Color(0xFF0F1A24)
private val Muted = Color(0xFF94A3B8)
private val Good = Color(0xFF4ADE80)
private val Warn = Color(0xFFFBBF24)
private val Bad = Color(0xFFF87171)

@Composable
fun QuotexScreen(onBack: () -> Unit, vm: QuotexViewModel = viewModel()) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    val analytics by vm.analytics.collectAsState()
    val test by vm.test.collectAsState()
    val liveReport by vm.liveReport.collectAsState()
    val chat by vm.chat.collectAsState()
    val dataInfo by vm.dataInfo.collectAsState()
    val busy by vm.busy.collectAsState()

    var overlayAllowed by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var liveOn by remember { mutableStateOf(vm.liveAnalysisEnabled()) }
    var requireEdge by remember { mutableStateOf(vm.requireVerifiedEdge()) }
    var candleSeconds by remember { mutableStateOf(vm.candleSeconds()) }
    var expiry by remember { mutableStateOf(vm.expiryCandles().toFloat()) }
    var payout by remember { mutableStateOf(vm.payout()) }
    var stake by remember { mutableStateOf(vm.stakePerTrade()) }
    var dailyLimit by remember { mutableStateOf(vm.dailyLossLimit()) }
    var maxLosses by remember { mutableStateOf(vm.maxConsecutiveLosses().toFloat()) }
    var regionTop by remember { mutableStateOf(vm.regionTop()) }
    var regionBottom by remember { mutableStateOf(vm.regionBottom()) }
    var question by remember { mutableStateOf("") }
    var manualPrice by remember { mutableStateOf("") }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayAllowed = Settings.canDrawOverlays(context)
                vm.refreshAnalytics()
                vm.refreshJournal()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importTestCsv(uri)
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) vm.exportTo(uri)
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.restoreFrom(uri)
    }

    Column(
        Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ BACK", color = Cyan) }
            Text("JARVIS QUOTEX ANALYZER", color = Cyan, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 15.sp)
        }
        Text(
            "Analysis only. JARVIS never places, prepares or confirms trades and never taps the trading app. " +
                "Short-term price moves are close to random, OTC prices are generated by the broker, and a payout below 100% " +
                "means you must win more than about half your trades just to break even. Nothing here is a promise.",
            color = Muted, fontSize = 11.sp
        )

        Panel("AGENT STATUS") {
            StatusLine("Screen capture", if (state.captureReady) "ON" else "OFF", state.captureReady)
            StatusLine(
                "Chart", when (state.screenStatus) {
                    ScreenStatus.TRACKING -> "PRICE READ"
                    ScreenStatus.SEARCHING -> "SEARCHING"
                    ScreenStatus.NOT_DETECTED -> "NOT DETECTED"
                    ScreenStatus.NOT_STARTED -> "—"
                }, state.screenStatus == ScreenStatus.TRACKING
            )
            StatusLine("Asset", state.asset ?: "—", state.asset != null)
            StatusLine("Last price", state.lastPrice?.toString() ?: "—", state.lastPrice != null)
            StatusLine("Candles stored", "${state.candleCount} (${candleSeconds}s each)", state.candleCount >= 150)
            StatusLine("Unreadable ticks skipped", state.unreadableTicks.toString(), true)
            state.message?.let { Text(it, color = Warn, fontSize = 11.sp) }
        }

        Panel("LIVE") {
            val p = state.prediction
            if (p == null) {
                Text(state.message ?: "No analysis yet.", color = Muted, fontSize = 12.sp)
            } else {
                val shown = p.isSignal && p.decision != QuotexDecision.WAIT
                Text(
                    if (shown) (if (p.decision == QuotexDecision.CALL) "CALL ▲" else "PUT ▼") else "WAIT",
                    color = if (shown) Cyan else Muted, fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
                )
                Mono("Trend ${p.trend.name}  ·  Volatility ${p.volatility.name}")
                if (shown) {
                    Mono("Confidence ${Fmt.pct(p.confidence)}  ·  Signal ${p.signal.name}  ·  ${p.agree}/${p.totalModels} models agree")
                    Mono("Expiry ${state.expirySeconds}s  ·  break-even needs > ${Fmt.pct(state.breakEven, 1)} wins")
                } else {
                    Text(p.waitReason ?: "WAIT — insufficient signal.", color = Warn, fontSize = 11.sp)
                }
            }
            state.lastOutcome?.let { Mono("Last resolved call: " + if (it.correct) "✓ correct" else "✕ wrong") }
        }

        Panel("TRADING INTELLIGENCE (FULL PIPELINE)") {
            val agent = state.agent
            if (agent == null) {
                Text("No agent read yet - keep collecting price history.", color = Muted, fontSize = 12.sp)
            } else {
                val r = agent.report
                val statusColor = when (r.status) {
                    AgentStatus.SETUP_DETECTED -> Good
                    AgentStatus.NO_TRADE, AgentStatus.DATA_UNCERTAIN -> Warn
                    else -> Cyan
                }
                Text(
                    "${r.status.emoji} ${r.status.label}", color = statusColor,
                    fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
                )
                Mono("Mode ${agent.mode.name}  ·  Data ${r.dataQuality.name}  ·  Regime ${r.regime.name}")
                Mono("Session ${r.session.name}  ·  News ${r.newsRisk.name}  ·  Volatility ${r.volatility.name}")
                Mono("Conditions ${r.conditionsMet}/${r.conditionsTotal}  ·  Signal ${agent.lifecycle.state.name}")
                for (c in r.checks) {
                    Mono((if (c.passed) "✓ " else "✕ ") + c.step + ": " + c.detail)
                }
                Text(ExplanationEngine.explain(r), color = Muted, fontSize = 10.sp)
            }
        }

        Panel("CONFLUENCE / SETUP") {
            val c = state.confluence
            if (c == null) {
                Text("No strategy read yet.", color = Muted, fontSize = 12.sp)
            } else {
                Text(
                    "${c.quality.name.replace('_', ' ')}   ·   STATE ${state.signalState.name}",
                    color = if (c.quality == SetupQuality.NO_SETUP) Muted else Cyan,
                    fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
                )
                Mono("Structure ${c.structure.name}  ·  Trend ${c.trend.name}  ·  Volatility ${c.volatility.name}")
                Mono("Agreeing strategies: ${c.agreeingStrategies}/${c.totalStrategies}  ·  Conditions met: ${c.conditionsSatisfied}/${c.conditionsTotal}")
                Text(c.reason, color = Muted, fontSize = 10.sp)
                for (r in c.strategyResults) {
                    val tag = if (r.direction.name == "WAIT") "—" else "${r.direction.name} ${r.satisfiedCount}/${r.totalCount}"
                    Mono("${r.strategyName}: $tag")
                }
            }
        }

        Panel("STRATEGY PERFORMANCE (live, walk-forward)") {
            val statuses by vm.strategyStatuses.collectAsState()
            if (statuses.isEmpty() || statuses.all { it.samples == 0 }) {
                Text("No resolved strategy calls yet.", color = Muted, fontSize = 11.sp)
            } else {
                for (s in statuses) {
                    Mono("${s.name}: ${s.accuracy?.let { Fmt.pct(it, 1) } ?: "–"} (${s.samples})  weight ${Fmt.num(s.weight, 2)}")
                }
            }
            val confluenceReport by vm.confluenceReport.collectAsState()
            val confluenceBusy by vm.confluenceBusy.collectAsState()
            OutlinedButton(enabled = !confluenceBusy, onClick = { vm.runConfluenceBacktest() }) {
                Text(if (confluenceBusy) "Running…" else "Run confluence backtest", color = Cyan)
            }
            confluenceReport?.let { Text(it.toText(), color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
        }

        Panel("CHAT") {
            Text(chat, color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            OutlinedTextField(
                value = question, onValueChange = { question = it }, label = { Text("Ask JARVIS about Quotex") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { vm.ask(question); question = "" }) { Text("Ask") }
                OutlinedButton(onClick = { vm.ask("signal") }) { Text("Signal", color = Cyan, fontSize = 11.sp) }
                OutlinedButton(onClick = { vm.ask("why") }) { Text("Why", color = Cyan, fontSize = 11.sp) }
                OutlinedButton(onClick = { vm.ask("accuracy") }) { Text("Acc.", color = Cyan, fontSize = 11.sp) }
            }
        }

        Panel("SETUP") {
            SetupStep("1. Allow display over other apps", overlayAllowed) {
                if (!overlayAllowed) {
                    OutlinedButton(onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }) { Text("Open permission", color = Cyan) }
                }
            }
            SetupStep("2. Allow screen capture (Android asks each time)", state.captureReady) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!state.captureReady) {
                        Button(onClick = { context.startActivity(Intent(context, QuotexCaptureConsentActivity::class.java)) }) { Text("Start monitoring") }
                    } else {
                        OutlinedButton(onClick = { vm.module.controls.stopMonitoring() }) { Text("Stop", color = Bad) }
                    }
                    OutlinedButton(onClick = { QuotexOverlayService.show(context) }) { Text("Show HUD", color = Cyan) }
                }
            }
            SetupStep("3. Open the Quotex chart yourself (JARVIS never opens or taps it)", null) {}
            SetupStep("4. Screen area that shows the asset name and the price axis", null) {
                Text("Top ${Fmt.pct(regionTop.toDouble())}  ·  Bottom ${Fmt.pct(regionBottom.toDouble())}", color = Muted, fontSize = 11.sp)
                Slider(value = regionTop, onValueChange = { regionTop = it }, valueRange = 0f..0.9f, onValueChangeFinished = { vm.saveRegion(regionTop, regionBottom) })
                Slider(value = regionBottom, onValueChange = { regionBottom = it }, valueRange = 0.1f..1f, onValueChangeFinished = { vm.saveRegion(regionTop, regionBottom) })
                Text("Restart monitoring after changing this.", color = Muted, fontSize = 10.sp)
            }
            SetupStep("5. Candle length", null) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (s in listOf(10, 15, 30, 60)) {
                        OutlinedButton(onClick = { candleSeconds = s; vm.setCandleSeconds(s) }) {
                            Text("${s}s", color = if (candleSeconds == s) Cyan else Muted, fontSize = 11.sp)
                        }
                    }
                }
            }
            SetupStep("6. Expiry: ${expiry.roundToInt()} candles = ${expiry.roundToInt() * candleSeconds}s", null) {
                Slider(value = expiry, onValueChange = { expiry = it }, valueRange = 1f..12f, steps = 10,
                    onValueChangeFinished = { vm.setExpiryCandles(expiry.roundToInt()) })
            }
            SetupStep("7. Payout shown on the asset: ${Fmt.pct(payout.toDouble())}", null) {
                Slider(value = payout, onValueChange = { payout = it }, valueRange = 0.5f..0.95f,
                    onValueChangeFinished = { vm.setPayout(payout) })
            }
            SetupStep("8. Enable live analysis (needs 150+ candles)", liveOn) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = liveOn, onCheckedChange = { liveOn = it; vm.setLiveAnalysis(it) })
                    Spacer(Modifier.width(8.dp))
                    Text(if (liveOn) "Live analysis ON" else "Live analysis OFF", color = Muted, fontSize = 12.sp)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = requireEdge, onCheckedChange = { requireEdge = it; vm.setRequireVerifiedEdge(it) })
                Spacer(Modifier.width(8.dp))
                Text("Only show a signal once a real edge is measured (recommended)", color = Muted, fontSize = 11.sp)
            }
        }

        Panel("ANALYTICS (THIS SESSION)") {
            val a = analytics
            if (a == null) {
                Text("Loading…", color = Muted, fontSize = 11.sp)
            } else {
                Mono("All leaning calls: ${statsLine(a.session.allCalls)}")
                Mono("Signalled only:   ${statsLine(a.session.signalled)}")
                Text("Accuracy by confidence", color = Cyan, fontSize = 11.sp)
                for (b in a.bands) Mono("${b.label}: ${b.accuracy?.let { Fmt.pct(it, 1) } ?: "–"} (${b.calls})")
                Text("Models (walk-forward)", color = Cyan, fontSize = 11.sp)
                for (m in a.modelStatuses) {
                    Mono("${m.name}: ${m.accuracy?.let { Fmt.pct(it, 1) } ?: "–"} (${m.samples})  weight ${Fmt.num(m.weight, 2)}")
                }
            }
            OutlinedButton(onClick = { vm.refreshAnalytics() }) { Text("Refresh", color = Cyan) }
        }

        Panel("BACKTEST ON STORED CANDLES") {
            OutlinedButton(enabled = !busy, onClick = { vm.runStoredBacktest() }) { Text(if (busy) "Running…" else "Run backtest", color = Cyan) }
            ReportText(liveReport ?: state.backtest)
        }

        Panel("ADD A PRICE BY HAND (TESTING)") {
            OutlinedTextField(
                value = manualPrice, onValueChange = { manualPrice = it }, label = { Text("Price, e.g. 1.08234") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(onClick = { vm.addManualPrice(manualPrice); manualPrice = "" }) { Text("Add price", color = Cyan) }
        }

        Panel("RISK (DISCIPLINE ONLY - JARVIS NEVER PLACES TRADES)") {
            val risk = state.risk
            if (risk == null) {
                Text("No risk data yet.", color = Muted, fontSize = 11.sp)
            } else {
                Text(
                    if (risk.paused) "TRADING PAUSED" else "ACTIVE",
                    color = if (risk.paused) Bad else Good, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
                )
                risk.reason?.let { Text(it, color = Warn, fontSize = 11.sp) }
                Mono("Today: ${risk.winsToday}W / ${risk.lossesToday}L (${risk.tradesToday} tracked)  ·  P/L ${Fmt.num(risk.dailyPnL, 2)}")
                Mono("Losses in a row: ${risk.consecutiveLosses}  (pause at ${risk.config.maxConsecutiveLosses})")
            }
            Text(
                "Tracks a stake YOU declare against JARVIS's resolved signals only. It pauses JARVIS's own displayed setups " +
                    "when a limit is hit, and stops counting while paused. It never changes the stake after a loss (no martingale). " +
                    "Resume clears a manual pause or a losing-streak cool-down; a daily-loss pause lifts tomorrow. " +
                    "Quotex's own daily-loss controls remain the real safeguard - use them too.",
                color = Muted, fontSize = 10.sp
            )
            Text("Stake per trade: ${Fmt.num(stake.toDouble(), 2)}", color = Color.White, fontSize = 12.sp)
            Slider(value = stake, onValueChange = { stake = it }, valueRange = 0.5f..100f,
                onValueChangeFinished = { vm.setStakePerTrade(stake) })
            Text("Daily loss limit: ${Fmt.num(dailyLimit.toDouble(), 2)}", color = Color.White, fontSize = 12.sp)
            Slider(value = dailyLimit, onValueChange = { dailyLimit = it }, valueRange = 1f..500f,
                onValueChangeFinished = { vm.setDailyLossLimit(dailyLimit) })
            Text("Pause after ${maxLosses.roundToInt()} losses in a row", color = Color.White, fontSize = 12.sp)
            Slider(value = maxLosses, onValueChange = { maxLosses = it }, valueRange = 1f..10f, steps = 8,
                onValueChangeFinished = { vm.setMaxConsecutiveLosses(maxLosses.roundToInt()) })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.setRiskPaused(true) }) { Text("Pause setups", color = Warn) }
                OutlinedButton(onClick = { vm.setRiskPaused(false) }) { Text("Resume setups", color = Cyan) }
            }
        }

        Panel("TRADE JOURNAL") {
            val today by vm.journalToday.collectAsState()
            val recent by vm.journalRecent.collectAsState()
            val todayResolved = today.filter { it.resolved }
            val todayWins = todayResolved.count { it.correct == true }
            Mono(
                if (today.isEmpty()) "No signals logged today."
                else "Today: $todayWins/${todayResolved.size} correct (${today.size - todayResolved.size} pending)"
            )
            Text("Last ${recent.size} logged signals:", color = Muted, fontSize = 11.sp)
            if (recent.isEmpty()) {
                Text("Nothing logged yet - a row is saved every time a real signal is surfaced.", color = Muted, fontSize = 10.sp)
            } else {
                for (e in recent.take(10)) {
                    val status = when (e.correct) { true -> "✓"; false -> "✕"; null -> "…" }
                    Mono("$status ${e.direction} ${e.asset} @ ${Fmt.pct(e.confidence)} · ${e.confluenceQuality}")
                }
            }
            OutlinedButton(onClick = { vm.refreshJournal() }) { Text("Refresh journal", color = Cyan) }
        }

        Panel("TEST MODE (OFFLINE, SEPARATE FROM LIVE DATA)") {
            Text(test.info, color = Muted, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text("Import CSV", color = Cyan) }
                OutlinedButton(onClick = { vm.generateRandomControl() }) { Text("Random control", color = Cyan) }
            }
            Text("CSV columns: Asset,OpenTimeMs,Open,High,Low,Close (Asset optional).", color = Muted, fontSize = 10.sp)
            Button(enabled = test.candles.isNotEmpty() && !test.running, onClick = { vm.runTestBacktest() }) {
                Text(if (test.running) "Running…" else "Start backtest")
            }
            ReportText(test.report)
        }

        Panel("DATA BACKUP") {
            Text(
                "Save all stored candles to a file (choose Downloads). After reinstalling, restore it here to get your history back. " +
                    "Restoring only adds candles that are missing.",
                color = Muted, fontSize = 11.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exportLauncher.launch("quotex_candles.csv") }) { Text("Export data", color = Cyan) }
                OutlinedButton(onClick = { restoreLauncher.launch(arrayOf("*/*")) }) { Text("Restore data", color = Cyan) }
            }
            if (dataInfo.isNotBlank()) Text(dataInfo, color = Warn, fontSize = 11.sp)
            OutlinedButton(onClick = { vm.resetData() }) { Text("Delete all stored Quotex data", color = Bad) }
        }
    }
}

@Composable
private fun Panel(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = CardBg), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = Cyan, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            content()
        }
    }
}

@Composable
private fun Mono(text: String) {
    Text(text, color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
}

@Composable
private fun StatusLine(name: String, value: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(name, color = Muted, fontSize = 12.sp)
        Text(value, color = if (ok) Good else Warn, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun SetupStep(title: String, done: Boolean?, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val mark = when (done) {
            true -> "✓ "
            false -> "○ "
            null -> "• "
        }
        Text(mark + title, color = if (done == true) Good else Color.White, fontSize = 12.sp)
        content()
    }
}

private fun statsLine(s: PerfStats): String {
    val acc = s.accuracy ?: return "no resolved calls"
    return "${Fmt.pct(acc, 1)} (${s.correct}/${s.calls}) streaks W${s.maxWinStreak}/L${s.maxLossStreak}"
}

@Composable
private fun ReportText(report: QuotexBacktestReport?) {
    if (report == null) return
    Text(report.toText(), color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
}

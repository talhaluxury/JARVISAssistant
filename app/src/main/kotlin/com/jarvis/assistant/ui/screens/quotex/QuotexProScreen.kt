package com.jarvis.assistant.ui.screens.quotex

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.jarvis.assistant.quotex.domain.Candle
import com.jarvis.assistant.quotex.pro.StatLine
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jarvis.assistant.quotex.agent.AgentStatus
import com.jarvis.assistant.quotex.agent.CandleClock
import com.jarvis.assistant.quotex.agent.JournalEntry as AgentJournalEntry
import com.jarvis.assistant.quotex.agent.toText
import com.jarvis.assistant.quotex.agent.EconomicEvent
import com.jarvis.assistant.quotex.agent.EventImpact
import com.jarvis.assistant.quotex.agent.fullStrategyLibrary
import com.jarvis.assistant.quotex.pro.LabBacktester
import com.jarvis.assistant.quotex.pro.LabRuleStore
import com.jarvis.assistant.quotex.pro.NewsEventCodec
import com.jarvis.assistant.quotex.pro.ParameterRobustness
import com.jarvis.assistant.quotex.pro.PaperStat
import com.jarvis.assistant.quotex.pro.RuleSpec
import com.jarvis.assistant.quotex.pro.sweep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.jarvis.assistant.quotex.pro.LabReport
import com.jarvis.assistant.quotex.pro.LabStatus
import com.jarvis.assistant.quotex.pro.PerformanceStats
import com.jarvis.assistant.quotex.pro.ProNarrator
import com.jarvis.assistant.quotex.pro.RuleCondition
import com.jarvis.assistant.quotex.pro.Robustness
import com.jarvis.assistant.quotex.pro.StrategyDeployGate
import com.jarvis.assistant.quotex.pro.StrategyRule
import com.jarvis.assistant.quotex.pro.ValidationSummary
import com.jarvis.assistant.quotex.agent.JournalOutcome
import com.jarvis.assistant.trading.QuotexDecision
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val ProBg = Color(0xFF0A0E14)
private val ProCyan = Color(0xFF38BDF8)
private val ProSurface = Color(0xFF12161F)
private val ProText = Color(0xFFE7EEF6)
private val ProDim = Color(0xFF8A97AB)
private val ProGreen = Color(0xFF34D399)
private val ProRed = Color(0xFFFF6B6B)
private val ProAmber = Color(0xFFFBBF24)

private enum class ProTab(val label: String) {
    LIVE("LIVE"), STRUCTURE("STRUCTURE"), PAPER("PAPER"), PRACTICE("PRACTICE"), PERFORMANCE("STATS"),
    LAB("STRATEGY LAB"), BACKTEST("BACKTEST"), JOURNAL("JOURNAL"), SETTINGS("SETTINGS"), DIAG("DIAGNOSTICS")
}

@Composable
private fun ProCard(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = ProSurface), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = ProCyan, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            content()
        }
    }
}

@Composable
private fun Body(text: String, color: Color = ProText) =
    Text(text, color = color, fontSize = 13.sp, fontFamily = FontFamily.Monospace)

/** Pro analyzer: explainable signal, structure, paper trading, stats, Strategy Lab, backtests, journal, settings, diagnostics. Analysis only. */
@Composable
fun QuotexProScreen(onBack: () -> Unit, vm: QuotexViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val coordinator = vm.module.coordinator
    val settings = vm.module.settings
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(ProTab.LIVE) }
    var tick by remember { mutableStateOf(0) }
    // Light refresh once a second for the countdown only while this screen is open.
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }

    Column(Modifier.fillMaxSize().background(ProBg).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("\u2039 BACK", color = ProCyan) }
            Text("JARVIS PRO ANALYZER", color = ProCyan, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 15.sp)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ProTab.values().forEach { t ->
                TextButton(onClick = { tab = t }) {
                    Text(t.label, color = if (t == tab) ProCyan else ProDim, fontWeight = if (t == tab) FontWeight.Bold else FontWeight.Normal, fontSize = 12.sp)
                }
            }
        }
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (tab) {
                ProTab.LIVE -> {
                    val a = state.agent
                    val r = a?.report
                    val color = when {
                        r == null -> ProDim
                        r.status == AgentStatus.SETUP_DETECTED && r.direction == QuotexDecision.CALL -> ProGreen
                        r.status == AgentStatus.SETUP_DETECTED && r.direction == QuotexDecision.PUT -> ProRed
                        r.status == AgentStatus.NO_TRADE || r.status == AgentStatus.DATA_UNCERTAIN -> ProAmber
                        else -> ProCyan
                    }
                    ProCard("SIGNAL") {
                        val head = when {
                            r == null -> "SCANNING"
                            r.status == AgentStatus.SETUP_DETECTED -> r.direction.name
                            r.status == AgentStatus.DATA_UNCERTAIN -> "NO SIGNAL"
                            else -> "WAIT"
                        }
                        Text(head, color = color, fontSize = 34.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        if (r != null) {
                            Body("Setup score: ${r.setupScore?.let { "$it/100" } ?: "n/a"} (strength, not a win probability)")
                            Body("Entry quality: ${r.entry?.quality ?: "n/a"}")
                            Body("Regime: ${r.regime}   Session: ${r.session}")
                            Body("Data: ${r.dataQuality}   News: ${r.newsRisk}")
                            val candleMs = a?.candleMs ?: 0L
                            val live = if (candleMs > 0L && tick >= 0) CandleClock.remainingMs(System.currentTimeMillis(), candleMs) else 0L
                            Body("Candle closes in ~${live.coerceAtLeast(0L) / 1000}s", ProDim)
                        } else Body("Start monitoring from the Quotex Analyzer screen.", ProDim)
                    }
                    ProCard("WHY") { Body(ProNarrator.signal(a)) }
                    ProCard("REASONS") { Body(ProNarrator.reasons(a)) }
                }
                ProTab.STRUCTURE -> ProCard("MARKET STRUCTURE") { Body(ProNarrator.structure(state.agent)) }
                ProTab.PAPER -> {
                    val all = coordinator.journalLast(500)
                    val dayStart = System.currentTimeMillis() - 24L * 3600 * 1000
                    val today = all.filter { it.timestampMs >= dayStart }
                    val w = today.count { it.outcome == JournalOutcome.WIN }
                    val l = today.count { it.outcome == JournalOutcome.LOSS }
                    val p = today.count { it.outcome == JournalOutcome.PENDING }
                    var stake by remember { mutableStateOf(settings.paperStake) }
                    val payout = settings.payout.toDouble()
                    fun pl(list: List<AgentJournalEntry>): Double =
                        list.sumOf { when (it.outcome) { JournalOutcome.WIN -> stake * payout; JournalOutcome.LOSS -> -stake.toDouble(); else -> 0.0 } }
                    ProCard("PAPER TRADING (virtual only - no order is ever sent)") {
                        Body("Last 24h: ${today.size} signals  \u2022  $w wins  \u2022  $l losses  \u2022  $p waiting")
                        Body("Virtual profit/loss (24h): %+.2f   (all recorded: %+.2f)".format(pl(today), pl(all)), if (pl(today) >= 0) ProGreen else ProRed)
                        Body("Virtual stake per trade: ${stake.toInt()}  (payout ${(payout * 100).toInt()}%)", ProDim)
                        Slider(value = stake, onValueChange = { stake = it }, valueRange = 1f..1000f, onValueChangeFinished = { settings.paperStake = stake })
                        Body("Play money only. CALL = price expected UP, PUT = price expected DOWN. You place any real or demo trade yourself.", ProDim)
                    }
                    ProCard("RECENT PAPER TRADES") {
                        if (all.isEmpty()) Body("None yet.", ProDim)
                        all.take(15).forEach { e ->
                            val dirText = when (e.direction) { "CALL" -> "CALL (UP)"; "PUT" -> "PUT (DOWN)"; else -> e.direction }
                            val res = when (e.outcome) {
                                JournalOutcome.WIN -> "WIN %+.2f".format(stake * payout)
                                JournalOutcome.LOSS -> "LOSS %+.2f".format(-stake.toDouble())
                                else -> e.outcome.name
                            }
                            Body("${e.asset} $dirText  in ${e.entryPrice}  out ${e.exitPrice ?: "-"}  $res")
                        }
                    }
                    val done = all.filter { it.outcome == JournalOutcome.WIN || it.outcome == JournalOutcome.LOSS }
                    val sections: List<Pair<String, (AgentJournalEntry) -> String>> = listOf(
                        Pair("BY STRATEGY", { e: AgentJournalEntry -> e.strategies.joinToString("+").ifEmpty { "-" } }),
                        Pair("BY TIMEFRAME", { e: AgentJournalEntry -> "${e.timeframeSeconds}s" }),
                        Pair("BY REGIME", { e: AgentJournalEntry -> e.regime }),
                        Pair("BY SESSION", { e: AgentJournalEntry -> e.session })
                    )
                    sections.forEach { (title, key) ->
                        val lines = PerformanceStats.groupLines(done, key) { it.outcome == JournalOutcome.WIN }
                        ProCard(title) {
                            if (lines.isEmpty()) Body("No resolved paper setups yet.", ProDim)
                            lines.forEach { Body(it.display()) }
                        }
                    }
                }
                ProTab.PRACTICE -> PracticePanel(vm)
                ProTab.PERFORMANCE -> {
                    val all = coordinator.journalLast(500)
                    ProCard("PERFORMANCE") { Body(ProNarrator.performance(all)) }
                    ProCard("ROBUSTNESS (Monte Carlo)") { Body(ProNarrator.monteCarlo(all)) }
                }
                ProTab.LAB -> LabPanel(vm)
                ProTab.BACKTEST -> {
                    var text by remember { mutableStateOf("Walk-forward test (train / validation / out-of-sample) of the full pipeline. Uses only past candles at each step.") }
                    var busy by remember { mutableStateOf(false) }
                    Button(enabled = !busy, onClick = {
                        busy = true
                        scope.launch { text = try { coordinator.runAgentBacktest(null).toText() } catch (e: Exception) { "Backtest failed: ${e.message ?: "unknown error"}" }; busy = false }
                    }) { Text(if (busy) "RUNNING..." else "RUN WALK-FORWARD BACKTEST") }
                    ProCard("RESULT") { Body(text) }
                }
                ProTab.JOURNAL -> {
                    val all = coordinator.journalLast(50)
                    if (all.isEmpty()) ProCard("JOURNAL") { Body("No signals recorded yet.", ProDim) }
                    all.forEach { e ->
                        ProCard("SIGNAL #${e.id.hashCode().toUInt().toString(16).uppercase().takeLast(6)}") {
                            Body("${e.asset}  ${e.direction}  ${e.quality}  ${e.timeframeSeconds}s")
                            Body("Regime ${e.regime}  Session ${e.session}  Outcome ${e.outcome}")
                            Body(e.reasonText.take(220), ProDim)
                        }
                    }
                }
                ProTab.SETTINGS -> {
                    var minScore by remember { mutableStateOf(settings.minSetupScore.toFloat()) }
                    var minDq by remember { mutableStateOf(settings.minDataQualityScore.toFloat()) }
                    var debug by remember { mutableStateOf(settings.debugLogging) }
                    var pan by remember { mutableStateOf(settings.autoChartPan) }
                    ProCard("MINIMUM SETUP SCORE: ${minScore.toInt()}") {
                        Slider(value = minScore, onValueChange = { minScore = it }, valueRange = 0f..100f, onValueChangeFinished = { settings.minSetupScore = minScore.toInt(); scope.launch { coordinator.applySettings() } })
                    }
                    ProCard("MINIMUM DATA QUALITY: ${minDq.toInt()}") {
                        Slider(value = minDq, onValueChange = { minDq = it }, valueRange = 0f..100f, onValueChangeFinished = { settings.minDataQualityScore = minDq.toInt(); scope.launch { coordinator.applySettings() } })
                        Body("Below this the analysis pauses: DATA QUALITY TOO LOW.", ProDim)
                    }
                    ProCard("DEBUG LOGGING") {
                        Row(verticalAlignment = Alignment.CenterVertically) { Switch(checked = debug, onCheckedChange = { debug = it; settings.debugLogging = it }); Body("  verbose (never logs keys)") }
                    }
                    ProCard("LET JARVIS SCROLL THE CHART") {
                        Row(verticalAlignment = Alignment.CenterVertically) { Switch(checked = pan, onCheckedChange = { pan = it; settings.autoChartPan = it }); Body("  one horizontal drag, OFF by default") }
                    }
                    NewsPanel(vm)
                    AnalysisSettings(vm)
                    Body("Capture interval, expiry, payout, voice, overlay and risk limits are on the Quotex Analyzer screen. Changes here apply to the next candle.", ProDim)
                }
                ProTab.DIAG -> {
                    ProCard("PIPELINE") {
                        Body("Screen: ${state.screenStatus}")
                        Body("Monitor on: ${state.monitorOn}   Capture ready: ${state.captureReady}")
                        Body("Asset: ${state.asset ?: "unknown"}   Candles: ${state.candleCount}   Unreadable ticks: ${state.unreadableTicks}")
                        Body("Candle length: ${state.candleSeconds}s   Expiry: ${state.expirySeconds}s")
                        Body("Reader: ${state.readerNote.ifBlank { "-" }}", ProDim)
                        Body("Chart: ${state.chartStatus.ifBlank { "-" }}", ProDim)
                        Body("Data: ${state.agent?.report?.dataSummary ?: "-"}", ProDim)
                        state.message?.let { Body(it, ProAmber) }
                    }
                }
            }
        }
    }
}

private class LabRun(val report: LabReport, val robustness: Robustness, val robustnessNote: String)

private fun cycle(current: Int, options: List<Int>): Int {
    val i = options.indexOf(current)
    return options[(i + 1).mod(options.size)]
}

@Composable
private fun LabPanel(vm: QuotexViewModel) {
    val scope = rememberCoroutineScope()
    val settings = vm.module.settings
    val coordinator = vm.module.coordinator
    var rules by remember { mutableStateOf(LabRuleStore.parse(settings.labRules)) }
    var editing by remember { mutableStateOf<RuleSpec?>(null) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf("Create a rule, backtest it, observe it on paper, then enable it. Enabled rules only add evidence - they never create a signal on their own.") }
    val runs = remember { mutableStateMapOf<String, LabRun>() }
    var refresh by remember { mutableStateOf(0) }

    fun save(list: List<RuleSpec>) { rules = list; settings.labRules = LabRuleStore.serialize(list); refresh++ }
    fun update(id: String, f: (RuleSpec) -> RuleSpec) = save(rules.map { if (it.id == id) f(it) else it })

    ProCard("STRATEGY LAB") {
        Body(msg, ProDim)
        Button(onClick = {
            val spec = RuleSpec(id = "r" + System.currentTimeMillis().toString(36), name = "Rule ${rules.size + 1}")
            save(rules + spec); editing = spec
        }) { Text("+ NEW RULE") }
    }

    editing?.let { e0 ->
        var e by remember(e0.id) { mutableStateOf(e0) }
        ProCard("EDIT RULE") {
            OutlinedTextField(value = e.name, onValueChange = { e = e.copy(name = it.take(40)) }, label = { Text("Name") }, singleLine = true)
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(checked = e.callSide, onCheckedChange = { e = e.copy(callSide = it) }); Body("  side: ${if (e.callSide) "CALL" else "PUT"}") }
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(checked = e.useEma, onCheckedChange = { e = e.copy(useEma = it) }); Body("  EMA ${e.emaFast} ${if (e.callSide) ">" else "<"} EMA ${e.emaSlow}") }
            if (e.useEma) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { val f = cycle(e.emaFast, listOf(9, 21, 50, 100)); e = e.copy(emaFast = f, emaSlow = maxOf(e.emaSlow, listOf(21, 50, 100, 200).first { it > f })) }) { Text("fast ${e.emaFast}") }
                OutlinedButton(onClick = { e = e.copy(emaSlow = cycle(e.emaSlow, listOf(21, 50, 100, 200).filter { it > e.emaFast })) }) { Text("slow ${e.emaSlow}") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(checked = e.useRsi, onCheckedChange = { e = e.copy(useRsi = it) }); Body("  RSI ${e.rsiLo.toInt()} - ${e.rsiHi.toInt()}") }
            if (e.useRsi) {
                Slider(value = e.rsiLo.toFloat(), onValueChange = { e = e.copy(rsiLo = minOf(it.toDouble(), e.rsiHi - 5)) }, valueRange = 0f..95f)
                Slider(value = e.rsiHi.toFloat(), onValueChange = { e = e.copy(rsiHi = maxOf(it.toDouble(), e.rsiLo + 5)) }, valueRange = 5f..100f)
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(checked = e.useAdx, onCheckedChange = { e = e.copy(useAdx = it) }); Body("  ADX >= ${e.adxMin.toInt()}") }
            if (e.useAdx) Slider(value = e.adxMin.toFloat(), onValueChange = { e = e.copy(adxMin = it.toDouble()) }, valueRange = 5f..50f)
            Body("Saving resets this rule to DRAFT and clears its paper results (new parameters = new strategy).", ProDim)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (!e.useEma && !e.useRsi && !e.useAdx) { msg = "Pick at least one condition." } else {
                        save(rules.map { if (it.id == e.id) e.copy(status = LabStatus.DRAFT) else it })
                        coordinator.resetLabPaper(e.id); runs.remove(e.id); editing = null
                    }
                }) { Text("SAVE") }
                OutlinedButton(onClick = { editing = null }) { Text("CANCEL") }
            }
        }
    }

    rules.forEach { r ->
        val stat = remember(refresh, r.id) { coordinator.labPaperStat(r.id) }
        val run = runs[r.id]
        ProCard("${r.name}  [${r.status}]") {
            Body(r.describe())
            Body("Paper: ${stat.trades} trades, ${stat.wins} wins" + if (stat.trades >= 30) ", expectancy %.3f".format(stat.expectancy) else " (need 30 for meaning)", ProDim)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                OutlinedButton(onClick = { editing = r }) { Text("EDIT") }
                OutlinedButton(enabled = busyId == null, onClick = {
                    busyId = r.id
                    scope.launch {
                        try {
                            val candles = coordinator.candlesSnapshot()
                            if (candles.size < 150) msg = "Need at least 150 candles, have ${candles.size}." else {
                                val (rep, sw) = withContext(Dispatchers.Default) {
                                    Pair(LabBacktester.run(r.toRule(), candles), LabBacktester.sweep(r, candles))
                                }
                                val (rb, note) = ParameterRobustness.assess(sw)
                                runs[r.id] = LabRun(rep, rb, note)
                                val oos = rep.segments[2]
                                val passed = oos.trades >= 30 && oos.expectancy > 0.0 && rep.walkForwardPositive * 3 >= rep.walkForwardWindows * 2
                                update(r.id) { it.copy(status = if (it.status.ordinal >= LabStatus.PAPER_TESTING.ordinal) it.status else if (passed) LabStatus.WALK_FORWARD_PASSED else LabStatus.BACKTESTED) }
                                msg = if (passed) "${r.name}: backtest and walk-forward look acceptable. Observe it on paper next." else "${r.name}: did not pass backtest/walk-forward - see warnings."
                            }
                        } catch (ex: Exception) { msg = "Lab run failed: ${ex.message ?: "unknown"}" }
                        busyId = null
                    }
                }) { Text(if (busyId == r.id) "RUNNING..." else "BACKTEST") }
                OutlinedButton(onClick = {
                    when {
                        r.status == LabStatus.PAPER_TESTING -> { update(r.id) { it.copy(status = LabStatus.WALK_FORWARD_PASSED) }; msg = "Paper observation stopped." }
                        r.status == LabStatus.WALK_FORWARD_PASSED || r.status == LabStatus.ENABLED -> { update(r.id) { it.copy(status = LabStatus.PAPER_TESTING) }; msg = "Paper observation started (virtual trades only, live candles)." }
                        else -> msg = "Backtest and pass walk-forward before paper testing."
                    }
                }) { Text(if (r.status == LabStatus.PAPER_TESTING) "STOP PAPER" else "PAPER TEST") }
                OutlinedButton(onClick = {
                    if (r.status == LabStatus.ENABLED) { update(r.id) { it.copy(status = LabStatus.PAPER_TESTING) }; msg = "${r.name} disabled." } else {
                        val rep = run
                        if (rep == null) msg = "Run BACKTEST first." else {
                            val seg = rep.report.segments
                            val oosTrades = seg[1].trades + seg[2].trades
                            val oosExp = if (oosTrades == 0) 0.0 else (seg[1].expectancy * seg[1].trades + seg[2].expectancy * seg[2].trades) / oosTrades
                            val gate = StrategyDeployGate.canEnable(
                                ValidationSummary(oosTrades, oosExp, rep.report.walkForwardPositive, rep.report.walkForwardWindows, rep.robustness, stat.trades, stat.expectancy)
                            )
                            if (gate.allowed) { update(r.id) { it.copy(status = LabStatus.ENABLED) }; msg = "${r.name} enabled as extra evidence." }
                            else msg = "NOT enabled: " + gate.reasons.joinToString("; ")
                        }
                    }
                }) { Text(if (r.status == LabStatus.ENABLED) "DISABLE" else "ENABLE") }
                OutlinedButton(onClick = { coordinator.resetLabPaper(r.id); runs.remove(r.id); save(rules.filter { it.id != r.id }); if (editing?.id == r.id) editing = null }) { Text("DELETE") }
            }
            run?.let { x ->
                x.report.segments.forEach { Body("${it.name}: ${it.trades} trades, ${it.wins} wins, expectancy %.3f".format(it.expectancy)) }
                Body("Walk-forward windows profitable: ${x.report.walkForwardPositive}/${x.report.walkForwardWindows}")
                Body("Robustness: ${x.robustness} - ${x.robustnessNote}")
                Body("Monte Carlo drawdown median %.1f, p95 %.1f stakes".format(x.report.monteCarlo.medianMaxDrawdown, x.report.monteCarlo.p95MaxDrawdown))
                x.report.warnings.forEach { Body("\u26A0 $it", ProAmber) }
            }
        }
    }

    if (runs.size >= 2) ProCard("COMPARE (out-of-sample = validation + test)") {
        runs.entries.forEach { (id, x) ->
            val seg = x.report.segments
            val n = seg[1].trades + seg[2].trades
            val exp = if (n == 0) 0.0 else (seg[1].expectancy * seg[1].trades + seg[2].expectancy * seg[2].trades) / n
            Body("${rules.firstOrNull { it.id == id }?.name ?: id}: $n trades, expectancy %.3f, WF ${x.report.walkForwardPositive}/${x.report.walkForwardWindows}, ${x.robustness}".format(exp))
        }
    }
}

@Composable
private fun NewsPanel(vm: QuotexViewModel) {
    val settings = vm.module.settings
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(settings.newsCalendarEnabled) }
    var events by remember { mutableStateOf(NewsEventCodec.parse(settings.newsEvents)) }
    var title by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf(30f) }
    var impact by remember { mutableStateOf(EventImpact.HIGH) }
    var window by remember { mutableStateOf(settings.newsHighWindowMin.toFloat()) }
    fun persist(list: List<EconomicEvent>) { events = list; settings.newsEvents = NewsEventCodec.serialize(list); scope.launch { vm.module.coordinator.applySettings() } }

    ProCard("NEWS / EVENT CALENDAR") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = enabled, onCheckedChange = { enabled = it; settings.newsCalendarEnabled = it; scope.launch { vm.module.coordinator.applySettings() } })
            Body("  I maintain this calendar")
        }
        Body(if (enabled) "News risk uses ONLY the events below. Empty list = you assert no events." else "OFF: news shows NEWS DATA UNAVAILABLE (unknown, not safe).", ProDim)
        if (enabled) {
            OutlinedTextField(value = title, onValueChange = { title = it.take(40) }, label = { Text("Event title") }, singleLine = true)
            Body("Starts in ${minutes.toInt()} min")
            Slider(value = minutes, onValueChange = { minutes = it }, valueRange = 0f..720f)
            OutlinedButton(onClick = { impact = EventImpact.values()[(impact.ordinal + 1) % EventImpact.values().size] }) { Text("Impact: ${impact.name} (tap to change)") }
            Button(onClick = {
                val t = title.ifBlank { "Event" }
                persist((events + EconomicEvent(System.currentTimeMillis() + minutes.toLong() * 60_000L, t, impact)).sortedBy { it.timeMs })
                title = ""
            }) { Text("ADD EVENT") }
            val now = System.currentTimeMillis()
            events.filter { it.timeMs > now - 3600_000L }.forEach { ev ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Body("${ev.impact.name} ${ev.title} in ${(ev.timeMs - now) / 60_000L} min")
                    TextButton(onClick = { persist(events.filter { it !== ev }) }) { Text("remove", color = ProRed) }
                }
            }
            Body("HIGH-impact window: ±${window.toInt()} min")
            Slider(value = window, onValueChange = { window = it }, valueRange = 5f..60f, onValueChangeFinished = { settings.newsHighWindowMin = window.toInt(); scope.launch { vm.module.coordinator.applySettings() } })
        }
    }
}

@Composable
private fun AnalysisSettings(vm: QuotexViewModel) {
    val settings = vm.module.settings
    val scope = rememberCoroutineScope()
    fun apply() { scope.launch { vm.module.coordinator.applySettings() } }
    var disabled by remember { mutableStateOf(settings.disabledStrategies.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()) }
    var mid by remember { mutableStateOf(settings.mtfMiddleSeconds) }
    var high by remember { mutableStateOf(settings.mtfHigherSeconds) }
    var blockHigh by remember { mutableStateOf(settings.blockHighVolatility) }
    val tfOptions = listOf(0, 60, 300, 900, 1800, 3600)
    fun tfLabel(s: Int) = if (s == 0) "AUTO" else com.jarvis.assistant.quotex.agent.CandleClock.label(s)
    val names = remember { fullStrategyLibrary().map { it.name } }

    ProCard("TIMEFRAME HIERARCHY") {
        Body("Entry timeframe is the chart you trade; middle confirms momentum, higher gives context. Must be exact multiples of the entry timeframe; otherwise that level is skipped.", ProDim)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { mid = cycle(mid, tfOptions); settings.mtfMiddleSeconds = mid; apply() }) { Text("middle: ${tfLabel(mid)}") }
            OutlinedButton(onClick = { high = cycle(high, tfOptions); settings.mtfHigherSeconds = high; apply() }) { Text("higher: ${tfLabel(high)}") }
        }
    }
    ProCard("VOLATILITY") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = blockHigh, onCheckedChange = { blockHigh = it; settings.blockHighVolatility = it; apply() })
            Body("  also block HIGH volatility (EXTREME is always blocked)")
        }
    }
    ProCard("STRATEGIES / INDICATOR EVIDENCE") {
        Body("Indicators (EMA, RSI, MACD, Bollinger, Stochastic, ADX) feed these strategies as evidence, never as independent trades. Switch off any you distrust.", ProDim)
        names.forEach { n ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = n !in disabled, onCheckedChange = { on ->
                    disabled = if (on) disabled - n else disabled + n
                    settings.disabledStrategies = disabled.joinToString(","); apply()
                })
                Body("  $n")
            }
        }
    }
}


/** Practice: replays stored candles up to a random point, you guess UP or DOWN, the next candles are then revealed. Nothing is sent anywhere. */
@Composable
private fun PracticePanel(vm: QuotexViewModel) {
    val coordinator = vm.module.coordinator
    var candles by remember { mutableStateOf<List<Candle>>(emptyList()) }
    var last by remember { mutableStateOf(-1) }
    var revealed by remember { mutableStateOf(false) }
    var wins by remember { mutableStateOf(0) }
    var losses by remember { mutableStateOf(0) }
    var result by remember { mutableStateOf("") }
    val expiry = 3

    fun pick() {
        if (candles.size >= 80) { last = (60 until candles.size - expiry).random(); revealed = false; result = "" }
    }
    fun guess(call: Boolean) {
        val entry = candles[last].close
        val exit = candles[last + expiry].close
        val verdict = when {
            exit == entry -> "DRAW"
            (exit > entry) == call -> { wins++; "WIN" }
            else -> { losses++; "LOSS" }
        }
        result = "$verdict  (entry %.5f -> exit %.5f after $expiry candles)".format(entry, exit)
        revealed = true
    }
    LaunchedEffect(Unit) { candles = coordinator.candlesSnapshot(); pick() }

    ProCard("PRACTICE (no real or demo order - just a quiz)") {
        if (candles.size < 80 || last < 0) {
            Body("Need at least 80 stored candles. Run the monitor for a while first (have ${candles.size}).", ProDim)
        } else {
            Body("Guess where price is after $expiry more candles. The vertical line is your entry.", ProDim)
            val end = if (revealed) last + expiry else last
            val from = maxOf(0, end - 39)
            val view = candles.subList(from, end + 1)
            val hi = view.maxOf { it.high }
            val lo = view.minOf { it.low }
            val range = if (hi - lo > 0.0) hi - lo else 1.0
            Canvas(Modifier.fillMaxWidth().height(260.dp)) {
                val w = size.width / view.size
                fun y(p: Double): Float = ((hi - p) / range * size.height).toFloat()
                view.forEachIndexed { i, c ->
                    val x = i * w + w / 2f
                    val col = if (c.close >= c.open) ProGreen else ProRed
                    drawLine(col, Offset(x, y(c.high)), Offset(x, y(c.low)), strokeWidth = 2f)
                    val top = y(maxOf(c.open, c.close))
                    val bot = y(minOf(c.open, c.close))
                    drawRect(col, Offset(i * w + w * 0.2f, top), Size(w * 0.6f, maxOf(2f, bot - top)))
                }
                val ex = (last - from) * w + w
                drawLine(Color.White.copy(alpha = 0.5f), Offset(ex, 0f), Offset(ex, size.height), strokeWidth = 1f)
            }
            if (!revealed) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { guess(true) }) { Text("UP (CALL)") }
                    Button(onClick = { guess(false) }) { Text("DOWN (PUT)") }
                    OutlinedButton(onClick = { pick() }) { Text("SKIP") }
                }
            } else {
                Body(result, if (result.startsWith("WIN")) ProGreen else if (result.startsWith("LOSS")) ProRed else ProAmber)
                Button(onClick = { pick() }) { Text("NEXT") }
            }
            Body(StatLine("Your guesses", wins, losses).display(), ProDim)
            Body("Around 50% is chance. Do not read a skill into a small sample.", ProDim)
        }
    }
}

package com.jarvis.assistant.ui.screens.wingo

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.geometry.Offset
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
import com.jarvis.assistant.wingo.ScreenStatus
import com.jarvis.assistant.wingo.analysis.AccuracyWindow
import com.jarvis.assistant.wingo.analysis.BacktestReport
import com.jarvis.assistant.wingo.analysis.PerfStats
import com.jarvis.assistant.wingo.capture.WinGoCaptureConsentActivity
import com.jarvis.assistant.wingo.domain.Fmt
import com.jarvis.assistant.wingo.overlay.WinGoOverlayService
import com.jarvis.assistant.wingo.voice.WinGoNarrator

private val Cyan = Color(0xFF38BDF8)
private val Bg = Color(0xFF0A0E14)
private val CardBg = Color(0xFF0F1A24)
private val Muted = Color(0xFF94A3B8)
private val Good = Color(0xFF4ADE80)
private val Warn = Color(0xFFFBBF24)
private val Bad = Color(0xFFF87171)

@Composable
fun WinGoScreen(onBack: () -> Unit, vm: WinGoViewModel = viewModel()) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    val analytics by vm.analytics.collectAsState()
    val test by vm.test.collectAsState()
    val liveReport by vm.liveReport.collectAsState()
    val busy by vm.busy.collectAsState()

    var overlayAllowed by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var liveOn by remember { mutableStateOf(vm.liveAnalysisEnabled()) }
    var requireEdge by remember { mutableStateOf(vm.requireVerifiedEdge()) }
    val saved = remember { vm.savedRegion() }
    var regionTop by remember { mutableStateOf(saved?.top ?: 0.55f) }
    var regionBottom by remember { mutableStateOf(saved?.bottom ?: 0.95f) }
    var regionSaved by remember { mutableStateOf(saved != null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayAllowed = Settings.canDrawOverlays(context)
                vm.refreshAnalytics()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importCsv(uri)
    }
    val dataInfo by vm.dataInfo.collectAsState()
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
            Text("JARVIS WIN GO ANALYZER", color = Cyan, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 15.sp)
        }
        Text(
            "Analysis only. JARVIS never places bets, taps or controls the game. Past rounds do not guarantee future results, " +
                "and no accuracy is promised — only what is measured on real history is shown.",
            color = Muted, fontSize = 11.sp
        )

        Panel("AGENT STATUS") {
            StatusLine("Screen capture", if (state.captureReady) "ON" else "OFF", state.captureReady)
            StatusLine(
                "Game screen",
                when (state.screenStatus) {
                    ScreenStatus.TRACKING -> "DETECTED"
                    ScreenStatus.SEARCHING -> "SEARCHING"
                    ScreenStatus.NOT_DETECTED -> "NOT DETECTED"
                    ScreenStatus.NOT_STARTED -> "—"
                },
                state.screenStatus == ScreenStatus.TRACKING
            )
            StatusLine("Verified rounds stored", state.historyCount.toString(), state.historyCount >= 100)
            StatusLine("Uncertain readings skipped", state.uncertainReadings.toString(), true)
            StatusLine("Analysis engine", if (state.historyCount >= 100) "READY" else "WAITING FOR DATA", state.historyCount >= 100)
            state.message?.let { Text(it, color = Warn, fontSize = 11.sp) }
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
                        Button(onClick = { context.startActivity(Intent(context, WinGoCaptureConsentActivity::class.java)) }) { Text("Start monitoring") }
                    } else {
                        OutlinedButton(onClick = { vm.module.controls.stopMonitoring() }) { Text("Stop", color = Bad) }
                    }
                    OutlinedButton(onClick = { WinGoOverlayService.show(context) }) { Text("Show HUD", color = Cyan) }
                }
            }
            SetupStep("3. Open the WinGo game yourself (JARVIS never opens or taps it)", null) {}
            SetupStep("4. Auto-detect the history table", state.screenStatus == ScreenStatus.TRACKING) {}
            SetupStep("5. Adjust the history region if detection is off", regionSaved) {
                Text("Top ${Fmt.pct(regionTop.toDouble())}  ·  Bottom ${Fmt.pct(regionBottom.toDouble())}", color = Muted, fontSize = 11.sp)
                Slider(value = regionTop, onValueChange = { regionTop = it }, valueRange = 0f..0.95f)
                Slider(value = regionBottom, onValueChange = { regionBottom = it }, valueRange = 0.05f..1f)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        if (regionTop < regionBottom) {
                            vm.saveRegion(regionTop, regionBottom)
                            regionSaved = true
                        }
                    }) { Text("Use this region", color = Cyan) }
                    OutlinedButton(onClick = { vm.clearRegion(); regionSaved = false }) { Text("Auto-detect", color = Muted) }
                }
                Text("Restart monitoring after changing the region.", color = Muted, fontSize = 10.sp)
            }
            SetupStep("6. Collect verified results", state.historyCount > 0) {}
            SetupStep("7. Wait for at least 100 verified rounds", state.historyCount >= 100) {}
            SetupStep("8. Enable live analysis", liveOn) {
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

        val prediction = state.prediction
        Panel("NEXT ESTIMATE") {
            Text(WinGoNarrator.phaseText(state.phase), color = Cyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            if (prediction == null) {
                Text(state.message ?: "No prediction yet.", color = Muted, fontSize = 12.sp)
            } else {
                Text(
                    if (prediction.isSignal) (prediction.side?.name ?: "WAIT") else "WAIT",
                    color = if (prediction.isSignal) Cyan else Muted, fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
                )
                Text("Target period ${state.predictionPeriod ?: "—"}  ·  history ${prediction.historySize} verified rounds", color = Muted, fontSize = 11.sp)
                if (prediction.isSignal) {
                    Mono("Probability ${Fmt.pct(prediction.confidence)}  ·  Signal ${prediction.signal.name}")
                    Mono("Model agreement ${prediction.agree} / ${prediction.totalModels}")
                } else {
                    Text(prediction.waitReason ?: "WAIT — insufficient signal.", color = Warn, fontSize = 11.sp)
                    if (!prediction.edge.verified) Text("NO VERIFIED EDGE", color = Warn, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                prediction.pattern?.let {
                    Mono("Pattern ${it.context}  ·  ${it.occurrences} matches  ·  ${it.bigAfter} BIG / ${it.smallAfter} SMALL")
                } ?: prediction.patternNote?.let { Text(it, color = Muted, fontSize = 11.sp) }
                prediction.measuredNote?.let { Text(it, color = Warn, fontSize = 11.sp) }
            }
            state.lastVerification?.let { Mono(it.toText().replace("\n", "   ")) }
        }

        Panel("MEASURED PERFORMANCE (WALK-FORWARD)") {
            val bt = state.backtest
            if (bt == null) {
                Text("Not enough verified history yet.", color = Muted, fontSize = 11.sp)
            } else {
                Mono("Last 100: ${statsLine(bt.last100)}")
                Mono("Last 500: ${statsLine(bt.last500)}")
                Mono("All-time: ${statsLine(bt.allCalls)}")
                Text(bt.verdict, color = Warn, fontSize = 11.sp)
                Text("By model (all-time)", color = Cyan, fontSize = 11.sp)
                for (m in bt.modelStatuses) {
                    Mono("${m.name}: ${m.allTimeAccuracy?.let { Fmt.pct(it, 1) } ?: "–"} (${m.allTimeSamples})  w ${Fmt.num(m.weight, 2)}" + if (m.disabled) "  off" else "")
                }
            }
        }

        Panel("HISTORY PAGES AND MISSING ROUNDS") {
            Mono("Stored rounds: ${state.historyCount}")
            val pc = state.pageCurrent
            val pt = state.pageTotal
            if (pc != null && pt != null) Mono("History page on screen: $pc/$pt")
            Mono("Older rounds saved this session: ${state.backfilledSession}")
            Mono("Missing rounds: ${state.missingRounds}")
            for (g in state.gaps) {
                val range = if (g.count == 1) "…${g.firstMissing.takeLast(5)}" else "…${g.firstMissing.takeLast(5)} to …${g.lastMissing.takeLast(5)}"
                Text("• $range (${g.count})" + (g.pageHint?.let { " ≈ page $it" } ?: ""), color = Warn, fontSize = 11.sp)
            }
            Text(
                "Open Game history in the game and press ‹ page by page (stay about 2 seconds on each page, up to all 50). " +
                    "JARVIS saves every page it can read and pauses live signals while you browse. Rounds it could not read " +
                    "cleanly show up above as missing - look for those periods again. Go back to page 1 when you are done.",
                color = Muted, fontSize = 11.sp
            )
        }

        Panel("ANALYTICS (LIVE PREDICTIONS)") {
            val a = analytics
            if (a == null) {
                Text("Loading…", color = Muted, fontSize = 11.sp)
            } else {
                WindowRow("Today", a.today)
                WindowRow("Last 100", a.last100)
                WindowRow("Last 500", a.last500)
                Text("Accuracy by confidence (all leaning calls)", color = Cyan, fontSize = 11.sp)
                BarChart(
                    labels = a.bands.map { it.label },
                    values = a.bands.map { (it.accuracy ?: 0.0).toFloat() },
                    captions = a.bands.map { b -> b.accuracy?.let { Fmt.pct(it) } ?: "–" },
                    maxValue = 1f
                )
                Text("Big vs Small, last 100 verified rounds", color = Cyan, fontSize = 11.sp)
                BarChart(
                    labels = listOf("BIG", "SMALL"),
                    values = listOf(a.bigCount.toFloat(), a.smallCount.toFloat()),
                    captions = listOf(a.bigCount.toString(), a.smallCount.toString()),
                    maxValue = maxOf(1, a.bigCount, a.smallCount).toFloat()
                )
                Text("Accuracy over time (grey line = 50% coin flip)", color = Cyan, fontSize = 11.sp)
                LineChart(a.cumulativeAccuracy)
                if (a.agreement.isNotEmpty()) {
                    Text("Accuracy by number of agreeing models", color = Cyan, fontSize = 11.sp)
                    for ((agree, pair) in a.agreement) {
                        val acc = if (pair.first == 0) "–" else Fmt.pct(pair.second.toDouble() / pair.first, 1)
                        Mono("$agree models agree: $acc (${pair.first} calls)")
                    }
                }
                Text("Models (walk-forward, last 200 calls each)", color = Cyan, fontSize = 11.sp)
                for (m in a.modelStatuses) {
                    Mono("${m.name}: ${m.accuracy?.let { Fmt.pct(it, 1) } ?: "–"} (${m.samples})  weight ${Fmt.num(m.weight, 2)}")
                }
            }
            OutlinedButton(onClick = { vm.refreshAnalytics() }) { Text("Refresh", color = Cyan) }
        }

        Panel("BACKTEST ON STORED HISTORY") {
            OutlinedButton(enabled = !busy, onClick = { vm.runStoredBacktest() }) { Text(if (busy) "Running…" else "Run backtest", color = Cyan) }
            ReportText(liveReport ?: state.backtest)
        }

        Panel("TEST MODE (OFFLINE, SEPARATE FROM LIVE DATA)") {
            Text(test.info, color = Muted, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text("Import CSV", color = Cyan) }
                OutlinedButton(onClick = { vm.generateRandomControl() }) { Text("Random control", color = Cyan) }
            }
            Text("CSV columns: Period,Number,BigSmall,Color (last two optional).", color = Muted, fontSize = 10.sp)
            Button(enabled = test.results.isNotEmpty() && !test.running, onClick = { vm.runTestBacktest() }) {
                Text(if (test.running) "Running…" else "Start backtest")
            }
            ReportText(test.report)
        }

        Panel("DATA BACKUP") {
            Text(
                "Save every verified round to a file (choose Downloads). After reinstalling the app, restore it here " +
                    "and all your history comes back. Restoring only adds rounds that are missing.",
                color = Muted, fontSize = 11.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exportLauncher.launch("wingo_history.csv") }) { Text("Export data", color = Cyan) }
                OutlinedButton(onClick = { restoreLauncher.launch(arrayOf("*/*")) }) { Text("Restore data", color = Cyan) }
            }
            if (dataInfo.isNotBlank()) Text(dataInfo, color = Warn, fontSize = 11.sp)
            OutlinedButton(onClick = { vm.resetData() }) { Text("Delete all stored WinGo data", color = Bad) }
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
        val mark = when (done) { true -> "✓ "; false -> "○ "; null -> "• " }
        Text(mark + title, color = if (done == true) Good else Color.White, fontSize = 12.sp)
        content()
    }
}

@Composable
private fun WindowRow(name: String, window: AccuracyWindow) {
    Mono("$name  all: ${statsLine(window.allCalls)}")
    Mono("${" ".repeat(name.length)}  signalled: ${statsLine(window.signalled)}")
}

private fun statsLine(s: PerfStats): String {
    val acc = s.accuracy ?: return "no resolved calls"
    return "${Fmt.pct(acc, 1)} (${s.correct}/${s.calls}) streaks W${s.maxWinStreak}/L${s.maxLossStreak}"
}

@Composable
private fun ReportText(report: BacktestReport?) {
    if (report == null) return
    Text(report.toText(), color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
}

@Composable
private fun BarChart(labels: List<String>, values: List<Float>, captions: List<String>, maxValue: Float) {
    Row(
        Modifier.fillMaxWidth().height(110.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        values.forEachIndexed { i, v ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(captions.getOrElse(i) { "" }, color = Muted, fontSize = 9.sp)
                Box(Modifier.width(26.dp).height((70f * (v / maxValue).coerceIn(0f, 1f)).dp).background(Cyan))
                Text(labels.getOrElse(i) { "" }, color = Muted, fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun LineChart(values: List<Double>) {
    if (values.size < 2) {
        Text("Not enough resolved predictions yet.", color = Muted, fontSize = 10.sp)
        return
    }
    Canvas(Modifier.fillMaxWidth().height(90.dp)) {
        val w = size.width
        val h = size.height
        // 50% reference line
        drawLine(Muted, Offset(0f, h / 2f), Offset(w, h / 2f), strokeWidth = 1f)
        val low = 0.3f
        val high = 0.7f
        var previous: Offset? = null
        values.forEachIndexed { index, v ->
            val x = w * index / (values.size - 1)
            val y = h - h * ((v.toFloat() - low) / (high - low)).coerceIn(0f, 1f)
            val point = Offset(x, y)
            previous?.let { drawLine(Cyan, it, point, strokeWidth = 3f) }
            previous = point
        }
    }
}

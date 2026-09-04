package com.jarvis.assistant.ui.screens.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.hud.DeviceTelemetryRepository
import com.jarvis.assistant.hud.HudCommandExecutor
import com.jarvis.assistant.hud.JarvisHudState
import com.jarvis.assistant.hud.WallpaperEventBus
import com.jarvis.assistant.ui.AssistantViewModel
import com.jarvis.assistant.voice.VoiceState
import com.jarvis.assistant.ui.theme.JarvisCyan
import com.jarvis.assistant.ui.theme.JarvisError
import com.jarvis.assistant.ui.theme.JarvisSuccess
import com.jarvis.assistant.ui.theme.JarvisTextSecondary
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.CircleShape

@Composable
fun HomeScreen(viewModel: AssistantViewModel) {
    val voice by viewModel.voiceState.collectAsState()
    val status by viewModel.statusText.collectAsState()
    val response by viewModel.lastResponse.collectAsState()
    val pending by viewModel.pendingCommand.collectAsState()
    val plan by viewModel.pendingPlan.collectAsState()
    val hud by WallpaperEventBus.state.collectAsState()
    val telemetryRepo = remember { DeviceTelemetryRepository(viewModel.getApplication()) }
    val telemetry by telemetryRepo.telemetry.collectAsState()
    val scope = rememberCoroutineScope()
    DisposableEffect(telemetryRepo) {
        telemetryRepo.start(scope, 2000L)
        onDispose { telemetryRepo.stop() }
    }

    val voiceState = when (voice) {
        VoiceState.LISTENING -> JarvisHudState.LISTENING
        VoiceState.THINKING -> JarvisHudState.THINKING
        VoiceState.SPEAKING -> JarvisHudState.COMPLETED
        VoiceState.ERROR -> JarvisHudState.ERROR
        VoiceState.IDLE -> hud.state
    }

    Box(
        Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF02070D))
    ) {
        HudBackground()
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("JARVIS", color = JarvisCyan, fontSize = 28.sp, fontFamily = FontFamily.Monospace)
                    Text("PERSONAL AI CONTROL SYSTEM", color = JarvisTextSecondary, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                }
                Text(
                    text = "● ${voiceState.name}",
                    color = if (voiceState == JarvisHudState.ERROR) JarvisError else JarvisCyan,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(Modifier.height(12.dp))
            TelemetryStrip(telemetry)

            Spacer(Modifier.height(8.dp))
            JarvisCore(
                state = voiceState,
                modifier = Modifier.size(260.dp).clickable {
                    if (voice == VoiceState.LISTENING) viewModel.cancelListening()
                    else viewModel.startListening(null)
                }
            )

            Text(status.uppercase(), color = JarvisCyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            if (response.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = androidx.compose.ui.graphics.Color(0xCC06131C),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(response, color = JarvisCyan, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(12.dp))
                }
            }

            Spacer(Modifier.height(10.dp))
            QuickCommandGrid(viewModel)
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                HudChip("PHONE CONTROL", JarvisAccessibilityService.isEnabled)
                HudChip("HUD", true)
                HudChip("AI", true)
            }
        }
    }

    pending?.let {
        AlertDialog(
            onDismissRequest = { viewModel.confirmPendingCommand(false) },
            title = { Text("JARVIS CONFIRMATION") },
            text = { Text(it.confirmationText) },
            confirmButton = { TextButton(onClick = { viewModel.confirmPendingCommand(true) }) { Text("EXECUTE") } },
            dismissButton = { TextButton(onClick = { viewModel.confirmPendingCommand(false) }) { Text("CANCEL") } }
        )
    }
    plan?.let {
        AlertDialog(
            onDismissRequest = { viewModel.confirmPendingPlan(false) },
            title = { Text("MULTI-STEP COMMAND") },
            text = { Text(it.confirmationText) },
            confirmButton = { TextButton(onClick = { viewModel.confirmPendingPlan(true) }) { Text("EXECUTE") } },
            dismissButton = { TextButton(onClick = { viewModel.confirmPendingPlan(false) }) { Text("CANCEL") } }
        )
    }
}

@Composable
private fun JarvisCore(state: JarvisHudState, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "jarvis-core")
    val angle by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(if (state == JarvisHudState.LISTENING) 1100 else 2600, easing = LinearEasing), RepeatMode.Restart),
        label = "rotation"
    )
    Canvas(modifier) {
        val c = center
        val r = size.minDimension * .38f
        drawCircle(JarvisCyan.copy(alpha = .05f), r * 1.8f)
        drawCircle(JarvisCyan.copy(alpha = .16f), r * 1.25f, style = Stroke(2f))
        for (i in 0..3) {
            val rr = r * (0.65f + i * .22f)
            drawArc(JarvisCyan.copy(alpha = .7f - i * .1f), angle * if (i % 2 == 0) 1 else -1, 260f, false, style = Stroke(if (i == 1) 3f else 1.5f, cap = StrokeCap.Round), topLeft = androidx.compose.ui.geometry.Offset(c.x - rr, c.y - rr), size = androidx.compose.ui.geometry.Size(rr * 2, rr * 2))
        }
        val pulse = if (state == JarvisHudState.ERROR) .25f else .12f
        drawCircle(JarvisCyan.copy(alpha = pulse), r * .55f)
        drawCircle(JarvisCyan, r * .24f)
        drawCircle(androidx.compose.ui.graphics.Color.White, r * .08f)
    }
}

@Composable
private fun HudBackground() {
    Canvas(Modifier.fillMaxSize()) {
        val step = size.minDimension / 12f
        for (x in 0..12) drawLine(JarvisCyan.copy(alpha = .035f), androidx.compose.ui.geometry.Offset(x * step, 0f), androidx.compose.ui.geometry.Offset(x * step, size.height))
        for (y in 0..24) drawLine(JarvisCyan.copy(alpha = .025f), androidx.compose.ui.geometry.Offset(0f, y * step), androidx.compose.ui.geometry.Offset(size.width, y * step))
        val l = 28f
        val s = 1.5f
        drawLine(JarvisCyan.copy(alpha=.45f), androidx.compose.ui.geometry.Offset(10f,10f), androidx.compose.ui.geometry.Offset(10f+l,10f), s)
        drawLine(JarvisCyan.copy(alpha=.45f), androidx.compose.ui.geometry.Offset(10f,10f), androidx.compose.ui.geometry.Offset(10f,10f+l), s)
        drawLine(JarvisCyan.copy(alpha=.45f), androidx.compose.ui.geometry.Offset(size.width-10f,10f), androidx.compose.ui.geometry.Offset(size.width-10f-l,10f), s)
        drawLine(JarvisCyan.copy(alpha=.45f), androidx.compose.ui.geometry.Offset(size.width-10f,10f), androidx.compose.ui.geometry.Offset(size.width-10f,10f+l), s)
    }
}

@Composable
private fun TelemetryStrip(t: com.jarvis.assistant.hud.DeviceTelemetry) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        TelemetryCell("BAT", t.batteryPercent?.let { "$it%" } ?: "UNAV", Modifier.weight(1f))
        TelemetryCell("RAM", if (t.ramUsedGb != null && t.ramTotalGb != null) "${"%.1f".format(t.ramUsedGb)}/${"%.1f".format(t.ramTotalGb)}" else "UNAV", Modifier.weight(1f))
        TelemetryCell("NET", when(t.networkConnected){true->"ON";false->"OFF";null->"UNAV"}, Modifier.weight(1f))
        TelemetryCell("WIFI", when(t.wifiConnected){true->"ON";false->"OFF";null->"UNAV"}, Modifier.weight(1f))
    }
}

@Composable
private fun TelemetryCell(label: String, value: String, modifier: Modifier) {
    Surface(color = androidx.compose.ui.graphics.Color(0x660A1A23), shape = RoundedCornerShape(2.dp), modifier = modifier) {
        Column(Modifier.padding(6.dp)) {
            Text(label, color = JarvisTextSecondary, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
            Text(value, color = JarvisCyan, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun QuickCommandGrid(viewModel: AssistantViewModel) {
    val commands = listOf("ACTIVATE HUD", "SYSTEM STATUS", "SHOW BATTERY", "SHOW NETWORK")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        commands.forEach { label ->
            Surface(
                color = androidx.compose.ui.graphics.Color(0x55091A23),
                shape = RoundedCornerShape(2.dp),
                modifier = Modifier.weight(1f).clickable {
                    viewModel.sendMessage(
                        when(label) {
                            "ACTIVATE HUD" -> "activate hud"
                            "SYSTEM STATUS" -> "show system status"
                            "SHOW BATTERY" -> "show battery"
                            else -> "show network"
                        }, true
                    )
                }
            ) {
                Text(label, color = JarvisCyan, fontSize = 8.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(7.dp))
            }
        }
    }
}

@Composable
private fun HudChip(label: String, online: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(if (online) JarvisSuccess else JarvisError, CircleShape))
        Spacer(Modifier.width(5.dp))
        Text(label, color = JarvisTextSecondary, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
    }
}

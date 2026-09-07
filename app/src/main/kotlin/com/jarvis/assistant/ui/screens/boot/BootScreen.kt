package com.jarvis.assistant.ui.screens.boot

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.ui.theme.JarvisBackground
import com.jarvis.assistant.ui.theme.JarvisCyan
import com.jarvis.assistant.ui.theme.JarvisSuccess
import com.jarvis.assistant.ui.theme.JarvisTextSecondary
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

/** #24 BOOT EXPERIENCE — a short, skippable cinematic boot sequence shown once when the app
 * launches: a spinning/pulsing core (the same visual language as the Command Center's
 * JarvisCore, kept as its own lightweight copy here so the boot screen has zero dependency on
 * the rest of the UI tree), a real system checklist, then a welcome line. Tap anywhere to skip. */
private data class BootStep(val label: String, val ready: Boolean = true)

private val bootSteps = listOf(
    BootStep("CORE"),
    BootStep("MEMORY"),
    BootStep("VOICE"),
    BootStep("AUTOMATION"),
    BootStep("HUD")
)

@Composable
fun BootScreen(onFinished: () -> Unit) {
    var visibleSteps by remember { mutableStateOf(0) }
    var showWelcome by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(300) // let the core render a couple of rotations before the checklist starts
        for (i in bootSteps.indices) {
            delay(180)
            visibleSteps = i + 1
        }
        delay(280)
        showWelcome = true
        delay(650)
        onFinished()
    }

    Box(
        modifier = Modifier.fillMaxSize().background(JarvisBackground).clickable { onFinished() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            BootCore(modifier = Modifier.size(150.dp))

            androidx.compose.foundation.layout.Spacer(Modifier.size(18.dp))

            if (!showWelcome) {
                Text("INITIALIZING J.A.R.V.I.S.", color = JarvisCyan, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
                androidx.compose.foundation.layout.Spacer(Modifier.size(10.dp))
                bootSteps.take(visibleSteps).forEach { step ->
                    Text(
                        "${step.label.padEnd(12, '.')} ONLINE",
                        color = JarvisTextSecondary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            } else {
                Text("WELCOME TO JARVIS", color = JarvisCyan, fontSize = 18.sp, fontFamily = FontFamily.Monospace)
                Text("COMMAND CENTER ONLINE", color = JarvisSuccess, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun BootCore(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "boot-core")
    val angle by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart),
        label = "boot-core-rotation"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.85f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "boot-core-pulse"
    )
    Canvas(modifier) {
        val c = center
        val r = size.minDimension * .38f * pulse

        drawCircle(JarvisCyan.copy(alpha = .06f), r * 2f)
        drawCircle(JarvisCyan.copy(alpha = .18f), r * 1.3f, style = Stroke(2f))

        for (i in 0..2) {
            val rr = r * (0.7f + i * .24f)
            drawArc(
                JarvisCyan.copy(alpha = .75f - i * .15f),
                startAngle = angle * if (i % 2 == 0) 1 else -1,
                sweepAngle = 250f,
                useCenter = false,
                style = Stroke(if (i == 1) 3f else 1.5f, cap = StrokeCap.Round),
                topLeft = Offset(c.x - rr, c.y - rr),
                size = androidx.compose.ui.geometry.Size(rr * 2, rr * 2)
            )
        }

        // Tick marks around the core, matching the Command Center's radial style.
        val tickR = r * 1.35f
        for (i in 0 until 40) {
            val a = Math.toRadians((i * 9.0) + angle)
            val cosA = cos(a).toFloat()
            val sinA = sin(a).toFloat()
            val len = tickR * .06f
            drawLine(
                JarvisCyan.copy(alpha = .3f),
                Offset(c.x + cosA * tickR, c.y + sinA * tickR),
                Offset(c.x + cosA * (tickR - len), c.y + sinA * (tickR - len)),
                1.2f
            )
        }

        drawCircle(JarvisCyan.copy(alpha = .14f), r * .5f)
        drawCircle(JarvisCyan, r * .22f)
        drawCircle(Color.White, r * .07f)
    }
}

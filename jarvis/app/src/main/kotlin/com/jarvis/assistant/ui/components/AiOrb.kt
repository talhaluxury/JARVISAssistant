package com.jarvis.assistant.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.ui.theme.JarvisCyan
import com.jarvis.assistant.ui.theme.JarvisPurpleGlow
import com.jarvis.assistant.voice.VoiceState
import kotlin.math.sin

@Composable
fun AiOrb(state: VoiceState, modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb")

    val speed = when (state) {
        VoiceState.LISTENING -> 1400
        VoiceState.THINKING -> 900
        VoiceState.SPEAKING -> 700
        VoiceState.ERROR -> 2000
        VoiceState.IDLE -> 3000
    }

    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(speed, easing = LinearEasing), RepeatMode.Restart),
        label = "phase"
    )

    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(speed, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse"
    )

    val color = if (state == VoiceState.ERROR) JarvisPurpleGlow else JarvisCyan

    Canvas(modifier = modifier.size(220.dp)) {
        val center = Offset(size.width / 2, size.height / 2)
        val baseRadius = size.minDimension / 3.2f

        // outer glow rings
        for (i in 1..3) {
            val ringRadius = baseRadius * (1f + i * 0.18f) * pulse
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = 0.10f / i), color.copy(alpha = 0f)),
                    center = center,
                    radius = ringRadius
                ),
                radius = ringRadius,
                center = center
            )
        }

        // core orb
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = 0.95f), color.copy(alpha = 0.35f)),
                center = center,
                radius = baseRadius * pulse
            ),
            radius = baseRadius * pulse,
            center = center
        )

        // rotating ring stroke, denser while listening/speaking to read as "activity"
        val segments = if (state == VoiceState.IDLE) 24 else 40
        for (s in 0 until segments) {
            val angle = phase + (2 * Math.PI * s / segments)
            val wobble = sin(angle * 3) * 6f
            val r = baseRadius * pulse + 18f + wobble
            val x = center.x + (r * kotlin.math.cos(angle)).toFloat()
            val y = center.y + (r * kotlin.math.sin(angle)).toFloat()
            drawCircle(
                color = color.copy(alpha = 0.5f),
                radius = 2.5f,
                center = Offset(x, y)
            )
        }

        drawCircle(
            color = color,
            radius = baseRadius * pulse,
            center = center,
            style = Stroke(width = 2f)
        )
    }
}

package com.jarvis.assistant.ui.screens.boot

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.ui.theme.JarvisBackground
import com.jarvis.assistant.ui.theme.JarvisCyan
import com.jarvis.assistant.ui.theme.JarvisTextSecondary
import kotlinx.coroutines.delay

private val bootLines = listOf(
    "INITIALIZING J.A.R.V.I.S.",
    "LOADING AI CORE...",
    "CONNECTING SYSTEM SERVICES...",
    "VOICE SYSTEM ONLINE",
    "ACCESSIBILITY SYSTEM ONLINE",
    "NETWORK STATUS CHECK..."
)

/** Short, skippable cinematic boot sequence shown once when the app launches. */
@Composable
fun BootScreen(onFinished: () -> Unit) {
    var visibleLines by remember { mutableStateOf(0) }
    var showFinalStatus by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        for (i in bootLines.indices) {
            delay(220)
            visibleLines = i + 1
        }
        delay(300)
        showFinalStatus = true
        delay(500)
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(JarvisBackground)
            .clickable { onFinished() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            bootLines.take(visibleLines).forEach { line ->
                Text(
                    line,
                    color = JarvisTextSecondary,
                    fontSize = 13.sp,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            if (showFinalStatus) {
                Text(
                    "J.A.R.V.I.S. ONLINE",
                    color = JarvisCyan,
                    fontSize = 22.sp,
                    style = MaterialTheme.typography.headlineSmall
                )
            }
        }
    }
}

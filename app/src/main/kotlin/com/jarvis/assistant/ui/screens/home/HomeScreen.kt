package com.jarvis.assistant.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.ui.AssistantViewModel
import com.jarvis.assistant.ui.components.AiOrb
import com.jarvis.assistant.ui.theme.JarvisCyan
import com.jarvis.assistant.ui.theme.JarvisSurfaceGlass
import com.jarvis.assistant.ui.theme.JarvisTextSecondary
import com.jarvis.assistant.voice.VoiceState

private val quickCommands = listOf(
    "What's on my calendar?",
    "Open YouTube",
    "Set a timer for 5 minutes",
    "Aaj ka mausam kaisa hai?"
)

@Composable
fun HomeScreen(viewModel: AssistantViewModel) {
    val voiceState by viewModel.voiceState.collectAsState()
    val statusText by viewModel.statusText.collectAsState()
    val lastResponse by viewModel.lastResponse.collectAsState()
    val isOffline by viewModel.isOffline.collectAsState()
    val pendingCommand by viewModel.pendingCommand.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "JARVIS",
                style = MaterialTheme.typography.headlineLarge,
                color = JarvisCyan
            )
            Spacer(Modifier.height(4.dp))
            if (isOffline) {
                Text("Offline mode", color = JarvisTextSecondary, fontSize = 13.sp)
            } else {
                Text("How can I help you?", color = JarvisTextSecondary, fontSize = 15.sp)
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AiOrb(state = voiceState)
            Spacer(Modifier.height(20.dp))
            Text(
                text = statusText,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium
            )
            if (lastResponse.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    color = JarvisSurfaceGlass,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        lastResponse,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(
                onClick = {
                    if (voiceState == VoiceState.LISTENING) {
                        viewModel.cancelListening()
                    } else {
                        viewModel.startListening(languageTag = null)
                    }
                },
                modifier = Modifier
                    .size(76.dp)
                    .background(
                        color = if (voiceState == VoiceState.LISTENING) JarvisCyan else JarvisSurfaceGlass,
                        shape = CircleShape
                    )
            ) {
                Icon(
                    imageVector = if (voiceState == VoiceState.LISTENING) Icons.Filled.MicOff else Icons.Filled.Mic,
                    contentDescription = "Microphone",
                    tint = if (voiceState == VoiceState.LISTENING) Color.Black else JarvisCyan,
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(Modifier.height(20.dp))

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) {
                items(quickCommands) { command ->
                    Surface(
                        color = JarvisSurfaceGlass,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.clickable { viewModel.sendMessage(command, speakReply = true) }
                    ) {
                        Text(
                            command,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }

    pendingCommand?.let { pending ->
        AlertDialog(
            onDismissRequest = { viewModel.confirmPendingCommand(false) },
            title = { Text("Confirm action") },
            text = { Text(pending.confirmationText) },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmPendingCommand(true) }) { Text("Yes") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.confirmPendingCommand(false) }) { Text("Cancel") }
            }
        )
    }
}

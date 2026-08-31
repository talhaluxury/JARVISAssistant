package com.jarvis.assistant.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jarvis.assistant.ui.theme.JarvisCyan
import com.jarvis.assistant.ui.theme.JarvisError
import com.jarvis.assistant.ui.theme.JarvisTextSecondary

private val languages = listOf("auto" to "Auto-detect", "en" to "English", "ur" to "Urdu", "ur-roman" to "Roman Urdu")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    var clearMemoryDialog by remember { mutableStateOf(false) }
    var clearHistoryDialog by remember { mutableStateOf(false) }
    var languageMenuExpanded by remember { mutableStateOf(false) }
    var voiceMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refreshStatus() }

    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        TopAppBar(title = { Text("Settings") })

        SectionLabel("System Status")
        StatusRow("AI Connection", state.aiConfigured)
        StatusRow("Phone Control (Accessibility)", state.phoneControlEnabled)
        StatusRow("Network", state.networkOnline)

        SectionLabel("AI Provider")
        OutlinedTextField(
            value = state.apiKey,
            onValueChange = viewModel::updateApiKey,
            label = { Text("API key") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
        )
        Text(
            "Stored encrypted on this device only (Android Keystore). Never bundled in the app.",
            color = JarvisTextSecondary,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        OutlinedTextField(
            value = state.baseUrl,
            onValueChange = viewModel::updateBaseUrl,
            label = { Text("API base URL (optional, default: OpenAI)") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
        )
        OutlinedTextField(
            value = state.model,
            onValueChange = viewModel::updateModel,
            label = { Text("Model") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
        )

        SectionLabel("Web Search")
        OutlinedTextField(
            value = state.searchApiKey,
            onValueChange = viewModel::updateSearchApiKey,
            label = { Text("Search API key (Brave Search)") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
        )

        SectionLabel("Voice")
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Speech speed: ${"%.1f".format(state.speechRate)}x")
        }
        Slider(
            value = state.speechRate,
            onValueChange = viewModel::updateSpeechRate,
            valueRange = 0.5f..2.0f,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        )
        Row(modifier = Modifier.padding(horizontal = 16.dp)) {
            OutlinedButton(onClick = { voiceMenuExpanded = true }) {
                Text(state.voices.firstOrNull { it.name == state.voiceName }?.displayLabel ?: "Select voice")
            }
            DropdownMenu(expanded = voiceMenuExpanded, onDismissRequest = { voiceMenuExpanded = false }) {
                state.voices.forEach { voice ->
                    DropdownMenuItem(text = { Text(voice.displayLabel) }, onClick = {
                        viewModel.updateVoice(voice.name)
                        voiceMenuExpanded = false
                    })
                }
            }
        }
        OutlinedButton(
            onClick = { viewModel.testVoice() },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
        ) { Text("Test voice") }

        SectionLabel("Language")
        Row(modifier = Modifier.padding(horizontal = 16.dp)) {
            OutlinedButton(onClick = { languageMenuExpanded = true }) {
                Text(languages.firstOrNull { it.first == state.language }?.second ?: "Auto-detect")
            }
            DropdownMenu(expanded = languageMenuExpanded, onDismissRequest = { languageMenuExpanded = false }) {
                languages.forEach { (code, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = {
                        viewModel.updateLanguage(code)
                        languageMenuExpanded = false
                    })
                }
            }
        }

        SectionLabel("Wake Word")
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("\"Hey JARVIS\" (experimental)")
                Text(
                    "Continuous background listening isn't reliable on modern Android without a " +
                        "foreground service and a persistent notification. Enabling this switches to " +
                        "a foreground listening mode with a visible mic indicator instead of silent " +
                        "background recording.",
                    color = JarvisTextSecondary,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Switch(checked = state.wakeWordEnabled, onCheckedChange = viewModel::updateWakeWord)
        }

        SectionLabel("Privacy & Data")
        Text(
            "JARVIS only listens when you tap the microphone button — there is no silent or " +
                "background recording. Contacts, messages, and notifications are never read unless " +
                "you explicitly grant that permission for a specific action. Memories are stored " +
                "only on this device and only when you ask JARVIS to remember something.",
            color = JarvisTextSecondary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp)
        )
        OutlinedButton(
            onClick = { clearMemoryDialog = true },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
        ) { Text("Clear all memory") }
        OutlinedButton(
            onClick = { clearHistoryDialog = true },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
        ) { Text("Clear all chat history") }

        SectionLabel("About")
        Text(
            "JARVIS personal assistant. Built to run entirely under your control — your API keys, " +
                "your device, your data.",
            color = JarvisTextSecondary,
            modifier = Modifier.padding(16.dp)
        )
    }

    if (clearMemoryDialog) {
        AlertDialog(
            onDismissRequest = { clearMemoryDialog = false },
            title = { Text("Clear all memory?") },
            text = { Text("This can't be undone.") },
            confirmButton = { TextButton(onClick = { viewModel.clearMemory(); clearMemoryDialog = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { clearMemoryDialog = false }) { Text("Cancel") } }
        )
    }
    if (clearHistoryDialog) {
        AlertDialog(
            onDismissRequest = { clearHistoryDialog = false },
            title = { Text("Clear all history?") },
            text = { Text("This can't be undone.") },
            confirmButton = { TextButton(onClick = { viewModel.clearHistory(); clearHistoryDialog = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { clearHistoryDialog = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp)
    )
}

@Composable
private fun StatusRow(label: String, online: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            if (online) "ONLINE" else "OFFLINE",
            color = if (online) JarvisCyan else JarvisError,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

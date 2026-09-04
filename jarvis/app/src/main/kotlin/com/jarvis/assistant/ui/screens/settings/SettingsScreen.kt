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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jarvis.assistant.ui.theme.JarvisCyan
import com.jarvis.assistant.ui.theme.JarvisError
import com.jarvis.assistant.ui.theme.JarvisTextSecondary
import com.jarvis.assistant.hud.HudMode

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

    // Accessibility (and battery-exemption) status only ever changes while the user is away in
    // system Settings, so re-check every time this screen comes back into the foreground —
    // otherwise "Phone Control: OFFLINE" can keep showing stale even after the user fixed it.
    val currentViewModel = rememberUpdatedState(viewModel)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) currentViewModel.value.refreshStatus()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        TopAppBar(title = { Text("Settings") })

        SectionLabel("System Status")
        StatusRow("AI Connection", state.aiConfigured)
        StatusRow("Phone Control (Accessibility)", state.phoneControlEnabled)
        StatusRow("Notification Access", state.notificationAccessEnabled)
        if (!state.notificationAccessEnabled) {
            Text(
                "Optional: allows JARVIS to summarize active notifications. Android keeps this behind a separate system-level access switch.",
                color = JarvisTextSecondary,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
            OutlinedButton(
                onClick = { viewModel.openNotificationAccessSettings() },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            ) { Text("Enable Notification Access") }
        }
        if (!state.phoneControlEnabled) {
            Text(
                "OFF means JARVIS cannot tap, type, or read the screen inside any other app " +
                    "(YouTube, WhatsApp, etc.) — only Android itself can turn this on, for your " +
                    "security. Tap below, find JARVIS in the list, and turn it on there.",
                color = JarvisTextSecondary,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
            OutlinedButton(
                onClick = { viewModel.openAccessibilitySettings() },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            ) { Text("Enable Phone Control in Android Settings") }
        }
        OutlinedButton(
            onClick = { viewModel.openBatteryOptimizationSettings() },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
        ) { Text("Allow JARVIS to run unrestricted in background") }
        Text(
            "On some phones (Xiaomi/MIUI, Oppo, Vivo, OnePlus, Samsung) this second step is the " +
                "one that actually matters — without it, Android can silently stop JARVIS a few " +
                "minutes after the screen turns off even if Phone Control shows ONLINE above.",
            color = JarvisTextSecondary,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
        )
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
                Text("\"Hey JARVIS\" — hands-free")
                Text(
                    "Say \"Jarvis\" anytime and JARVIS listens for what follows — no tap needed. " +
                        "There is no floating microphone on top of other apps. A persistent foreground " +
                        "notification is required by Android while the background microphone service runs.",
                    color = JarvisTextSecondary,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Switch(checked = state.wakeWordEnabled, onCheckedChange = viewModel::updateWakeWord)
        }

        SectionLabel("Automation")
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Confirm before every action")
                Text(
                    "Off by default for low-risk actions (opening apps, going home/back). Turn " +
                        "this on to have JARVIS ask yes/no before doing anything at all.",
                    color = JarvisTextSecondary,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Switch(checked = state.confirmEveryAction, onCheckedChange = viewModel::updateConfirmEveryAction)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Screen automation")
                Text(
                    "Lets JARVIS tap, type, scroll, and search inside other apps (WhatsApp, " +
                        "YouTube, etc.). Turn this off to restrict JARVIS to opening apps and basic " +
                        "navigation (back/home/recents) only.",
                    color = JarvisTextSecondary,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Switch(checked = state.screenAutomationEnabled, onCheckedChange = viewModel::updateScreenAutomationEnabled)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Background JARVIS (hands-free)")
                Text(
                    "Runs JARVIS in the foreground service with automatic wake-word listening. " +
                        "No floating microphone appears over other apps. The live wallpaper is the " +
                        "visual status indicator when it is set as your wallpaper.",
                    color = JarvisTextSecondary,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Switch(checked = state.backgroundJarvisEnabled, onCheckedChange = viewModel::setBackgroundJarvisEnabled)
        }

        SectionLabel("Home Screen")
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Live wallpaper")
                Text(
                    "A glowing orb behind your home screen icons that pulses and changes color " +
                        "while JARVIS listens, thinks, or speaks — pure visual, it doesn't use the " +
                        "mic itself. Opens Android's own wallpaper picker; you still tap \"Set " +
                        "wallpaper\" there to confirm.",
                    color = JarvisTextSecondary,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        OutlinedButton(
            onClick = { viewModel.openLiveWallpaperPicker() },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Text("Set as live wallpaper")
        }

        SectionLabel("JARVIS HUD CONTROL")
        Text(
            "The live wallpaper is a real WallpaperService. These controls change its renderer without touching the microphone or bypassing Android security.",
            color = JarvisTextSecondary,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(HudMode.AUTO, HudMode.FULL, HudMode.MINIMAL, HudMode.STANDBY).forEach { mode ->
                OutlinedButton(onClick = { viewModel.setHudMode(mode) }) {
                    Text(mode.name)
                }
            }
        }
        Text("Brightness", modifier = Modifier.padding(horizontal = 16.dp))
        Slider(
            value = state.hudSettings.brightness,
            onValueChange = viewModel::updateHudBrightness,
            valueRange = .1f..1f,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        )
        Text("Animation intensity", modifier = Modifier.padding(horizontal = 16.dp))
        Slider(
            value = state.hudSettings.animationIntensity,
            onValueChange = viewModel::updateHudIntensity,
            valueRange = .25f..1.5f,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        )
        Text("Animation speed", modifier = Modifier.padding(horizontal = 16.dp))
        Slider(
            value = state.hudSettings.animationSpeed,
            onValueChange = viewModel::updateHudSpeed,
            valueRange = .25f..2f,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        )
        HudSwitch("Show clock", state.hudSettings.showClock) { viewModel.updateHudVisible("clock", it) }
        HudSwitch("Show battery", state.hudSettings.showBattery) { viewModel.updateHudVisible("battery", it) }
        HudSwitch("Show RAM", state.hudSettings.showRam) { viewModel.updateHudVisible("ram", it) }
        HudSwitch("Show storage", state.hudSettings.showStorage) { viewModel.updateHudVisible("storage", it) }
        HudSwitch("Show network", state.hudSettings.showNetwork) { viewModel.updateHudVisible("network", it) }
        HudSwitch("Show notifications", state.hudSettings.showNotifications) { viewModel.updateHudVisible("notifications", it) }
        HudSwitch("Show device info", state.hudSettings.showDevice) { viewModel.updateHudVisible("device", it) }
        HudSwitch("Power-saving renderer", state.hudSettings.powerSaving) { viewModel.updateHudPowerSaving(it) }

        SectionLabel("Privacy & Data")
        Text(
            (if (state.wakeWordEnabled)
                "Wake word is ON: JARVIS listens continuously for the word \"Jarvis\" while the " +
                    "background service is running (shown by the persistent notification), and only " +
                    "sends what you say afterward to the AI provider. Turn Wake Word off in the " +
                    "section above to go back to tap-to-talk only. "
            else
                "JARVIS only listens when you tap the microphone button (in-app or the floating " +
                    "bubble, if enabled) — it does not transcribe or store anything otherwise. ") +
                "While JARVIS is speaking, it briefly checks the microphone's volume level only, not " +
                "what's said, so it can stop and listen if you start talking over it; that check " +
                "isn't recorded or sent anywhere. Contacts, messages, and notifications are never " +
                "read unless you explicitly grant that permission for a specific action. Memories " +
                "are stored only on this device and only when you ask JARVIS to remember something.",
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
private fun HudSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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

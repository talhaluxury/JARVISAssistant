package com.jarvis.assistant.remote

import com.jarvis.assistant.BuildConfig
import com.jarvis.assistant.JarvisApplication
import com.jarvis.assistant.agent.AgentEvent
import com.jarvis.assistant.command.describe
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.jarvis.assistant.security.AppLock
import kotlinx.coroutines.launch
import com.jarvis.assistant.ui.theme.JarvisCyan
import com.jarvis.assistant.ui.theme.JarvisTheme

class RemoteControlActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { JarvisTheme { RemoteScreen() } }
    }

    private fun directLink(id: String): String {
        val base = BuildConfig.DEFAULT_REMOTE_RELAY_URL.trimEnd('/')
        return if (base.isBlank()) "" else "$base/?code=$id"
    }

    private fun copyToClipboard(context: Context, label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    @Composable
    private fun RemoteScreen() {
        val context = LocalContext.current
        var started by remember { mutableStateOf(false) }
        var serverConnected by remember { mutableStateOf(false) }
        var connected by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf("") }
        var copiedMsg by remember { mutableStateOf("") }
        // Stable across restarts/reconnects - this is what makes a saved link on
        // Phone 2 work every time, instead of a fresh random code each session.
        val deviceId = remember { DeviceIdentity.get(context) }

        DisposableEffect(Unit) {
            val poll = object : Runnable {
                override fun run() {
                    serverConnected = RemoteRelayClient.connectedToServer
                    connected = RemoteRelayClient.controllerConnected
                    handler.postDelayed(this, 500)
                }
            }
            handler.post(poll)
            onDispose { handler.removeCallbacksAndMessages(null) }
        }

        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val intent = Intent(this@RemoteControlActivity, RemoteScreenService::class.java)
                    .putExtra(RemoteScreenService.EXTRA_RESULT_CODE, result.resultCode)
                    .putExtra(RemoteScreenService.EXTRA_DATA, result.data)
                try {
                    ContextCompat.startForegroundService(this@RemoteControlActivity, intent)
                    started = true
                    error = ""
                } catch (e: Exception) {
                    error = "Could not start remote service: ${e.message ?: "unknown error"}"
                }
            } else error = "Screen-sharing permission was cancelled."
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("JARVIS REMOTE CONTROL", color = JarvisCyan, fontSize = 22.sp)
            Spacer(Modifier.height(20.dp))
            Text("This phone's pairing code", fontSize = 13.sp)
            Text(deviceId, fontSize = 26.sp, color = JarvisCyan)
            Spacer(Modifier.height(8.dp))
            Text(
                "This code never changes. Bookmark the link below on Phone 2 once " +
                    "and it will keep working, even after restarts or reconnects.",
                fontSize = 11.sp
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                val link = directLink(deviceId)
                copiedMsg = if (link.isNotBlank()) {
                    copyToClipboard(context, "JARVIS link", link)
                    "Link copied - open it in a browser on Phone 2"
                } else {
                    "Relay URL not configured in this APK - can't build a link"
                }
            }) { Text("COPY DIRECT LINK FOR PHONE 2") }
            if (copiedMsg.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text(copiedMsg, fontSize = 12.sp) }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = {
                val base = BuildConfig.DEFAULT_REMOTE_RELAY_URL.trimEnd('/')
                copiedMsg = if (base.isNotBlank()) {
                    copyToClipboard(context, "JARVIS relay URL", base)
                    "Website link copied (paste it, then type the code)"
                } else {
                    "Relay URL not configured in this APK"
                }
            }) { Text("COPY WEBSITE LINK (NO CODE)") }
            if (BuildConfig.DEFAULT_REMOTE_RELAY_URL.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(BuildConfig.DEFAULT_REMOTE_RELAY_URL, fontSize = 11.sp)
            }
            Spacer(Modifier.height(20.dp))
            if (!started) {
                Text("Internet relay + live screen + touch control")
                Spacer(Modifier.height(12.dp))
                Text("Relay: ${if (BuildConfig.DEFAULT_REMOTE_RELAY_URL.isBlank()) "NOT CONFIGURED" else "configured"}")
                Spacer(Modifier.height(20.dp))
                Button(onClick = {
                    if (BuildConfig.DEFAULT_REMOTE_RELAY_URL.isBlank()) {
                        error = "Set REMOTE_RELAY_URL in GitHub Actions and rebuild the APK."
                    } else {
                        launcher.launch((getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).createScreenCaptureIntent())
                    }
                }) { Text("START + SHARE SCREEN") }
            } else {
                Text(if (serverConnected) "Phone 1: online (connected to server)" else "Phone 1: reconnecting to server…")
                Spacer(Modifier.height(10.dp))
                Text(if (connected) "Phone 2: CONNECTED" else "Phone 2: waiting…")
                Spacer(Modifier.height(20.dp))
                Button(onClick = {
                    stopService(Intent(this@RemoteControlActivity, RemoteScreenService::class.java))
                    RemoteSession.stop(); started = false; connected = false; serverConnected = false
                }) { Text("STOP REMOTE") }
            }
            if (error.isNotBlank()) {
                Spacer(Modifier.height(16.dp)); Text(error, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(32.dp))
            HorizontalDivider()
            Spacer(Modifier.height(20.dp))
            AutonomousAgentSection(context)

            Spacer(Modifier.height(32.dp))
            HorizontalDivider()
            Spacer(Modifier.height(20.dp))
            AutoConnectSection(context)

            Spacer(Modifier.height(32.dp))
            HorizontalDivider()
            Spacer(Modifier.height(20.dp))
            LocationSection(context, deviceId)

            Spacer(Modifier.height(32.dp))
            HorizontalDivider()
            Spacer(Modifier.height(20.dp))
            PrivacySection(context)
        }
    }

    /**
     * Builds a link that, when opened in a browser, asks that browser's own
     * user for permission to share their current location (a native browser
     * prompt neither this app nor the linked page can skip or hide). Only
     * share this link with a device/person who has agreed to share it.
     */
    /**
     * A real multi-step autonomous agent: give it a goal in plain language and
     * it repeatedly asks the AI for one next action, runs it, and feeds the
     * real result back so the AI can adapt - instead of committing to a fixed
     * plan made upfront. It automatically delegates to a focused specialist
     * (messaging/navigation/system/research/general) based on the goal.
     * Anything the app considers high-risk (sending a message, deleting
     * memory, etc.) pauses here for a tap to approve or cancel, same as the
     * rest of the app - full autonomy for everything else.
     *
     * Requires an AI API key to already be configured in Settings.
     */
    @Composable
    private fun AutonomousAgentSection(context: Context) {
        val scope = rememberCoroutineScope()
        var goal by remember { mutableStateOf("") }
        var running by remember { mutableStateOf(false) }
        var log by remember { mutableStateOf(listOf<String>()) }
        var pendingConfirmText by remember { mutableStateOf<String?>(null) }

        val orchestrator = remember {
            (context.applicationContext as JarvisApplication).container.autonomousAgentOrchestrator
        }

        fun appendLog(line: String) { log = log + line }

        fun handleEvent(event: AgentEvent) {
            when (event) {
                is AgentEvent.Started -> appendLog("▶ Using ${event.agentName}")
                is AgentEvent.Thinking -> appendLog("… step ${event.stepNumber}: thinking")
                is AgentEvent.StepExecuted -> appendLog("✓ ${event.command.describe()} → ${event.result.message}")
                is AgentEvent.AwaitingConfirmation -> {
                    pendingConfirmText = event.command.describe()
                    running = false
                }
                is AgentEvent.Done -> { appendLog("✔ Done: ${event.summary}"); running = false }
                is AgentEvent.Stopped -> { appendLog("■ Stopped: ${event.reason}"); running = false }
                is AgentEvent.Error -> { appendLog("✗ Error: ${event.message}"); running = false }
            }
        }

        Text("AUTONOMOUS MULTI-AGENT", color = JarvisCyan, fontSize = 16.sp)
        Spacer(Modifier.height(10.dp))
        Text(
            "Give it a goal and it works through it step by step, checking real results as it " +
                "goes and adapting if needed - instead of one fixed plan. High-risk steps (sending " +
                "messages, deleting memory) pause for your approval below. Needs an AI API key set " +
                "in Settings first.",
            fontSize = 11.sp
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = goal,
            onValueChange = { goal = it },
            label = { Text("Goal, e.g. \"find the weather and set a 7am alarm\"") },
            enabled = !running
        )
        Spacer(Modifier.height(10.dp))
        Button(
            enabled = !running && goal.isNotBlank(),
            onClick = {
                running = true
                log = emptyList()
                pendingConfirmText = null
                scope.launch {
                    orchestrator.run(goal) { event -> handleEvent(event) }
                }
            }
        ) { Text(if (running) "RUNNING…" else "RUN AGENT") }

        if (pendingConfirmText != null) {
            Spacer(Modifier.height(10.dp))
            Text("Needs approval: $pendingConfirmText", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            Row {
                Button(onClick = {
                    val text = pendingConfirmText
                    pendingConfirmText = null
                    running = true
                    appendLog("✓ Approved: $text")
                    scope.launch {
                        orchestrator.confirmPendingAndContinue { event -> handleEvent(event) }
                    }
                }) { Text("APPROVE") }
                Spacer(Modifier.width(10.dp))
                OutlinedButton(onClick = {
                    orchestrator.cancelPending()
                    appendLog("✗ Cancelled by you")
                    pendingConfirmText = null
                }) { Text("CANCEL") }
            }
        }

        if (log.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Column(Modifier.fillMaxWidth()) {
                log.forEach { Text(it, fontSize = 11.sp) }
            }
        }
    }

    /**
     * Lets the owner keep JARVIS connected to the relay in the background at
     * all times (survives app restarts and, when Android allows it, reboots),
     * so GET LOCATION / LOCK PHONE / WAKE SCREEN work without first pressing
     * START + SHARE SCREEN. Live screen mirroring itself still always needs
     * one manual tap on Android's own capture-consent dialog - that step
     * can never be automated (see the note in RemoteConnectService).
     */
    @Composable
    private fun AutoConnectSection(context: Context) {
        var enabled by remember { mutableStateOf(RemoteAutoConnect.isEnabled(context)) }

        Text("BACKGROUND AUTO-CONNECT", color = JarvisCyan, fontSize = 16.sp)
        Spacer(Modifier.height(10.dp))
        Text(
            "When ON, JARVIS stays connected to the relay in the background so " +
                "GET LOCATION, LOCK PHONE, and WAKE SCREEN work anytime - without " +
                "needing to press START + SHARE SCREEN first. Live screen viewing " +
                "still always needs one manual tap on Android's own permission " +
                "dialog when you want to watch the screen - Android never allows " +
                "that specific step to be automated.",
            fontSize = 11.sp
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (enabled) "ON" else "OFF", fontSize = 13.sp)
            Spacer(Modifier.width(10.dp))
            Switch(checked = enabled, onCheckedChange = {
                enabled = it
                RemoteAutoConnect.setEnabled(context, it)
            })
        }
    }

    @Composable
    private fun LocationSection(context: Context, deviceId: String) {
        var msg by remember { mutableStateOf("") }
        val handler = remember { Handler(Looper.getMainLooper()) }
        var lastLoc by remember { mutableStateOf(RemoteRelayClient.lastLocation) }

        DisposableEffect(Unit) {
            val poll = object : Runnable {
                override fun run() { lastLoc = RemoteRelayClient.lastLocation; handler.postDelayed(this, 2000) }
            }
            handler.post(poll)
            onDispose { handler.removeCallbacksAndMessages(null) }
        }

        Text("LOCATION LINK", color = JarvisCyan, fontSize = 16.sp)
        Spacer(Modifier.height(10.dp))
        Text(
            "Send this link (e.g. via WhatsApp) to the phone whose location you want. " +
                "Opening it always shows that phone's own browser permission prompt first - " +
                "nothing is shared unless they tap Allow. Only send this to a device/person who agreed.",
            fontSize = 11.sp
        )
        Spacer(Modifier.height(10.dp))
        Button(onClick = {
            val base = BuildConfig.DEFAULT_REMOTE_RELAY_URL.trimEnd('/')
            msg = if (base.isNotBlank()) {
                copyToClipboard(context, "JARVIS location link", "$base/loc?code=$deviceId")
                "Link copied - share it via WhatsApp etc."
            } else "Relay URL not configured in this APK"
        }) { Text("COPY LOCATION LINK") }
        if (msg.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text(msg, fontSize = 12.sp) }
        Spacer(Modifier.height(14.dp))
        val loc = lastLoc
        if (loc == null) {
            Text("No location has been shared back yet.", fontSize = 11.sp)
        } else {
            Text(
                "Last shared location:\nLat ${loc.lat}, Lng ${loc.lng}" +
                    (loc.accuracy?.let { " (±${it.toInt()} m)" } ?: "") +
                    "\n${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(loc.at))}",
                fontSize = 12.sp
            )
        }
    }

    /**
     * Lets the owner set a PIN and hide the app's launcher icon so someone
     * else who picks up the phone can't find or open JARVIS. See [AppLock].
     */
    @Composable
    private fun PrivacySection(context: Context) {
        var pinSet by remember { mutableStateOf(AppLock.isPinSet(context)) }
        var newPin by remember { mutableStateOf("") }
        var confirmPin by remember { mutableStateOf("") }
        var setPinError by remember { mutableStateOf("") }
        var hidePinInput by remember { mutableStateOf("") }
        var hideError by remember { mutableStateOf("") }
        var hideInfo by remember { mutableStateOf("") }

        Text("PRIVACY / APP LOCK", color = JarvisCyan, fontSize = 16.sp)
        Spacer(Modifier.height(10.dp))

        if (!pinSet) {
            Text("Set a PIN once to be able to hide this app and unlock it later.", fontSize = 11.sp)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = newPin,
                onValueChange = { newPin = it.filter { c -> c.isDigit() }.take(8); setPinError = "" },
                label = { Text("New PIN (4-8 digits)") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = confirmPin,
                onValueChange = { confirmPin = it.filter { c -> c.isDigit() }.take(8); setPinError = "" },
                label = { Text("Confirm PIN") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
            )
            if (setPinError.isNotBlank()) {
                Spacer(Modifier.height(6.dp)); Text(setPinError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
            Spacer(Modifier.height(10.dp))
            Button(onClick = {
                when {
                    newPin.length < 4 -> setPinError = "PIN must be at least 4 digits"
                    newPin != confirmPin -> setPinError = "PINs don't match"
                    else -> {
                        AppLock.setPin(context, newPin)
                        pinSet = true
                        newPin = ""; confirmPin = ""
                    }
                }
            }) { Text("SET PIN") }
        } else {
            Text("PIN is set. The app will ask for it every time it's opened.", fontSize = 11.sp)
            Spacer(Modifier.height(14.dp))
            Text(
                "To hide the app icon, enter your PIN and tap HIDE APP. " +
                    "To bring it back later, dial *#*#1231#*#* in your phone's Dialer app " +
                    "(you'll still need the PIN to get in).",
                fontSize = 11.sp
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = hidePinInput,
                onValueChange = { hidePinInput = it.filter { c -> c.isDigit() }.take(8); hideError = "" },
                label = { Text("Enter PIN to confirm") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
            )
            if (hideError.isNotBlank()) {
                Spacer(Modifier.height(6.dp)); Text(hideError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
            if (hideInfo.isNotBlank()) {
                Spacer(Modifier.height(6.dp)); Text(hideInfo, fontSize = 12.sp)
            }
            Spacer(Modifier.height(10.dp))
            Row {
                Button(onClick = {
                    if (AppLock.verify(context, hidePinInput)) {
                        AppLock.setHidden(context, true)
                        hidePinInput = ""
                        hideInfo = "App icon hidden. Dial *#*#1231#*#* to bring it back."
                        moveTaskToBack(true)
                    } else {
                        hideError = "Wrong PIN"
                    }
                }) { Text("HIDE APP") }
                Spacer(Modifier.width(10.dp))
                OutlinedButton(onClick = {
                    if (AppLock.verify(context, hidePinInput)) {
                        AppLock.clearPinAndUnhide(context)
                        pinSet = false
                        hidePinInput = ""
                        hideInfo = "PIN removed and app un-hidden."
                    } else {
                        hideError = "Wrong PIN"
                    }
                }) { Text("REMOVE PIN") }
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(20.dp))
            UninstallProtectionSection(context)
        }
    }

    @Composable
    private fun UninstallProtectionSection(context: Context) {
        var isProtected by remember { mutableStateOf(AppLock.isUninstallProtected(context)) }
        val adminLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { isProtected = AppLock.isUninstallProtected(context) }

        Text("UNINSTALL PROTECTION", color = JarvisCyan, fontSize = 16.sp)
        Spacer(Modifier.height(10.dp))
        if (isProtected) {
            Text(
                "Protection is ON. The \"Uninstall\" button for JARVIS is disabled in " +
                    "Settings > Apps. To remove the app, first go to Settings > Security > " +
                    "Device admin apps and deactivate JARVIS there, then uninstall as usual.",
                fontSize = 11.sp
            )
        } else {
            Text(
                "Turning this on disables the normal \"Uninstall\" button for this app, " +
                    "so it can't be casually removed. It must be deactivated from " +
                    "Settings > Security > Device admin apps first.",
                fontSize = 11.sp
            )
            Spacer(Modifier.height(10.dp))
            Button(onClick = {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, AppLock.deviceAdminComponent(context))
                    .putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "This lets JARVIS block casual uninstalling from Settings."
                    )
                adminLauncher.launch(intent)
            }) { Text("ENABLE UNINSTALL PROTECTION") }
        }
    }
}

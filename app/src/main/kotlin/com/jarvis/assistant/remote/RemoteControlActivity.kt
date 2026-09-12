package com.jarvis.assistant.remote

import com.jarvis.assistant.BuildConfig
import android.app.Activity
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
            PrivacySection(context)
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
        }
    }
}

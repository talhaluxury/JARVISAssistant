package com.jarvis.assistant

import android.Manifest
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.jarvis.assistant.navigation.JarvisNavGraph
import com.jarvis.assistant.security.AppLock
import com.jarvis.assistant.security.AppLockScreen
import com.jarvis.assistant.ui.screens.boot.BootScreen
import com.jarvis.assistant.ui.theme.JarvisTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            JarvisTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val context = LocalContext.current
                    // Require the PIN on every cold start whenever one is set, regardless of
                    // whether the launcher icon happens to be visible right now.
                    var unlocked by remember { mutableStateOf(!AppLock.isPinSet(context)) }
                    if (unlocked) {
                        JarvisApp()
                    } else {
                        AppLockScreen(onUnlocked = { unlocked = true })
                    }
                }
            }
        }
    }
}

@Composable
private fun JarvisApp() {
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var bootComplete by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    if (bootComplete) {
        JarvisNavGraph()
    } else {
        BootScreen(onFinished = { bootComplete = true })
    }
}

package com.jarvis.assistant.ui.screens.setup

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.ui.theme.JarvisBackground
import com.jarvis.assistant.ui.theme.JarvisCyan
import com.jarvis.assistant.ui.theme.JarvisSuccess
import com.jarvis.assistant.ui.theme.JarvisTextSecondary

/** First-run setup. Android still requires the human to enable the Accessibility switch. */
@Composable
fun AccessibilitySetupScreen(
    context: Context,
    onComplete: () -> Unit,
    onSkip: () -> Unit
) {
    var enabled by remember { mutableStateOf(JarvisAccessibilityService.isEnabled) }

    LaunchedEffect(Unit) {
        while (!enabled) {
            kotlinx.coroutines.delay(400)
            enabled = JarvisAccessibilityService.isEnabled
        }
    }

    if (enabled) {
        LaunchedEffect(Unit) { onComplete() }
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().background(JarvisBackground).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("J.A.R.V.I.S.", color = JarvisCyan, fontSize = 30.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.size(10.dp))
        Text("PHONE CONTROL SETUP", color = JarvisSuccess, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.size(24.dp))
        Text(
            "Enable JARVIS Accessibility to allow authorized phone-control and UI automation commands.",
            color = JarvisTextSecondary, fontSize = 14.sp, lineHeight = 21.sp
        )
        Spacer(Modifier.size(24.dp))
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = { openJarvisAccessibilitySettings(context) },
            colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan)
        ) { Text("ENABLE JARVIS CONTROL") }
        Spacer(Modifier.size(12.dp))
        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onSkip) {
            Text("CONTINUE WITHOUT PHONE CONTROL")
        }
        Spacer(Modifier.size(18.dp))
        Text(
            "Android requires you to enable this switch yourself. JARVIS cannot turn Accessibility on silently.",
            color = JarvisTextSecondary.copy(alpha = .75f), fontSize = 11.sp, lineHeight = 16.sp
        )
    }
}

private fun openJarvisAccessibilitySettings(context: Context) {
    val component = ComponentName(context, JarvisAccessibilityService::class.java)
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS / EXTRA_ACCESSIBILITY_COMPONENT_NAME
        // deep-link straight to this one service's toggle, but aren't part of the public
        // Android SDK (no compileSdk resolves them, even on 34/35) - only the underlying
        // platform action/extra strings are documented, so those are used directly here.
        Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").apply {
            putExtra("android.provider.extra.ACCESSIBILITY_COMPONENT_NAME", component.flattenToString())
        }
    } else {
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    }.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        // Some OEM Settings apps do not implement the per-service details action.
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

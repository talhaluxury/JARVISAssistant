package com.jarvis.assistant.ui.screens.permissions

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jarvis.assistant.agent.CapabilityState
import com.jarvis.assistant.agent.CapabilityStatus
import com.jarvis.assistant.ui.theme.JarvisCyan
import com.jarvis.assistant.ui.theme.JarvisError
import com.jarvis.assistant.ui.theme.JarvisSuccess
import com.jarvis.assistant.ui.theme.JarvisSurfaceGlass
import com.jarvis.assistant.ui.theme.JarvisTextPrimary
import com.jarvis.assistant.ui.theme.JarvisTextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(onBack: () -> Unit, viewModel: PermissionsViewModel = viewModel()) {
    val permissions by viewModel.permissions.collectAsState()
    val context = LocalContext.current

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.refresh()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Permission Center", fontFamily = FontFamily.Monospace) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
            }
        )

        Text(
            "JARVIS never grants or bypasses a permission itself — Android requires you to do " +
                "that explicitly. Tap any row below to go straight to the right screen.",
            color = JarvisTextSecondary,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(16.dp)
        )

        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            items(permissions) { permission ->
                PermissionRow(permission) {
                    if (permission.name == "Microphone" &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
                    ) {
                        micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        viewModel.openSettingsFor(permission.name)
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun PermissionRow(permission: CapabilityStatus, onTap: () -> Unit) {
    val (color, label) = when (permission.state) {
        CapabilityState.READY -> JarvisSuccess to "READY"
        CapabilityState.REQUIRED -> JarvisError to "REQUIRED"
        CapabilityState.OPTIONAL -> JarvisCyan to "OPTIONAL"
        CapabilityState.DENIED -> JarvisError to "DENIED"
    }
    Surface(
        color = JarvisSurfaceGlass,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth().clickable(enabled = permission.state != CapabilityState.READY) { onTap() }
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(permission.name, color = JarvisTextPrimary, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(3.dp))
                Text(permission.explanation, color = JarvisTextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.padding(end = 6.dp).size(8.dp).background(color, CircleShape))
                Text(label, color = color, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

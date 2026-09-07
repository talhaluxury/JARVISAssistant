package com.jarvis.assistant.ui.screens.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.data.local.db.entity.MessageEntity
import com.jarvis.assistant.ui.AssistantViewModel
import com.jarvis.assistant.ui.theme.JarvisCyan
import com.jarvis.assistant.ui.theme.JarvisSurfaceGlass
import com.jarvis.assistant.ui.theme.JarvisTextSecondary
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(viewModel: AssistantViewModel) {
    val messages by viewModel.messages.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(messages, key = { it.id }) { message ->
                MessagePanel(
                    message = message,
                    onCopy = { copyToClipboard(context, message.content) },
                    onSpeak = { scope.launch { viewModel.speakAgain(message.content) } }
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message JARVIS...") }
            )
            IconButton(onClick = {
                if (input.isNotBlank()) {
                    viewModel.sendMessage(input, speakReply = false)
                    input = ""
                }
            }) {
                Icon(Icons.Filled.Send, contentDescription = "Send", tint = JarvisCyan)
            }
        }
    }
}

/** A technical command-log style panel rather than a chat bubble — labeled, monospace, boxed. */
@Composable
private fun MessagePanel(message: MessageEntity, onCopy: () -> Unit, onSpeak: () -> Unit) {
    val isUser = message.role == "user"
    Column(horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
        Text(
            if (isUser) "USER COMMAND" else "J.A.R.V.I.S.",
            fontSize = 10.sp,
            color = JarvisTextSecondary,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(bottom = 3.dp, start = 4.dp, end = 4.dp)
        )
        Surface(
            color = if (isUser) JarvisCyan.copy(alpha = 0.10f) else JarvisSurfaceGlass,
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .border(1.dp, JarvisCyan.copy(alpha = if (isUser) 0.25f else 0.12f), RoundedCornerShape(4.dp))
        ) {
            Text(
                message.content,
                modifier = Modifier.padding(12.dp),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        if (!isUser) {
            Row {
                IconButton(onClick = onCopy) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Copy", modifier = Modifier.padding(4.dp))
                }
                IconButton(onClick = onSpeak) {
                    Icon(Icons.Filled.VolumeUp, contentDescription = "Speak", modifier = Modifier.padding(4.dp))
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("JARVIS reply", text))
}

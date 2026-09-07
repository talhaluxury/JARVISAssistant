package com.jarvis.assistant.ui.screens.history

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jarvis.assistant.data.local.db.entity.CommandHistoryEntity
import com.jarvis.assistant.data.local.db.entity.ConversationEntity
import com.jarvis.assistant.ui.AssistantViewModel
import com.jarvis.assistant.ui.theme.JarvisCyan
import com.jarvis.assistant.ui.theme.JarvisError
import com.jarvis.assistant.ui.theme.JarvisSuccess
import com.jarvis.assistant.ui.theme.JarvisSurfaceGlass
import com.jarvis.assistant.ui.theme.JarvisTextPrimary
import com.jarvis.assistant.ui.theme.JarvisTextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** #15 ACTIVITY LOG — two tabs: the existing conversation browser (unchanged, still fully
 * working), and a new real, timestamped system-activity feed (command received / action
 * executed / result / status) backed by [ActivityLogViewModel]. Neither tab replaces the
 * other — this adds the spec's activity-log format without removing conversation browsing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: AssistantViewModel,
    onOpenChat: () -> Unit,
    activityLogViewModel: ActivityLogViewModel = viewModel()
) {
    var tab by remember { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Activity Log") })

        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("CONVERSATIONS", fontSize = 10.sp, fontFamily = FontFamily.Monospace) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("SYSTEM LOG", fontSize = 10.sp, fontFamily = FontFamily.Monospace) })
        }

        when (tab) {
            0 -> ConversationList(viewModel, onOpenChat)
            else -> SystemActivityFeed(activityLogViewModel)
        }
    }
}

@Composable
private fun ConversationList(viewModel: AssistantViewModel, onOpenChat: () -> Unit) {
    val conversations by viewModel.conversations.collectAsState()
    var query by remember { mutableStateOf("") }
    var showClearDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ConversationEntity?>(null) }

    val filtered = if (query.isBlank()) conversations else conversations.filter {
        it.title.contains(query, ignoreCase = true)
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(end = 8.dp), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = { viewModel.startNewConversation(); onOpenChat() }) {
                Icon(Icons.Filled.Add, contentDescription = "New conversation")
            }
            IconButton(onClick = { showClearDialog = true }) {
                Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear all")
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            placeholder = { Text("Search conversations") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true
        )

        if (filtered.isEmpty()) {
            Text(
                "No conversations yet — start one from Home or Chat.",
                modifier = Modifier.padding(24.dp),
                color = JarvisTextSecondary
            )
        }

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(filtered, key = { it.id }) { conversation ->
                Surface(
                    color = JarvisSurfaceGlass,
                    shape = RoundedCornerShape(14.dp),
                    onClick = { viewModel.openConversation(conversation.id); onOpenChat() }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(vertical = 12.dp)) {
                            Text(
                                conversation.title,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                formatDate(conversation.updatedAt),
                                color = JarvisTextSecondary,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        IconButton(onClick = { pendingDelete = conversation }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete")
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { conversation ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this conversation?") },
            text = { Text(conversation.title) },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteConversation(conversation); pendingDelete = null }) {
                    Text("Delete")
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear all history?") },
            text = { Text("This deletes every conversation. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { viewModel.clearAllHistory(); showClearDialog = false }) { Text("Clear all") }
            },
            dismissButton = { TextButton(onClick = { showClearDialog = false }) { Text("Cancel") } }
        )
    }
}

/** The real #15-format feed: TIMESTAMP / COMMAND / ACTION+RESULT / STATUS, one block per
 * actual executed command — oldest at the bottom, newest at the top (most recent first). */
@Composable
private fun SystemActivityFeed(viewModel: ActivityLogViewModel) {
    val entries by viewModel.entries.collectAsState()
    var showClearDialog by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(end = 8.dp), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = { showClearDialog = true }) {
                Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear activity log")
            }
        }

        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text("No commands executed yet.", color = JarvisTextSecondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(entries, key = { it.id }) { entry -> ActivityEntryCard(entry) }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear activity log?") },
            text = { Text("This deletes the recorded command log. This can't be undone.") },
            confirmButton = { TextButton(onClick = { viewModel.clear(); showClearDialog = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { showClearDialog = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun ActivityEntryCard(entry: CommandHistoryEntity) {
    Surface(color = JarvisSurfaceGlass, shape = RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(entry.timestamp), color = JarvisTextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Text("[${entry.commandType}]", color = JarvisCyan, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
            Spacer()
            Text("COMMAND RECEIVED", color = JarvisTextSecondary, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            Text(entry.description, color = JarvisTextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Spacer()
            Text(
                if (entry.success) "VERIFICATION PASSED" else "ACTION FAILED",
                color = if (entry.success) JarvisSuccess else JarvisError,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
            if (entry.resultMessage.isNotBlank() && entry.resultMessage != entry.description) {
                Text(entry.resultMessage, color = JarvisTextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            Text(
                if (entry.success) "COMMAND COMPLETE" else "COMMAND FAILED",
                color = if (entry.success) JarvisSuccess else JarvisError,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun Spacer() = androidx.compose.foundation.layout.Spacer(Modifier.padding(vertical = 2.dp))

private fun formatDate(timestamp: Long): String =
    SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(timestamp))

private fun formatTime(timestamp: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

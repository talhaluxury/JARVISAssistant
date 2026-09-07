package com.jarvis.assistant.ui.screens.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jarvis.assistant.data.local.db.entity.TaskOutcomeEntity
import com.jarvis.assistant.ui.theme.JarvisCyan
import com.jarvis.assistant.ui.theme.JarvisError
import com.jarvis.assistant.ui.theme.JarvisSuccess
import com.jarvis.assistant.ui.theme.JarvisSurfaceGlass
import com.jarvis.assistant.ui.theme.JarvisTextPrimary
import com.jarvis.assistant.ui.theme.JarvisTextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(onBack: () -> Unit, viewModel: DashboardViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Dashboard", fontFamily = FontFamily.Monospace) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
            },
            actions = {
                IconButton(onClick = { viewModel.refresh() }) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
                IconButton(onClick = { viewModel.clearStats() }) { Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear stats") }
            }
        )

        if (state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = JarvisCyan) }
            return
        }

        if (state.totalTasks == 0) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No tasks have run yet.", color = JarvisTextSecondary, fontFamily = FontFamily.Monospace)
            }
            return
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item { Spacer(Modifier.height(12.dp)) }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatCard("TASKS RUN", state.totalTasks.toString(), Modifier.weight(1f))
                    Spacer(Modifier.width(10.dp))
                    StatCard("SUCCESS RATE", "${(state.successRate * 100).toInt()}%", Modifier.weight(1f), valueColor = if (state.successRate >= 0.8f) JarvisSuccess else if (state.successRate >= 0.5f) JarvisCyan else JarvisError)
                    Spacer(Modifier.width(10.dp))
                    StatCard("AVG TIME", "${state.averageExecutionTimeMs}ms", Modifier.weight(1f))
                }
                Spacer(Modifier.height(20.dp))
            }

            item {
                SectionHeader("RECENT TASK OUTCOMES")
                OutcomeTimeline(state.recentOutcomes)
                Spacer(Modifier.height(20.dp))
            }

            if (state.mostUsedCommands.isNotEmpty()) {
                item {
                    SectionHeader("MOST USED COMMANDS")
                    UsageBars(state.mostUsedCommands.map { it.commandType to it.count })
                    Spacer(Modifier.height(20.dp))
                }
            }

            if (state.mostCommonFailures.isNotEmpty()) {
                item {
                    SectionHeader("MOST COMMON FAILURES")
                    state.mostCommonFailures.forEach { (reason, count) ->
                        ListRow(reason, count.toString(), JarvisError)
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }

            if (state.flaggedWorkflows.isNotEmpty()) {
                item {
                    SectionHeader("FLAGGED FOR IMPROVEMENT")
                    state.flaggedWorkflows.forEach { workflow ->
                        Text(
                            "• $workflow",
                            color = JarvisTextSecondary,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }

            if (state.recentEvents.isNotEmpty()) {
                item {
                    SectionHeader("SYSTEM EVENT LOG")
                }
                items(state.recentEvents) { event ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(
                            "[${event.category}]",
                            color = JarvisCyan,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(end = 6.dp)
                        )
                        Text(event.message, color = JarvisTextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = JarvisTextPrimary) {
    Surface(color = JarvisSurfaceGlass, shape = RoundedCornerShape(6.dp), modifier = modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(label, color = JarvisTextSecondary, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(4.dp))
            Text(value, color = valueColor, fontSize = 20.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        color = JarvisTextSecondary,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun ListRow(label: String, value: String, valueColor: Color) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = JarvisTextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
        Text(value, color = valueColor, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

/** A real bar-per-task strip, oldest task on the left, green = succeeded, red = failed — drawn
 * from the actual TaskOutcomeEntity rows, not a mock/sample series. */
@Composable
private fun OutcomeTimeline(outcomes: List<TaskOutcomeEntity>) {
    Surface(color = JarvisSurfaceGlass, shape = RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth()) {
        Canvas(modifier = Modifier.fillMaxWidth().height(70.dp).padding(12.dp)) {
            if (outcomes.isEmpty()) return@Canvas
            val gap = 4.dp.toPx()
            val barWidth = ((size.width - gap * (outcomes.size - 1)) / outcomes.size).coerceAtLeast(2f)
            outcomes.forEachIndexed { index, outcome ->
                val x = index * (barWidth + gap)
                val heightFraction = if (outcome.success) 1f else 0.4f
                val barHeight = size.height * heightFraction
                drawRect(
                    color = if (outcome.success) JarvisSuccess else JarvisError,
                    topLeft = androidx.compose.ui.geometry.Offset(x, size.height - barHeight),
                    size = Size(barWidth, barHeight)
                )
            }
        }
    }
}

/** Horizontal usage bars for the most-used command types — counts only, never command content. */
@Composable
private fun UsageBars(counts: List<Pair<String, Int>>) {
    val max = (counts.maxOfOrNull { it.second } ?: 1).coerceAtLeast(1)
    Column {
        counts.forEach { (type, count) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    type, color = JarvisTextPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(0.4f)
                )
                Box(
                    Modifier
                        .weight(0.5f)
                        .height(10.dp)
                ) {
                    Surface(
                        color = JarvisCyan,
                        shape = RoundedCornerShape(2.dp),
                        modifier = Modifier.fillMaxWidth(count.toFloat() / max).fillMaxHeight()
                    ) {}
                }
                Text(
                    count.toString(), color = JarvisTextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(0.1f)
                )
            }
        }
    }
}

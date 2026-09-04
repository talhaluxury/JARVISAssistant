package com.jarvis.assistant.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.ui.theme.JarvisCyan
import com.jarvis.assistant.ui.theme.JarvisTextSecondary
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.jarvis.assistant.ui.AssistantViewModel
import com.jarvis.assistant.ui.screens.chat.ChatScreen
import com.jarvis.assistant.ui.screens.history.HistoryScreen
import com.jarvis.assistant.ui.screens.home.HomeScreen
import com.jarvis.assistant.ui.screens.memory.MemoryScreen
import com.jarvis.assistant.ui.screens.settings.SettingsScreen

private data class Destination(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val destinations = listOf(
    Destination("home", "Home", Icons.Filled.Home),
    Destination("chat", "Chat", Icons.Filled.Chat),
    Destination("memory", "Memory", Icons.Filled.Psychology),
    Destination("history", "History", Icons.Filled.History),
    Destination("settings", "Settings", Icons.Filled.Settings)
)

@Composable
fun JarvisNavGraph() {
    val navController = rememberNavController()
    // Shared across Home/Chat so a conversation started by voice on Home
    // continues seamlessly if the user switches to the Chat screen.
    val assistantViewModel: AssistantViewModel = viewModel()

    Scaffold(
        bottomBar = {
            val backStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = backStackEntry?.destination
            Surface(color = androidx.compose.ui.graphics.Color(0xEE02070D)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    destinations.forEach { destination ->
                        val selected = currentRoute?.hierarchy?.any { it.route == destination.route } == true
                        Surface(
                            color = if (selected) androidx.compose.ui.graphics.Color(0x5538DCFF) else androidx.compose.ui.graphics.Color.Transparent,
                            shape = RoundedCornerShape(3.dp)
                        ) {
                            Text(
                                destination.label.uppercase(),
                                color = if (selected) JarvisCyan else JarvisTextSecondary,
                                fontSize = 9.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                modifier = Modifier.clickable {
                                    navController.navigate(destination.route) {
                                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }.padding(horizontal = 10.dp, vertical = 7.dp)
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(padding)
        ) {
            composable("home") { HomeScreen(assistantViewModel) }
            composable("chat") { ChatScreen(assistantViewModel) }
            composable("memory") { MemoryScreen() }
            composable("history") { HistoryScreen(assistantViewModel, onOpenChat = { navController.navigate("chat") }) }
            composable("settings") { SettingsScreen() }
        }
    }
}

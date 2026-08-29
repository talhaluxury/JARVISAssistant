package com.jarvis.assistant.navigation

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
            NavigationBar {
                val backStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = backStackEntry?.destination
                destinations.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute?.hierarchy?.any { it.route == destination.route } == true,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = destination.label) },
                        label = { androidx.compose.material3.Text(destination.label) }
                    )
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

package com.jarvis.assistant.ui.screens.demotrade

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jarvis.assistant.ui.theme.JarvisTextSecondary

/**
 * The complete demo (paper) trading settings, embedded in the main Settings screen. It shows exactly the same panels as the
 * Settings tab of the Demo Trading screen (one shared composable), so both places always agree.
 */
@Composable
fun DemoSettingsSection(vm: DemoTradingViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Body("Paper trading only: no real money and no broker connection. Auto Demo Trading also starts a status notification so Android keeps the engine alive.", JarvisTextSecondary, 11)
        SettingsTab(state, vm)
    }
}

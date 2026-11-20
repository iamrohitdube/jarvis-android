package dev.jarvis.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jarvis.agent.Models
import dev.jarvis.data.AppSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ApiKeySection(vm)
            ConnectorsSection(vm)
            settings?.let { ModelSection(vm, it) }
            settings?.let { BudgetSection(vm, it) }
        }
    }
}

@Composable
private fun ModelSection(vm: SettingsViewModel, s: AppSettings) {
    var chat by remember(s.chatModel) { mutableStateOf(s.chatModel) }
    var quick by remember(s.quickModel) { mutableStateOf(s.quickModel) }
    SectionCard("Models") {
        Hint("Both default to Claude Haiku 5.5, the cheapest. For richer answers set the chat model to claude-sonnet-5-5 (about 20x the price).")
        OutlinedTextField(chat, { chat = it }, label = { Text("Chat model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(quick, { quick = it }, label = { Text("Quick model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.setChatModel(chat); vm.setQuickModel(quick) }) { Text("Save models") }
            TextButton(onClick = {
                chat = Models.DEFAULT_CHAT; quick = Models.DEFAULT_QUICK
                vm.setChatModel(chat); vm.setQuickModel(quick)
            }) { Text("Defaults") }
        }
        ToggleRow("Route simple questions to the quick model", s.routeSimpleToQuick, vm::setRouteSimple)
        ToggleRow("Server-side fallback if a request is declined (Sonnet/Opus)", s.serverFallback, vm::setServerFallback)
    }
}

@Composable
private fun BudgetSection(vm: SettingsViewModel, s: AppSettings) {
    var text by remember(s.monthlyBudgetUsd) { mutableStateOf(if (s.monthlyBudgetUsd > 0) s.monthlyBudgetUsd.toString() else "") }
    SectionCard("Monthly budget") {
        Hint("Local estimate; warns at 80%. Also set a spend limit in the Anthropic Console, which is the real cap.")
        OutlinedTextField(
            text, { text = it }, label = { Text("Budget in USD (empty = none)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = { vm.setBudget(text.ifBlank { "0" }) }) { Text("Save budget") }
        ToggleRow("Stop requests when the budget is used up", s.stopAtBudget, vm::setStopAtBudget)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

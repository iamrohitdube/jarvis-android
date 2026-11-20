package dev.jarvis.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jarvis.agent.Models
import dev.jarvis.data.AppSettings
import dev.jarvis.ui.components.Hint
import dev.jarvis.ui.components.NavRow
import dev.jarvis.ui.components.SaveButton
import dev.jarvis.ui.components.SectionLabel
import dev.jarvis.ui.components.SettingsCard
import dev.jarvis.ui.components.StatusMessage
import dev.jarvis.ui.history.ConfirmDialog
import dev.jarvis.ui.icons.AppIcons

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenSecurity: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            androidx.compose.material3.LargeTopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(AppIcons.ArrowBack, "Back") } },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionLabel("Account")
            ApiKeySection(vm)
            NavRow("Security", AppIcons.Shield, "PIN, fingerprint, sessions, tool permissions", tint = MaterialTheme.colorScheme.tertiary, onClick = onOpenSecurity)

            SectionLabel("Connectors")
            ConnectorsSection(vm)

            settings?.let {
                SectionLabel("Intelligence")
                ModelSection(vm, it)
                VoiceSection(vm, it)
                SectionLabel("Spending")
                BudgetSection(vm, it)
                SectionLabel("Privacy")
                DataSection(vm, it)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ModelSection(vm: SettingsViewModel, s: AppSettings) {
    var custom by remember { mutableStateOf(false) }
    var chat by remember(s.chatModel) { mutableStateOf(s.chatModel) }
    var quick by remember(s.quickModel) { mutableStateOf(s.quickModel) }
    SettingsCard("Model", AppIcons.AutoAwesome, subtitle = "Haiku is the cheapest; Sonnet gives richer answers (~20x the price)") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = s.chatModel == Models.HAIKU, onClick = { vm.setChatModel(Models.HAIKU) }, label = { Text("Haiku 5.5 · thrifty") })
            FilterChip(selected = s.chatModel == Models.SONNET, onClick = { vm.setChatModel(Models.SONNET) }, label = { Text("Sonnet 5.5 · smart") })
        }
        ToggleRow("Quick model for short questions", "Simple questions use the cheapest settings", s.routeSimpleToQuick, vm::setRouteSimple)
        ToggleRow("Server-side fallback", "Retry a declined request on another model (Sonnet/Opus only)", s.serverFallback, vm::setServerFallback)
        TextButton(onClick = { custom = !custom }, contentPadding = PaddingValues(0.dp)) {
            Text(if (custom) "Hide custom model IDs" else "Custom model IDs", style = MaterialTheme.typography.labelMedium)
        }
        AnimatedVisibility(custom) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(chat, { chat = it }, label = { Text("Chat model") }, singleLine = true, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(quick, { quick = it }, label = { Text("Quick model") }, singleLine = true, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SaveButton { vm.setChatModel(chat); vm.setQuickModel(quick) }
                    TextButton(onClick = {
                        chat = Models.DEFAULT_CHAT; quick = Models.DEFAULT_QUICK
                        vm.setChatModel(chat); vm.setQuickModel(quick)
                    }) { Text("Reset") }
                }
            }
        }
    }
}


@Composable
fun VoiceSection(vm: SettingsViewModel, s: AppSettings) {
    var rate by remember(s.speechRate) { mutableFloatStateOf(s.speechRate) }
    SettingsCard("Voice", AppIcons.Mic, subtitle = "Tap the mic in chat to talk; replies are spoken sentence by sentence") {
        ToggleRow("Speak replies", "Off = quiet mode: answers on screen only", s.speakReplies, vm::setSpeakReplies)
        ToggleRow("On-device speech recognition", "Keeps your voice on the phone when supported", s.preferOnDeviceStt, vm::setPreferOnDeviceStt)
        Text("Speaking speed: " + "%.1fx".format(rate), style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = rate, onValueChange = { rate = it }, valueRange = 0.7f..1.6f, steps = 8,
            onValueChangeFinished = { vm.setSpeechRate(rate) },
        )
        OutlinedButton(onClick = vm::testVoice) {
            Icon(AppIcons.VolumeUp, null, modifier = Modifier.size(18.dp))
            Text("  Test voice")
        }
        Hint("Fingerprint answers are never read aloud, and nothing sensitive is spoken while the phone is locked.")
    }
}

@Composable
private fun BudgetSection(vm: SettingsViewModel, s: AppSettings) {
    var text by remember(s.monthlyBudgetUsd) { mutableStateOf(if (s.monthlyBudgetUsd > 0) s.monthlyBudgetUsd.toString() else "") }
    SettingsCard("Monthly budget", AppIcons.Savings, subtitle = "Local estimate; warns at 80%", tint = androidx.compose.ui.graphics.Color(0xFF34D399)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                text, { text = it }, label = { Text("USD (empty = none)") }, singleLine = true, prefix = { Text("$") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f),
            )
            SaveButton { vm.setBudget(text.ifBlank { "0" }) }
        }
        ToggleRow("Stop when used up", "No more requests until next month or a higher budget", s.stopAtBudget, vm::setStopAtBudget)
        Hint("Your Anthropic Console spend limit is the real cap.")
    }
}

@Composable
private fun DataSection(vm: SettingsViewModel, s: AppSettings) {
    var minutes by remember(s.snapshotMaxAgeMinutes) { mutableStateOf(s.snapshotMaxAgeMinutes.toString()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val message by vm.dataMessage.collectAsStateWithLifecycle()
    SettingsCard("Data on this phone", AppIcons.Storage, subtitle = "Chats, saved data, usage and audit log; never backed up", tint = MaterialTheme.colorScheme.secondary) {
        Hint("Recent portfolio data is reused to save a connector call. Say \"refresh\" to fetch live.")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                minutes, { minutes = it }, label = { Text("Reuse for (min, 0 = live)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f),
            )
            SaveButton { vm.setSnapshotMinutes(minutes) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::clearSnapshots) { Text("Clear saved data") }
            TextButton(onClick = { confirmDelete = true }) {
                Icon(AppIcons.DeleteOutline, null, tint = MaterialTheme.colorScheme.error)
                Text(" Delete history", color = MaterialTheme.colorScheme.error)
            }
        }
        StatusMessage(message)
    }
    if (confirmDelete) {
        ConfirmDialog(
            "Delete all history?", "Every saved conversation will be removed from this phone. This can't be undone.",
            onConfirm = vm::deleteAllHistory, onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
fun ToggleRow(label: String, description: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            description?.let { Hint(it) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

package dev.jarvis.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import dev.jarvis.agent.Models
import dev.jarvis.service.ListeningService
import dev.jarvis.ui.components.ChipTone
import dev.jarvis.ui.components.StatusChip
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
                WakeWordSection(vm, it)
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
fun WakeWordSection(vm: SettingsViewModel, s: AppSettings) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycle.lifecycle.addObserver(obs)
        onDispose { lifecycle.lifecycle.removeObserver(obs) }
    }
    val overlayOk = remember(refresh) { android.provider.Settings.canDrawOverlays(context) }
    val batteryOk = remember(refresh) {
        context.getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
    }
    val micOk = remember(refresh) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) {
            vm.setWakeWordEnabled(true)
            ListeningService.start(context)
        }
        refresh++
    }
    SettingsCard("\"Hey Jarvis\"", AppIcons.GraphicEq, subtitle = "Always-on wake word, recognised on the phone") {
        ToggleRow(
            "Listen for \"Hey Jarvis\"",
            "Shows a notification while listening. Audio stays on the phone until you say it.",
            s.wakeWordEnabled,
        ) { on ->
            if (on) {
                val wanted = buildList {
                    add(Manifest.permission.RECORD_AUDIO)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
                }
                permissions.launch(wanted.toTypedArray())
            } else {
                vm.setWakeWordEnabled(false)
                ListeningService.stop(context)
            }
        }
        val assistant = remember(refresh) { dev.jarvis.service.JarvisVoiceInteractionService.isDefaultAssistant(context) }
        PermissionRow(
            "Default digital assistant",
            "Recommended. Keeps \"Hey Jarvis\" working anytime, even after a restart, with no taps. Replaces Gemini/Google Assistant.",
            assistant,
        ) {
            val voice = Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS)
            val defaults = Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
            runCatching { context.startActivity(voice) }.onFailure { runCatching { context.startActivity(defaults) } }
        }
        PermissionRow(
            "Pop up over other apps", "Lets the \"Hey Jarvis\" screen appear over anything, even the lock screen.", overlayOk,
        ) {
            context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri()))
        }
        PermissionRow(
            "Don't stop in the background", "Battery savers can stop the listener; this keeps it running.", batteryOk,
        ) {
            context.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri()))
        }
        if (!micOk) Hint("Microphone permission is needed.")
        if (s.wakeWordEnabled) WakeDiagnosticsPanel(vm, s)
        Hint("In a pocket (proximity sensor covered) JARVIS answers by voice only, through your Bluetooth headset if one is connected. Private answers need the phone in hand.")
    }
}


@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun WakeDiagnosticsPanel(vm: SettingsViewModel, s: AppSettings) {
    val context = LocalContext.current
    val status by vm.wake.status.collectAsStateWithLifecycle()
    val level by vm.wake.level.collectAsStateWithLifecycle()
    val active by vm.wake.modelActive.collectAsStateWithLifecycle()
    val heard by vm.wake.heardCount.collectAsStateWithLifecycle()
    val error by vm.wake.lastError.collectAsStateWithLifecycle()
    val gain by vm.wake.gain.collectAsStateWithLifecycle()
    // Log-ish scale so quiet mics still show movement: 0.0003 → ~0, 0.03 → full.
    val meter by androidx.compose.animation.core.animateFloatAsState(
        ((kotlin.math.log10(level.coerceAtLeast(0.0003f)) + 3.5f) / 2f).coerceIn(0f, 1f), label = "meter",
    )

    Text("Detector", style = MaterialTheme.typography.bodyLarge)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        dev.jarvis.voice.WakeEngineChoice.entries.forEachIndexed { i, e ->
            SegmentedButton(
                selected = s.wakeEngine == e,
                onClick = { vm.setWakeEngine(e) { if (s.wakeWordEnabled) runCatching { ListeningService.restart(context) } } },
                shape = SegmentedButtonDefaults.itemShape(i, dev.jarvis.voice.WakeEngineChoice.entries.size),
            ) { Text(e.label) }
        }
    }
    if (s.wakeEngine == dev.jarvis.voice.WakeEngineChoice.PHONE) {
        Hint(
            "Uses your phone's speech recognition, so it works with your voice. More battery than the keyword model. " +
                "With on-device recognition (Settings → Voice) speech stays on the phone; otherwise speech near the phone " +
                "goes to your phone's recognizer (usually Google) while listening."
        )
    }
    Text("Wake sound", style = MaterialTheme.typography.bodyLarge)
    Hint("How JARVIS lets you know it heard \"Hey Jarvis\", even with the screen off.")
    Column {
        dev.jarvis.voice.WakeAck.entries.forEach { a ->
            Row(Modifier.fillMaxWidth().clickable { vm.setWakeAck(a) }, verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.RadioButton(selected = s.wakeAck == a, onClick = { vm.setWakeAck(a) })
                Text(a.label, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    if (s.wakeAck == dev.jarvis.voice.WakeAck.VOICE || s.wakeAck == dev.jarvis.voice.WakeAck.CHIME_VOICE) {
        var phrase by remember(s.wakeAckPhrase) { mutableStateOf(s.wakeAckPhrase) }
        OutlinedTextField(
            value = phrase,
            onValueChange = { phrase = it.take(40); vm.setWakeAckPhrase(phrase) },
            label = { Text("Phrase (e.g. Yes?, Hi, At your service)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    TextButton(onClick = vm::previewWakeAck) { Text("Play it") }
    androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    if (s.wakeEngine == dev.jarvis.voice.WakeEngineChoice.PHONE) {
        val prompt by vm.trainingPrompt.collectAsStateWithLifecycle()
        val result by vm.trainingResult.collectAsStateWithLifecycle()
        Text("Teach JARVIS your voice", style = MaterialTheme.typography.bodyLarge)
        Hint("Say \"Hey Jarvis\" 3 times and \"Jarvis\" twice. JARVIS learns how your phone hears you, so it misses you less.")
        Button(onClick = { vm.trainWakeWord(context) }, enabled = prompt == null) { Text(if (prompt == null) "Start training" else "Listening…") }
        prompt?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
        result?.let { StatusMessage(it) }
        if (s.learnedWakePhrases.isNotEmpty()) {
            Text("Learned: tap ✕ to remove one that wakes JARVIS by mistake", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            s.learnedWakePhrases.forEach { phrase ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("\"$phrase\"", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { vm.removeLearnedPhrase(phrase) }) { Text("✕") }
                }
            }
            TextButton(onClick = vm::clearLearnedPhrases) { Text("Forget all learned phrases") }
        }
        androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
    Text("Sensitivity", style = MaterialTheme.typography.bodyLarge)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        dev.jarvis.voice.WakeSensitivity.entries.forEachIndexed { i, w ->
            SegmentedButton(
                selected = s.wakeSensitivity == w,
                onClick = { vm.setWakeSensitivity(w) { if (s.wakeWordEnabled) runCatching { ListeningService.restart(context) } } },
                shape = SegmentedButtonDefaults.itemShape(i, dev.jarvis.voice.WakeSensitivity.entries.size),
            ) { Text(w.name.lowercase().replaceFirstChar { it.uppercase() }) }
        }
    }
    Hint(
        if (s.wakeEngine == dev.jarvis.voice.WakeEngineChoice.PHONE) "Strict: only wakes on \"Hey/Hi Jarvis\". Normal/Sensitive: any sentence with \"Jarvis\"."
        else "Missed often? Try Sensitive (always analysing, a bit more battery). Waking by itself? Try Strict."
    )
    androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Text("Live check", style = MaterialTheme.typography.bodyLarge)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Listener", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        when (status) {
            dev.jarvis.service.WakeBus.Status.LISTENING -> StatusChip("Listening", ChipTone.GOOD)
            dev.jarvis.service.WakeBus.Status.CONVERSATION -> StatusChip("Talking", ChipTone.BRAND)
            dev.jarvis.service.WakeBus.Status.PAUSED -> StatusChip("Paused (mic in use)")
            dev.jarvis.service.WakeBus.Status.STARTING -> StatusChip("Starting")
            dev.jarvis.service.WakeBus.Status.STOPPED -> StatusChip("Stopped", ChipTone.WARN)
            dev.jarvis.service.WakeBus.Status.NOT_READY -> StatusChip("Not ready", ChipTone.BAD)
            dev.jarvis.service.WakeBus.Status.ERROR -> StatusChip("Error", ChipTone.BAD)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Mic level", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        androidx.compose.material3.LinearProgressIndicator(progress = { meter }, modifier = Modifier.width(120.dp))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Microphone", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(s.wakeMicSource.label, style = MaterialTheme.typography.bodyMedium)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Boost", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text("%.0fx".format(gain), style = MaterialTheme.typography.bodyMedium)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Model", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (active) StatusChip("Analysing speech", ChipTone.BRAND) else StatusChip("Idle (quiet)")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Heard \"Hey Jarvis\"", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text("$heard time" + if (heard == 1) "" else "s", style = MaterialTheme.typography.bodyMedium)
    }
    error?.let { StatusMessage(it, error = true) }
    val lastHeard by vm.wake.lastHeard.collectAsStateWithLifecycle()
    if (s.wakeEngine == dev.jarvis.voice.WakeEngineChoice.PHONE) {
        Hint("Last heard: " + (lastHeard?.let { "\"$it\"" } ?: "nothing yet"))
        val lastWake by vm.wake.lastWake.collectAsStateWithLifecycle()
        val lastWakeAt by vm.wake.lastWakeAt.collectAsStateWithLifecycle()
        lastWake?.let { w ->
            val at = lastWakeAt?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalTime().withNano(0) }
            Hint("Last woke on: \"$w\"" + (at?.let { " at $it" } ?: ""))
        }
    }
    Hint("Speak near the phone: the mic level should move and the model should switch to \"Analysing\" while you talk.")
    val testResult by vm.wakeTest.collectAsStateWithLifecycle()
    val testing by vm.wakeTesting.collectAsStateWithLifecycle()
    val canSave by vm.canSaveClip.collectAsStateWithLifecycle()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = vm::testWakeWord, enabled = !testing) { Text(if (testing) "Listening…" else "Test now") }
        OutlinedButton(onClick = { vm.compareMicrophones(context) }, enabled = !testing) { Text("Compare microphones") }
        if (canSave && !testing) TextButton(onClick = { vm.saveTestClip(context) }) { Text("Save recording") }
    }
    testResult?.let { StatusMessage(it, error = it.startsWith("Not") || it.startsWith("Detector") || it.startsWith("Didn")) }
}

@Composable
private fun PermissionRow(title: String, why: String, granted: Boolean, onFix: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Hint(why)
        }
        if (granted) StatusChip("Allowed", ChipTone.GOOD) else TextButton(onClick = onFix) { Text("Allow") }
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

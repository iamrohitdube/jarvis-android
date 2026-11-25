package dev.jarvis.ui.settings

import android.content.Context
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jarvis.connectors.ConnectorStatus
import dev.jarvis.ui.components.ChipTone
import dev.jarvis.ui.components.Hint
import dev.jarvis.ui.components.SettingsCard
import dev.jarvis.ui.components.StatusChip
import dev.jarvis.ui.components.StatusMessage
import dev.jarvis.ui.theme.Brand
import dev.jarvis.ui.icons.AppIcons

/** Inline spinner shown inside a button while it's working. */
@Composable
fun ButtonProgress() {
    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
    Spacer(Modifier.width(8.dp))
}

/** Anthropic key: paste, test (free GET /v1/models), save encrypted. The saved key is never displayed. */
@Composable
fun ApiKeySection(vm: SettingsViewModel, onKeyOk: () -> Unit = {}) {
    val hasKey by vm.hasApiKey.collectAsStateWithLifecycle()
    val message by vm.keyMessage.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val input = rememberTextFieldState()

    SettingsCard(
        "Anthropic API key", AppIcons.Key,
        subtitle = if (hasKey) "Encrypted with the Android Keystore" else "Create one at console.anthropic.com",
        trailing = { if (hasKey) StatusChip("Saved", ChipTone.GOOD, AppIcons.CheckCircle) else StatusChip("Not set", ChipTone.WARN) },
    ) {
        Row(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(Brand.Warning.copy(alpha = 0.12f)).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AppIcons.WarningAmber, null, tint = Brand.Warning, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text("Set a monthly spend limit on your Anthropic key.", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedSecureTextField(
            state = input,
            label = { Text(if (hasKey) "Replace key" else "Paste key") },
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { vm.saveAndTestKey(input.text.toString()) { input.clearText(); onKeyOk() } },
                enabled = !busy && input.text.isNotBlank(),
            ) {
                if (busy) ButtonProgress()
                Text("Test & save")
            }
            if (hasKey) {
                OutlinedButton(onClick = vm::testSavedKey, enabled = !busy) { Text("Test") }
                TextButton(onClick = vm::removeKey, enabled = !busy) { Text("Remove") }
            }
        }
        StatusMessage(message, error = message?.let { "rejected" in it || "Couldn't" in it } == true)
    }
}

/** Connectors: INDmoney OAuth sign-in in a Custom Tab, a free MCP test call, and a manual-token fallback. */
@Composable
fun ConnectorsSection(vm: SettingsViewModel, onConnectorOk: () -> Unit = {}) {
    val status by vm.indMoneyStatus.collectAsStateWithLifecycle()
    val message by vm.connectorMessage.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showManual by remember { mutableStateOf(false) }
    val manualToken = rememberTextFieldState()

    SettingsCard(
        "INDmoney", AppIcons.AccountBalanceWallet,
        subtitle = "Net worth, holdings, SIPs and market data (read-only)",
        tint = MaterialTheme.colorScheme.secondary,
        trailing = {
            when (status) {
                ConnectorStatus.Connected -> StatusChip("Connected", ChipTone.GOOD)
                ConnectorStatus.NotConnected -> StatusChip("Not connected")
                ConnectorStatus.NeedsReauth -> StatusChip("Expired", ChipTone.WARN)
            }
        },
    ) {
        Hint("You sign in on INDmoney's own page; JARVIS never sees your INDmoney password.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { vm.startIndMoneySignIn { openCustomTab(context, it) } }, enabled = !busy) {
                Text(if (status == ConnectorStatus.NotConnected) "Sign in" else "Sign in again")
            }
            if (status != ConnectorStatus.NotConnected) {
                OutlinedButton(onClick = { vm.testIndMoney(onConnectorOk) }, enabled = !busy) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Test")
                }
                TextButton(onClick = vm::signOutIndMoney, enabled = !busy) { Text("Sign out") }
            }
        }
        StatusMessage(message, error = message?.let { "Couldn't" in it || "expired" in it || "failed" in it.lowercase() } == true)
        TextButton(onClick = { showManual = !showManual }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            Text(if (showManual) "Hide advanced" else "Advanced: paste a token", style = MaterialTheme.typography.labelMedium)
        }
        AnimatedVisibility(showManual) {
            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Hint("Only if browser sign-in doesn't work. A pasted token can't be refreshed.")
                OutlinedSecureTextField(
                    state = manualToken, label = { Text("Access token") },
                    shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = { vm.setIndMoneyManualToken(manualToken.text.toString()); manualToken.clearText() },
                    enabled = !busy && manualToken.text.isNotBlank(),
                ) { Text("Save token") }
            }
        }
    }
}

/** Gmail (D29): Google sign-in through Play services, a free test read, and the privacy filter. Read-only. */
@Composable
fun GmailSection(vm: SettingsViewModel, onConnectorOk: () -> Unit = {}) {
    val status by vm.gmailStatus.collectAsStateWithLifecycle()
    val message by vm.gmailMessage.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val consent = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult(),
    ) { result -> vm.completeGoogleSignIn(result.data, onConnectorOk) }
    var senders by remember(settings?.emailExcludedSenders) { mutableStateOf(settings?.emailExcludedSenders.orEmpty()) }

    SettingsCard(
        "Gmail", AppIcons.Mail,
        subtitle = "Unread, latest and emails from someone (read-only, answered on the phone)",
        tint = MaterialTheme.colorScheme.secondary,
        trailing = {
            when (status) {
                ConnectorStatus.Connected -> StatusChip("Connected", ChipTone.GOOD)
                ConnectorStatus.NotConnected -> StatusChip("Not connected")
                ConnectorStatus.NeedsReauth -> StatusChip("Expired", ChipTone.WARN)
            }
        },
    ) {
        Hint("Needs your own Google Cloud project first (docs/setup-keys.md, section 3). JARVIS reads only sender, subject and preview; emails never go to Claude.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    vm.startGoogleSignIn({ pi ->
                        consent.launch(androidx.activity.result.IntentSenderRequest.Builder(pi.intentSender).build())
                    }, onConnectorOk)
                },
                enabled = !busy,
            ) { Text(if (status == ConnectorStatus.NotConnected) "Sign in" else "Sign in again") }
            if (status != ConnectorStatus.NotConnected) {
                OutlinedButton(onClick = { vm.testGmail(onConnectorOk) }, enabled = !busy) { Text("Test") }
                TextButton(onClick = vm::signOutGmail, enabled = !busy) { Text("Sign out") }
            }
        }
        StatusMessage(message, error = message?.let { "Couldn't" in it || "failed" in it.lowercase() } == true)
        Hint("Never shown or saved: one-time codes, promotions, and these senders (comma separated):")
        androidx.compose.material3.OutlinedTextField(
            senders, { senders = it }, label = { Text("Excluded senders") },
            shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth(), minLines = 2,
        )
        OutlinedButton(
            onClick = { vm.setEmailExcludedSenders(senders) },
            enabled = senders != settings?.emailExcludedSenders,
        ) { Text("Save filter") }
    }
}

/** Google Drive (D29): file names and dates only, authorised separately from Gmail. */
@Composable
fun DriveSection(vm: SettingsViewModel) {
    val status by vm.driveStatus.collectAsStateWithLifecycle()
    val message by vm.driveMessage.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val consent = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult(),
    ) { result -> vm.completeGoogleSignIn(result.data, kind = SettingsViewModel.GoogleKind.DRIVE) }
    SettingsCard(
        "Google Drive", AppIcons.Storage,
        subtitle = "Find a file by name, recent files (names and dates only)",
        tint = MaterialTheme.colorScheme.secondary,
        trailing = {
            when (status) {
                ConnectorStatus.Connected -> StatusChip("Connected", ChipTone.GOOD)
                ConnectorStatus.NotConnected -> StatusChip("Not connected")
                ConnectorStatus.NeedsReauth -> StatusChip("Expired", ChipTone.WARN)
            }
        },
    ) {
        Hint("Uses the same Google Cloud project as Gmail, with the Drive API and the drive.metadata.readonly scope added (docs/setup-keys.md section 3). File contents are never read.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    vm.startGoogleSignIn({ pi ->
                        consent.launch(androidx.activity.result.IntentSenderRequest.Builder(pi.intentSender).build())
                    }, kind = SettingsViewModel.GoogleKind.DRIVE)
                },
                enabled = !busy,
            ) { Text(if (status == ConnectorStatus.NotConnected) "Sign in" else "Sign in again") }
            if (status != ConnectorStatus.NotConnected) {
                OutlinedButton(onClick = vm::testDrive, enabled = !busy) { Text("Test") }
                TextButton(onClick = vm::signOutDrive, enabled = !busy) { Text("Sign out") }
            }
        }
        StatusMessage(message, error = message?.let { "Couldn't" in it || "failed" in it.lowercase() } == true)
    }
}

/** Messaging (CLAUDE.md section 7): the off switch, trusted contacts, and the log of every send. */
@Composable
fun MessagingSection(vm: SettingsViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val log by vm.sentLog.collectAsStateWithLifecycle()
    val settings = s ?: return
    var trusted by remember(settings.trustedContacts) { mutableStateOf(settings.trustedContacts) }
    SettingsCard("Messaging", AppIcons.Send, subtitle = "Texts and chat replies, always confirmed by you", tint = MaterialTheme.colorScheme.tertiary) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                Text("Allow sending", style = MaterialTheme.typography.bodyLarge)
                Hint("Off = JARVIS never sends anything. On = every message shows the full text and waits for \"send it\", then 5 seconds to cancel.")
            }
            androidx.compose.material3.Switch(checked = settings.sendingEnabled, onCheckedChange = vm::setSendingEnabled)
        }
        Hint("Trusted contacts (comma separated) can be confirmed by voice. Anyone else needs a tap on the screen.")
        androidx.compose.material3.OutlinedTextField(
            trusted, { trusted = it }, label = { Text("Trusted contacts") },
            shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = { vm.setTrustedContacts(trusted) }, enabled = trusted != settings.trustedContacts) { Text("Save") }
        Hint("At most ${settings.maxMessagesPerHour} messages an hour. Replies to WhatsApp and other chat apps work only while their message notification is showing.")
        if (log.isNotEmpty()) {
            Text("Recent", style = MaterialTheme.typography.titleSmall)
            log.take(5).forEach { m ->
                Hint("${m.outcome} · ${m.channel} to ${m.recipient}: \"${m.text.take(60)}\"")
            }
        }
    }
}

/** Call recordings (D30): pick the dialer's recordings folder, then transcribe on the phone. */
@Composable
fun RecordingsSection(vm: SettingsViewModel) {
    val status by vm.localStatus.collectAsStateWithLifecycle()
    val done by vm.transcribedCount.collectAsStateWithLifecycle()
    val pending by vm.pendingRecordings.collectAsStateWithLifecycle()
    val progress by vm.transcribeProgress.collectAsStateWithLifecycle()
    val message by vm.recordingsMessage.collectAsStateWithLifecycle()
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> vm.setRecordingsFolder(uri) }
    LaunchedEffect(Unit) { vm.refreshPendingRecordings() }
    val chosen = status[dev.jarvis.agent.ToolRegistry.RECORDINGS] == ConnectorStatus.Connected
    SettingsCard(
        "Call recordings", AppIcons.Mic,
        subtitle = "Search what was said in calls your dialer recorded",
        tint = MaterialTheme.colorScheme.secondary,
        trailing = { if (chosen) StatusChip("$done transcribed", ChipTone.GOOD) else StatusChip("No folder") },
    ) {
        Hint("JARVIS can't record calls (Android blocks it). Turn on call recording in your phone's dialer, then pick the folder it saves to. Transcripts are made on the phone (English) and never sent to Claude.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { picker.launch(null) }) { Text(if (chosen) "Change folder" else "Choose folder") }
            if (chosen) Button(onClick = vm::transcribeRecordings, enabled = progress == null) {
                Text(if (pending > 0) "Transcribe $pending" else "Transcribe")
            }
        }
        progress?.let { p ->
            androidx.compose.material3.LinearProgressIndicator(progress = { p.done.toFloat() / p.total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
            Hint("Transcribing ${p.done + 1} of ${p.total}" + (p.current?.let { ": $it" } ?: "") + ". Keep JARVIS open.")
        }
        if (chosen && done > 0) TextButton(onClick = vm::deleteTranscripts) { Text("Delete transcripts") }
        StatusMessage(message)
    }
}

/** Phone data (D29/D30): each source is read on the phone with an Android permission; nothing leaves the phone. */
@Composable
fun PhoneDataSection(vm: SettingsViewModel) {
    val status by vm.localStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var denied by remember { mutableStateOf<String?>(null) }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        vm.refreshLocalSources()
        denied = if (result.values.any { !it }) {
            "Not allowed. Android limits call log, SMS and notification access for apps installed from a file: open " +
                "App settings → ⋮ (top right) → Allow restricted settings, then come back and tap Allow again."
        } else null
    }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { vm.refreshLocalSources(); onPauseOrDispose { } }
    SettingsCard(
        "Phone data", AppIcons.Storage,
        subtitle = "Read on the phone and answered on the phone; never sent to Claude",
        tint = MaterialTheme.colorScheme.secondary,
    ) {
        vm.localSources.filter { it.permissions.isNotEmpty() }.forEach { source ->
            val ok = status[source.id] == ConnectorStatus.Connected
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                    Text(source.displayName, style = MaterialTheme.typography.bodyLarge)
                    Hint(source.purpose)
                }
                if (ok) StatusChip("Allowed", ChipTone.GOOD)
                else OutlinedButton(onClick = { launcher.launch(source.permissions.toTypedArray()) }) { Text("Allow") }
            }
        }
        // Chat messages need Android's notification access (a settings screen, not a permission dialog).
        vm.localSources.firstOrNull { it.id == dev.jarvis.agent.ToolRegistry.CHATS }?.let { chats ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                    Text(chats.displayName, style = MaterialTheme.typography.bodyLarge)
                    Hint(chats.purpose + " Turn on \"JARVIS chat messages\" in notification access.")
                }
                if (status[chats.id] == ConnectorStatus.Connected) StatusChip("Allowed", ChipTone.GOOD)
                else OutlinedButton(onClick = {
                    runCatching { context.startActivity(dev.jarvis.messaging.JarvisNotificationListener.settingsIntent(context)) }
                    denied = "If Android says it's restricted: App settings → ⋮ → Allow restricted settings, then try again."
                }) { Text("Allow") }
            }
        }
        denied?.let {
            StatusMessage(it, error = true)
            TextButton(onClick = {
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(android.net.Uri.fromParts("package", context.packageName, null)),
                    )
                }
            }) { Text("Open app settings") }
        }
    }
}

private fun openCustomTab(context: Context, url: String) {
    CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, url.toUri())
}

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

private fun openCustomTab(context: Context, url: String) {
    CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, url.toUri())
}

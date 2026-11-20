package dev.jarvis.ui.settings

import android.content.Context
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jarvis.connectors.ConnectorStatus

@Composable
fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Anthropic key: paste, test (free GET /v1/models), save encrypted. The saved key is never displayed. */
@Composable
fun ApiKeySection(vm: SettingsViewModel, onKeyOk: () -> Unit = {}) {
    val hasKey by vm.hasApiKey.collectAsStateWithLifecycle()
    val message by vm.keyMessage.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val input = rememberTextFieldState()

    SectionCard("Anthropic API key") {
        Hint(if (hasKey) "A key is saved (encrypted with the Android Keystore)." else "No key saved yet. Create one at console.anthropic.com.")
        Text(
            "Set a monthly spend limit on your Anthropic key.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        OutlinedSecureTextField(
            state = input,
            label = { Text(if (hasKey) "Replace key" else "Paste key") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { vm.saveAndTestKey(input.text.toString()) { input.clearText(); onKeyOk() } },
                enabled = !busy && input.text.isNotBlank(),
            ) {
                Text("Test & save")
            }
            if (hasKey) {
                OutlinedButton(onClick = vm::testSavedKey, enabled = !busy) { Text("Test saved") }
                TextButton(onClick = vm::removeKey, enabled = !busy) { Text("Remove") }
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
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

    SectionCard("INDmoney") {
        Hint("Read-only access to net worth, holdings, SIPs and market data via INDmoney's MCP server. You sign in on INDmoney's own page; this app never sees your INDmoney credentials.")
        Text(
            when (status) {
                ConnectorStatus.Connected -> "Status: connected"
                ConnectorStatus.NotConnected -> "Status: not connected"
                ConnectorStatus.NeedsReauth -> "Status: sign-in expired"
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.startIndMoneySignIn { openCustomTab(context, it) } }, enabled = !busy) {
                Text(if (status == ConnectorStatus.NotConnected) "Sign in" else "Sign in again")
            }
            if (status != ConnectorStatus.NotConnected) {
                OutlinedButton(onClick = { vm.testIndMoney(onConnectorOk) }, enabled = !busy) { Text("Test") }
                TextButton(onClick = vm::signOutIndMoney, enabled = !busy) { Text("Sign out") }
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        TextButton(onClick = { showManual = !showManual }) { Text(if (showManual) "Hide advanced" else "Advanced: paste a token") }
        if (showManual) {
            Hint("Only if browser sign-in doesn't work. A pasted token can't be refreshed; you'll need to paste a new one when it expires.")
            OutlinedSecureTextField(
                state = manualToken,
                label = { Text("Access token") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = { vm.setIndMoneyManualToken(manualToken.text.toString()); manualToken.clearText() },
                enabled = !busy && manualToken.text.isNotBlank(),
            ) {
                Text("Save token")
            }
        }
    }
}

private fun openCustomTab(context: Context, url: String) {
    CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, url.toUri())
}

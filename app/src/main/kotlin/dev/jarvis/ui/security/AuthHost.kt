package dev.jarvis.ui.security

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jarvis.agent.AuthTier
import dev.jarvis.security.AuthCoordinator
import dev.jarvis.security.AuthMethod
import dev.jarvis.security.AuthPrompt
import kotlinx.coroutines.delay

/**
 * App-wide auth dialogs driven by [AuthCoordinator.prompt]. The fingerprint stage is shown by the
 * system prompt (launched from MainActivity), so it has no dialog here. The wording is neutral:
 * it never reveals what the protected data is.
 */
@Composable
fun AuthHost(auth: AuthCoordinator) {
    val prompt by auth.prompt.collectAsStateWithLifecycle()
    val p = prompt ?: return
    when (p.stage) {
        AuthPrompt.Stage.CHOOSE -> ChooseDialog(p, auth)
        AuthPrompt.Stage.PIN -> PinDialog(p, auth)
        AuthPrompt.Stage.CONFIRM -> ConfirmDialog(p, auth)
        AuthPrompt.Stage.BIOMETRIC -> Unit
    }
}

private fun AuthPrompt.title() = if (tier >= AuthTier.SENSITIVE) "That's sensitive." else "That's personal."

@Composable
private fun ChooseDialog(p: AuthPrompt, auth: AuthCoordinator) {
    var left by remember(p.id) { mutableIntStateOf(p.defaultInSeconds ?: 0) }
    LaunchedEffect(p.id) {
        while (left > 0) { delay(1000); left-- }
    }
    AlertDialog(
        onDismissRequest = { auth.cancel() },
        title = { Text(p.title()) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("PIN or fingerprint?")
                Text(
                    "Use PIN if you're alone. Use fingerprint if people are nearby: the answer is shown on screen only." +
                        if (left > 0) " Fingerprint in ${left}s." else "",
                    style = MaterialTheme.typography.bodySmall,
                )
                p.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { Button(onClick = { auth.choose(AuthMethod.BIOMETRIC) }) { Text("Fingerprint") } },
        dismissButton = {
            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                OutlinedButton(onClick = { auth.choose(AuthMethod.PIN) }, enabled = p.pinAvailable && !p.pinLocked) { Text("PIN") }
                TextButton(onClick = { auth.cancel() }) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun PinDialog(p: AuthPrompt, auth: AuthCoordinator) {
    val pin = rememberTextFieldState()
    val submit = {
        auth.submitPin(pin.text.toString())
        pin.clearText() // the PIN isn't kept in UI state after submitting
    }
    AlertDialog(
        onDismissRequest = { pin.clearText(); auth.cancel() },
        title = { Text("Enter your JARVIS PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedSecureTextField(
                    state = pin,
                    label = { Text("PIN") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                p.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { Button(onClick = submit) { Text("OK") } },
        dismissButton = {
            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                if (p.biometricAvailable) TextButton(onClick = { pin.clearText(); auth.choose(AuthMethod.BIOMETRIC) }) { Text("Use fingerprint") }
                TextButton(onClick = { pin.clearText(); auth.cancel() }) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun ConfirmDialog(p: AuthPrompt, auth: AuthCoordinator) {
    AlertDialog(
        onDismissRequest = { auth.confirm(false) },
        title = { Text("Allow this?") },
        text = { Text("JARVIS wants to use: " + p.confirmLabels.joinToString("; ") + ".") },
        confirmButton = { Button(onClick = { auth.confirm(true) }) { Text("Allow") } },
        dismissButton = { TextButton(onClick = { auth.confirm(false) }) { Text("Don't allow") } },
    )
}

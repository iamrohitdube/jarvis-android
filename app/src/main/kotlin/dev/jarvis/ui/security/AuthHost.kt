package dev.jarvis.ui.security

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jarvis.agent.AuthTier
import dev.jarvis.security.AuthCoordinator
import dev.jarvis.security.AuthMethod
import dev.jarvis.security.AuthPrompt
import dev.jarvis.ui.components.Hint
import dev.jarvis.ui.components.IconTile
import dev.jarvis.ui.components.StatusMessage
import dev.jarvis.ui.icons.AppIcons

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
    val total = (p.defaultInSeconds ?: 0).coerceAtLeast(1)
    // Countdown ring around the fingerprint icon: empties as the 6 s default approaches.
    var target by remember(p.id) { mutableFloatStateOf(1f) }
    LaunchedEffect(p.id) { target = 0f }
    val progress by animateFloatAsState(target, tween(total * 1000), label = "countdown")

    AlertDialog(
        onDismissRequest = { auth.cancel() },
        icon = {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { progress }, modifier = Modifier.size(64.dp), strokeWidth = 3.dp,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
                Icon(AppIcons.Fingerprint, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            }
        },
        title = { Text(p.title(), textAlign = TextAlign.Center) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("PIN or fingerprint?", style = MaterialTheme.typography.bodyLarge)
                Hint("PIN if you're alone. Fingerprint if people are nearby: the answer stays on screen only.", Modifier)
                StatusMessage(p.message, error = true)
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    FilledTonalButton(
                        onClick = { auth.choose(AuthMethod.PIN) }, enabled = p.pinAvailable && !p.pinLocked, modifier = Modifier.weight(1f),
                    ) {
                        Icon(AppIcons.Password, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("PIN")
                    }
                    Button(onClick = { auth.choose(AuthMethod.BIOMETRIC) }, modifier = Modifier.weight(1f)) {
                        Icon(AppIcons.Fingerprint, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Fingerprint")
                    }
                }
                TextButton(onClick = { auth.cancel() }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Cancel") }
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
        icon = { IconTile(AppIcons.Lock, MaterialTheme.colorScheme.tertiary, size = 48) },
        title = { Text("Enter your JARVIS PIN", textAlign = TextAlign.Center) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedSecureTextField(
                    state = pin,
                    label = { Text("PIN") },
                    shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    onKeyboardAction = { submit() },
                    modifier = Modifier.fillMaxWidth(),
                )
                StatusMessage(p.message, error = true)
            }
        },
        confirmButton = { Button(onClick = submit) { Text("Unlock") } },
        dismissButton = {
            Row {
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
        icon = { IconTile(AppIcons.VerifiedUser, MaterialTheme.colorScheme.primary, size = 48) },
        title = { Text("Allow this?", textAlign = TextAlign.Center) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("JARVIS wants to use:", color = MaterialTheme.colorScheme.onSurfaceVariant)
                p.confirmLabels.forEach { Text("• $it", style = MaterialTheme.typography.bodyLarge) }
            }
        },
        confirmButton = { Button(onClick = { auth.confirm(true) }) { Text("Allow") } },
        dismissButton = { TextButton(onClick = { auth.confirm(false) }) { Text("Don't allow") } },
    )
}

package dev.jarvis.ui.security

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jarvis.messaging.Channel
import dev.jarvis.messaging.SendCoordinator
import dev.jarvis.messaging.SendPrompt
import dev.jarvis.ui.components.Hint

/**
 * The send confirmation (CLAUDE.md section 7): the FULL draft, recipient and channel, then "Send it" or Cancel,
 * then a short countdown that can still be cancelled. Nothing is sent while this is open.
 */
@Composable
fun SendHost(coordinator: SendCoordinator) {
    val prompt by coordinator.prompt.collectAsStateWithLifecycle()
    val p = prompt ?: return
    if (p.recipient.channel == Channel.CALL) {
        // Voice calling (D36): who and which number, then Call or Cancel.
        AlertDialog(
            onDismissRequest = coordinator::cancel,
            title = { Text("Call ${p.recipient.name}?") },
            text = { Hint("${p.recipient.detail}. Say \"call\" or tap Call.") },
            confirmButton = { Button(onClick = { coordinator.approve(byVoice = false) }) { Text("Call") } },
            dismissButton = { TextButton(onClick = coordinator::cancel) { Text("Cancel") } },
        )
        return
    }
    val email = p.recipient.channel == Channel.EMAIL
    val channel = when (p.recipient.channel) {
        Channel.SMS -> "SMS"
        Channel.EMAIL -> if (p.recipient.thread != null) "reply" else "email"
        else -> p.recipient.detail
    }
    when (p.stage) {
        SendPrompt.Stage.CONFIRM -> AlertDialog(
            onDismissRequest = coordinator::cancel,
            title = { Text("Send this $channel?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("To ${p.recipient.name} (${p.recipient.detail})", style = MaterialTheme.typography.titleSmall)
                    if (email) Text("Subject: ${p.recipient.subject}", style = MaterialTheme.typography.bodyMedium)
                    p.recipient.thread?.let { Hint("Replying to their email \"${it.originalSubject}\", in the same thread.") }
                    Text("\"${p.text}\"", style = MaterialTheme.typography.bodyLarge)
                    Hint(
                        if (p.voiceAllowed) "Say \"send it\" or tap Send it. Nothing is sent otherwise."
                        else "Not on your trusted list, so tap Send it to confirm (voice isn't enough)."
                    )
                }
            },
            confirmButton = { Button(onClick = { coordinator.approve(byVoice = false) }) { Text("Send it") } },
            dismissButton = { TextButton(onClick = coordinator::cancel) { Text("Cancel") } },
        )
        SendPrompt.Stage.COUNTDOWN -> AlertDialog(
            onDismissRequest = coordinator::cancel,
            title = { Text("Sending in ${p.secondsLeft}…") },
            text = { Text("To ${p.recipient.name}: \"${p.text}\"") },
            confirmButton = { Button(onClick = coordinator::cancel) { Text("Cancel") } },
        )
    }
}

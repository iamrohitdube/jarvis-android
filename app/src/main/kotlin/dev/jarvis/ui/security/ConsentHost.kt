package dev.jarvis.ui.security

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jarvis.agent.ConsentCoordinator
import dev.jarvis.agent.ConsentFeatures
import dev.jarvis.ui.components.Hint

/** First redacted send of a feature (D29 step 6): shows EXACTLY what would go to Claude. */
@Composable
fun ConsentHost(coordinator: ConsentCoordinator) {
    val prompt by coordinator.prompt.collectAsStateWithLifecycle()
    val p = prompt ?: return
    AlertDialog(
        onDismissRequest = { coordinator.answer(false) },
        title = { Text("Send this to Claude?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Hint("First time for ${ConsentFeatures.label(p.group)}. This is exactly what would be sent, after the phone removed identifiers. You'll only see this once.")
                Text(
                    p.preview, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = { Button(onClick = { coordinator.answer(true) }) { Text("Send") } },
        dismissButton = { TextButton(onClick = { coordinator.answer(false) }) { Text("Don't send") } },
    )
}

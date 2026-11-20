package dev.jarvis.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jarvis.claude.BudgetLevel
import dev.jarvis.ui.common.FixAction

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onOpenSettings: () -> Unit,
    onOpenUsage: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenAudit: () -> Unit,
    vm: ChatViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val budget by vm.budget.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.text?.length) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("JARVIS") },
                actions = {
                    TextButton(onClick = vm::newChat) { Text("New") }
                    TextButton(onClick = onOpenHistory) { Text("History") }
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { menu = true }) { Text("⋮") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Usage") }, onClick = { menu = false; onOpenUsage() })
                            DropdownMenuItem(text = { Text("Audit log") }, onClick = { menu = false; onOpenAudit() })
                            DropdownMenuItem(text = { Text("Settings") }, onClick = { menu = false; onOpenSettings() })
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            when (budget) {
                BudgetLevel.WARNING -> Banner("Over 80% of this month's budget.", onOpenUsage)
                BudgetLevel.EXCEEDED -> Banner("Monthly budget exceeded.", onOpenUsage)
                else -> Unit
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.messages.isEmpty()) {
                    item { EmptyHint() }
                }
                items(state.messages, key = { it.id }) { msg ->
                    MessageBubble(
                        msg,
                        onFix = { action ->
                            when (action) {
                                FixAction.OPEN_SETTINGS -> onOpenSettings()
                                FixAction.RETRY -> vm.retry()
                                FixAction.NONE -> Unit
                            }
                        },
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Ask anything… e.g. \"what's my net worth?\"") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { vm.send(input); input = "" }),
                    maxLines = 4,
                )
                Spacer(Modifier.size(8.dp))
                if (state.busy) {
                    OutlinedButton(onClick = vm::stop) { Text("Stop") }
                } else {
                    Button(onClick = { vm.send(input); input = "" }, enabled = input.isNotBlank()) { Text("Send") }
                }
            }
        }
    }
}

@Composable
private fun Banner(text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onClick) { Text("View") }
    }
}

@Composable
private fun EmptyHint() {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("At your service.", style = MaterialTheme.typography.titleMedium)
        Text(
            "Try \"what's my net worth?\" once INDmoney is connected.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MessageBubble(msg: UiMessage, onFix: (FixAction) -> Unit) {
    val align = if (msg.fromUser) Alignment.CenterEnd else Alignment.CenterStart
    val bg = if (msg.fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (msg.fromUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Box(Modifier.fillMaxWidth(), contentAlignment = align) {
        Column(
            Modifier.widthIn(max = 520.dp)
                .background(bg, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (msg.text.isNotEmpty()) {
                SelectionContainer { Text(msg.text, color = fg) }
            } else if (msg.streaming) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            msg.dataLabel?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
            }
            msg.status?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.7f), modifier = Modifier.padding(top = 4.dp))
            }
            msg.error?.let { err ->
                Text(err.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                if (err.fix != FixAction.NONE && err.fixLabel != null) {
                    TextButton(onClick = { onFix(err.fix) }) { Text(err.fixLabel) }
                }
            }
        }
    }
}

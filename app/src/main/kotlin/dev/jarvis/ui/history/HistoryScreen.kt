package dev.jarvis.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jarvis.data.AuditCategory
import dev.jarvis.data.AuditEvent
import dev.jarvis.data.AuditLog
import dev.jarvis.data.db.ConversationSummary
import dev.jarvis.memory.HistoryStore
import dev.jarvis.ui.chat.CurrentConversation
import dev.jarvis.ui.common.formatDateTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val history: HistoryStore,
    private val current: CurrentConversation,
    private val audit: AuditLog,
) : ViewModel() {
    val query = MutableStateFlow("")

    val conversations: StateFlow<List<ConversationSummary>> = query.debounce(200)
        .flatMapLatest { history.conversations(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun open(id: Long) = current.open(id)

    fun delete(id: Long) = viewModelScope.launch {
        history.delete(id)
        if (current.id.value == id) current.open(null)
        audit.record(AuditEvent(AuditCategory.DATA, "delete_conversation", "ok"))
    }

    fun deleteAll() = viewModelScope.launch {
        history.deleteAll()
        current.open(null)
        audit.record(AuditEvent(AuditCategory.DATA, "delete_all_history", "ok"))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onBack: () -> Unit, onOpened: () -> Unit, vm: HistoryViewModel = hiltViewModel()) {
    val query by vm.query.collectAsStateWithLifecycle()
    val items by vm.conversations.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf<ConversationSummary?>(null) }
    var confirmAll by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("History") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = { TextButton(onClick = { confirmAll = true }, enabled = items.isNotEmpty()) { Text("Delete all") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query, onValueChange = { vm.query.value = it },
                placeholder = { Text("Search conversations") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
            )
            if (items.isEmpty()) {
                Text(
                    if (query.isBlank()) "No conversations yet. They're saved on this phone only." else "No matches.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.id }) { c ->
                    Card(Modifier.fillMaxWidth().clickable { vm.open(c.id); onOpened() }) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    formatDateTime(c.updatedAt) + " · " + c.messageCount + " message" + (if (c.messageCount == 1) "" else "s"),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { confirmDelete = c }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }

    confirmDelete?.let { c ->
        ConfirmDialog("Delete this conversation?", "\"${c.title}\" will be removed from this phone.", onConfirm = { vm.delete(c.id) }, onDismiss = { confirmDelete = null })
    }
    if (confirmAll) {
        ConfirmDialog("Delete all history?", "Every saved conversation will be removed from this phone. This can't be undone.", onConfirm = { vm.deleteAll() }, onDismiss = { confirmAll = false })
    }
}

@Composable
fun ConfirmDialog(title: String, text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

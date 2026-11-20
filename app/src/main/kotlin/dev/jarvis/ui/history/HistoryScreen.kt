package dev.jarvis.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
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
import dev.jarvis.ui.components.IconTile
import dev.jarvis.ui.components.JarvisOrb
import dev.jarvis.ui.components.OrbState
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
import dev.jarvis.ui.icons.AppIcons

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
                navigationIcon = { IconButton(onClick = onBack) { Icon(AppIcons.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { confirmAll = true }, enabled = items.isNotEmpty()) {
                        Icon(AppIcons.DeleteSweep, "Delete all")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TextField(
                value = query, onValueChange = { vm.query.value = it },
                placeholder = { Text("Search your chats") }, singleLine = true,
                leadingIcon = { Icon(AppIcons.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { vm.query.value = "" }) { Icon(AppIcons.Close, "Clear") }
                },
                shape = CircleShape,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (items.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(top = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    JarvisOrb(96.dp, state = OrbState.LOCKED)
                    Spacer(Modifier.height(16.dp))
                    Text(if (query.isBlank()) "No conversations yet" else "No matches", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (query.isBlank()) "Chats are saved on this phone only." else "Try another word from the chat.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.id }) { c ->
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth().animateItem().clip(MaterialTheme.shapes.large)
                            .clickable { vm.open(c.id); onOpened() },
                    ) {
                        Row(Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconTile(AppIcons.ChatBubbleOutline, size = 38)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    formatDateTime(c.updatedAt) + " · " + c.messageCount + " message" + (if (c.messageCount == 1) "" else "s"),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { confirmDelete = c }) {
                                Icon(AppIcons.DeleteOutline, "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
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

/** Destructive confirmation used across the app. Closes itself after either choice. */
@Composable
fun ConfirmDialog(title: String, text: String, onConfirm: () -> Unit, onDismiss: () -> Unit, confirmLabel: String = "Delete") {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Box(contentAlignment = Alignment.Center) { IconTile(AppIcons.DeleteOutline, MaterialTheme.colorScheme.error, size = 48) } },
        title = { Text(title, textAlign = TextAlign.Center) },
        text = { Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        confirmButton = {
            Button(
                onClick = { onConfirm(); onDismiss() },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

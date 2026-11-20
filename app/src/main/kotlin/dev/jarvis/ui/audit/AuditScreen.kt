package dev.jarvis.ui.audit

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jarvis.data.AuditCategory
import dev.jarvis.data.AuditLog
import dev.jarvis.data.db.AuditEntity
import dev.jarvis.ui.common.formatDateTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class AuditViewModel @Inject constructor(audit: AuditLog) : ViewModel() {
    val filter = MutableStateFlow<AuditCategory?>(null)

    val entries: StateFlow<List<AuditEntity>> = combine(audit.recent(), filter) { rows, f ->
        if (f == null) rows else rows.filter { it.category == f.name }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/** Read-only view of the audit log (CLAUDE.md section 6). Kept for 180 days; no content is stored. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuditScreen(onBack: () -> Unit, vm: AuditViewModel = hiltViewModel()) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Audit log") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } })
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = filter == null, onClick = { vm.filter.value = null }, label = { Text("All") })
                AuditCategory.entries.forEach { c ->
                    FilterChip(selected = filter == c, onClick = { vm.filter.value = c }, label = { Text(c.label()) })
                }
            }
            Text(
                "Every request, tool call and sign-in event, kept for 180 days. No message text or data is stored here.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            if (entries.isEmpty()) {
                Text("Nothing logged yet.", modifier = Modifier.padding(16.dp))
            }
            LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                items(entries, key = { it.id }) { e ->
                    AuditRow(e)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun AuditRow(e: AuditEntity) {
    val bad = e.outcome != "ok"
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row {
            Text(
                listOfNotNull(e.action.replace('_', ' '), e.tool).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
            )
            Text(
                e.outcome.replace('_', ' '), style = MaterialTheme.typography.labelMedium,
                color = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            listOfNotNull(formatDateTime(e.timestampMs), runCatching { AuditCategory.valueOf(e.category).label() }.getOrDefault(e.category), e.tier?.lowercase(), e.method, e.detail)
                .joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun AuditCategory.label() = when (this) {
    AuditCategory.REQUEST -> "Requests"
    AuditCategory.TOOL -> "Tools"
    AuditCategory.CONNECTOR -> "Connectors"
    AuditCategory.DATA -> "Data"
    AuditCategory.AUTH -> "Auth"
}

package dev.jarvis.ui.audit

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import dev.jarvis.ui.components.ChipTone
import dev.jarvis.ui.components.Hint
import dev.jarvis.ui.components.IconTile
import dev.jarvis.ui.components.JarvisOrb
import dev.jarvis.ui.components.OrbState
import dev.jarvis.ui.components.StatusChip
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import dev.jarvis.ui.icons.AppIcons

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
            TopAppBar(
                title = { Text("Audit log") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(AppIcons.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = filter == null, onClick = { vm.filter.value = null }, label = { Text("All") })
                AuditCategory.entries.forEach { c ->
                    FilterChip(
                        selected = filter == c, onClick = { vm.filter.value = c }, label = { Text(c.label()) },
                        leadingIcon = { Icon(c.icon(), null, modifier = Modifier.padding(0.dp)) },
                    )
                }
            }
            Hint(
                "Every request, tool call and security event, kept 180 days. No message text or data is stored here.",
                Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
            if (entries.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    JarvisOrb(88.dp, state = OrbState.LOCKED)
                    Spacer(Modifier.height(12.dp))
                    Text("Nothing logged yet", style = MaterialTheme.typography.titleMedium)
                }
            }
            LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(entries, key = { it.id }) { e -> AuditRow(e, Modifier.animateItem()) }
            }
        }
    }
}

@Composable
private fun AuditRow(e: AuditEntity, modifier: Modifier = Modifier) {
    val category = runCatching { AuditCategory.valueOf(e.category) }.getOrNull()
    val ok = e.outcome == "ok" || e.outcome == "shown" || e.outcome == "allowed"
    val bad = e.outcome.startsWith("error") || e.outcome in setOf("wrong", "locked", "failed", "denied", "refused", "timeout")
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(category?.icon() ?: AppIcons.Policy, category?.tint() ?: MaterialTheme.colorScheme.primary, size = 38)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(listOfNotNull(e.action.replace('_', ' ').replaceFirstChar { it.uppercase() }, e.tool).joinToString(" · "), style = MaterialTheme.typography.titleSmall)
                Text(
                    listOfNotNull(formatDateTime(e.timestampMs), e.tier?.lowercase(), e.method, e.detail).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            StatusChip(e.outcome.replace('_', ' '), when { ok -> ChipTone.GOOD; bad -> ChipTone.BAD; else -> ChipTone.NEUTRAL })
        }
    }
}

private fun AuditCategory.label() = when (this) {
    AuditCategory.REQUEST -> "Requests"
    AuditCategory.TOOL -> "Tools"
    AuditCategory.CONNECTOR -> "Connectors"
    AuditCategory.DATA -> "Data"
    AuditCategory.AUTH -> "Security"
}

private fun AuditCategory.icon(): ImageVector = when (this) {
    AuditCategory.REQUEST -> AppIcons.ChatBubbleOutline
    AuditCategory.TOOL -> AppIcons.Build
    AuditCategory.CONNECTOR -> AppIcons.Cable
    AuditCategory.DATA -> AppIcons.Storage
    AuditCategory.AUTH -> AppIcons.Lock
}

@Composable
private fun AuditCategory.tint(): Color = when (this) {
    AuditCategory.REQUEST -> MaterialTheme.colorScheme.primary
    AuditCategory.TOOL -> MaterialTheme.colorScheme.secondary
    AuditCategory.CONNECTOR -> MaterialTheme.colorScheme.secondary
    AuditCategory.DATA -> MaterialTheme.colorScheme.onSurfaceVariant
    AuditCategory.AUTH -> MaterialTheme.colorScheme.tertiary
}

package dev.jarvis.ui.usage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
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
import dev.jarvis.claude.BudgetLevel
import dev.jarvis.claude.UsageEntry
import dev.jarvis.claude.UsageMeter
import dev.jarvis.claude.UsageSummary
import dev.jarvis.data.SettingsRepository
import dev.jarvis.ui.settings.SectionCard
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class UsageViewModel @Inject constructor(private val meter: UsageMeter, settings: SettingsRepository) : ViewModel() {
    val summary: StateFlow<UsageSummary?> = combine(meter.entries, settings.settings) { e, s ->
        UsageSummary.of(e, s.monthlyBudgetUsd, Instant.now(), ZoneId.systemDefault())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun clear() = viewModelScope.launch { meter.clear() }
}

private val timeFmt = DateTimeFormatter.ofPattern("dd MMM HH:mm").withZone(ZoneId.systemDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageScreen(onBack: () -> Unit, vm: UsageViewModel = hiltViewModel()) {
    val summary by vm.summary.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Usage") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = { TextButton(onClick = vm::clear) { Text("Clear") } },
            )
        },
    ) { padding ->
        val s = summary ?: return@Scaffold
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                SectionCard("This month") {
                    Text("Estimated cost: $" + "%.4f".format(s.monthCostUsd) + " · ${s.monthRequests} requests")
                    if (s.budgetUsd > 0) {
                        LinearProgressIndicator(progress = { s.budgetFraction.toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        Text(
                            "Budget $" + "%.2f".format(s.budgetUsd) + " · " + "%.0f".format(s.budgetFraction * 100) + "% used" +
                                when (s.budgetLevel) {
                                    BudgetLevel.WARNING -> " · over 80%"
                                    BudgetLevel.EXCEEDED -> " · exceeded"
                                    else -> ""
                                },
                            color = if (s.budgetLevel >= BudgetLevel.WARNING) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                    } else {
                        Text("No budget set (Settings → Monthly budget).", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "Estimates from token counts and list prices; your Anthropic Console is authoritative.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            item { Text("Recent requests", style = MaterialTheme.typography.titleSmall) }
            items(s.entries.asReversed().take(100)) { EntryRow(it) }
        }
    }
}

@Composable
private fun EntryRow(e: UsageEntry) {
    Column(Modifier.fillMaxWidth()) {
        Text("${timeFmt.format(Instant.ofEpochMilli(e.timestampMs))} · ${e.model.removePrefix("claude-")} · ${e.mode}", style = MaterialTheme.typography.bodyMedium)
        Text(
            "in ${e.inputTokens} (cache r ${e.cacheReadTokens} / w ${e.cacheWriteTokens}) · out ${e.outputTokens} · " +
                (e.costUsd?.let { "$" + "%.4f".format(it) } ?: "cost n/a") +
                " · ${e.latencyMs} ms" + (e.timeToFirstTokenMs?.let { " (first token ${it} ms)" } ?: "") +
                (if (e.toolsUsed > 0) " · ${e.toolsUsed} tool calls" else ""),
            style = MaterialTheme.typography.bodySmall,
        )
        HorizontalDivider(Modifier.padding(top = 6.dp))
    }
}

package dev.jarvis.ui.usage

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
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
import dev.jarvis.ui.common.formatDateTime
import dev.jarvis.ui.components.ChipTone
import dev.jarvis.ui.components.Hint
import dev.jarvis.ui.components.SectionLabel
import dev.jarvis.ui.components.StatusChip
import dev.jarvis.ui.history.ConfirmDialog
import dev.jarvis.ui.theme.Brand
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import dev.jarvis.ui.icons.AppIcons

@HiltViewModel
class UsageViewModel @Inject constructor(private val meter: UsageMeter, settings: SettingsRepository) : ViewModel() {
    val summary: StateFlow<UsageSummary?> = combine(meter.entries, settings.settings) { e, s ->
        UsageSummary.of(e, s.monthlyBudgetUsd, Instant.now(), ZoneId.systemDefault())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun clear() = viewModelScope.launch { meter.clear() }
}

private fun usd(v: Double) = if (v < 0.01 && v > 0) "$" + "%.4f".format(v) else "$" + "%.2f".format(v)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageScreen(onBack: () -> Unit, vm: UsageViewModel = hiltViewModel()) {
    val summary by vm.summary.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Usage") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(AppIcons.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { confirmClear = true }) { Icon(AppIcons.DeleteSweep, "Clear usage log") } },
            )
        },
    ) { padding ->
        val s = summary ?: return@Scaffold
        val month = s.entries.filter { Instant.ofEpochMilli(it.timestampMs).atZone(ZoneId.systemDefault()).month == Instant.now().atZone(ZoneId.systemDefault()).month }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { HeroCard(s) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val avgLatency = month.map { it.latencyMs }.average().takeIf { !it.isNaN() }?.toLong()
                    val tokens = month.sumOf { it.inputTokens + it.outputTokens + it.cacheReadTokens + it.cacheWriteTokens }
                    val prompt = month.sumOf { it.inputTokens + it.cacheReadTokens + it.cacheWriteTokens }
                    val cachePct = if (prompt > 0) month.sumOf { it.cacheReadTokens } * 100 / prompt else 0
                    StatTile(Modifier.weight(1f), AppIcons.Speed, avgLatency?.let { "%.1fs".format(it / 1000.0) } ?: "—", "avg reply")
                    StatTile(Modifier.weight(1f), AppIcons.Memory, compact(tokens), "tokens")
                    StatTile(Modifier.weight(1f), AppIcons.Bolt, "$cachePct%", "from cache")
                }
            }
            item { SectionLabel("Recent requests") }
            if (s.entries.isEmpty()) item { Hint("Nothing yet. Ask JARVIS something.", Modifier.padding(horizontal = 6.dp)) }
            items(s.entries.asReversed().take(100)) { EntryRow(it) }
        }
    }
    if (confirmClear) {
        ConfirmDialog("Clear the usage log?", "Cost history on this phone will be reset. Your Anthropic bill isn't affected.",
            onConfirm = vm::clear, onDismiss = { confirmClear = false }, confirmLabel = "Clear")
    }
}

@Composable
private fun HeroCard(s: UsageSummary) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Brand.Blue.copy(alpha = 0.18f), Brand.Violet.copy(alpha = 0.06f))))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
                Text("This month", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(usd(s.monthCostUsd), style = MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.width(10.dp))
                    Text("${s.monthRequests} requests", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
                }
                if (s.budgetUsd > 0) {
                    val fraction by animateFloatAsState(s.budgetFraction.toFloat().coerceIn(0f, 1f), tween(900), label = "budget")
                    val barColor = when (s.budgetLevel) {
                        BudgetLevel.EXCEEDED -> MaterialTheme.colorScheme.error
                        BudgetLevel.WARNING -> Brand.Warning
                        else -> null
                    }
                    Box(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                        Box(
                            Modifier.fillMaxHeight().fillMaxWidth(fraction).clip(CircleShape).background(
                                barColor?.let { androidx.compose.ui.graphics.SolidColor(it) } ?: Brand.horizontal()
                            )
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("%.0f%% of ".format(s.budgetFraction * 100) + usd(s.budgetUsd) + " budget", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        when (s.budgetLevel) {
                            BudgetLevel.WARNING -> StatusChip("Over 80%", ChipTone.WARN)
                            BudgetLevel.EXCEEDED -> StatusChip("Used up", ChipTone.BAD)
                            else -> StatusChip("On track", ChipTone.GOOD)
                        }
                    }
                } else {
                    Hint("No budget set. Add one in Settings → Spending.")
                }
                Hint("Estimates from token counts and list prices; your Anthropic Console is authoritative.")
        }
    }
}

@Composable
private fun StatTile(modifier: Modifier, icon: ImageVector, value: String, label: String) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier) {
        Column(Modifier.padding(14.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.height(8.dp))
            Text(value, style = MaterialTheme.typography.titleLarge)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EntryRow(e: UsageEntry) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatDateTime(e.timestampMs), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(e.costUsd?.let { usd(it) } ?: "n/a", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusChip(e.model.removePrefix("claude-"))
                StatusChip(e.mode)
                if (e.toolsUsed > 0) StatusChip("${e.toolsUsed} tool", ChipTone.BRAND, AppIcons.Build)
            }
            Text(
                "in ${compact(e.inputTokens)} · cache ${compact(e.cacheReadTokens)} read / ${compact(e.cacheWriteTokens)} write · out ${compact(e.outputTokens)} · " +
                    "%.1fs".format(e.latencyMs / 1000.0) + (e.timeToFirstTokenMs?.let { " (first token %.1fs)".format(it / 1000.0) } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun compact(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fk".format(n / 1_000.0)
    else -> n.toString()
}

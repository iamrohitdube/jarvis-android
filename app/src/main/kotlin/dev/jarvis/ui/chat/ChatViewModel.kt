package dev.jarvis.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jarvis.agent.AgentEvent
import dev.jarvis.agent.AgentLoop
import dev.jarvis.agent.DataStamp
import dev.jarvis.agent.ToolRegistry
import dev.jarvis.claude.BudgetLevel
import dev.jarvis.claude.UsageMeter
import dev.jarvis.claude.UsageSummary
import dev.jarvis.data.SettingsRepository
import dev.jarvis.data.db.MessageEntity
import dev.jarvis.memory.HistoryStore
import dev.jarvis.ui.common.UiError
import dev.jarvis.ui.common.dataLabel
import dev.jarvis.ui.common.toUi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

data class UiMessage(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
    val streaming: Boolean = false,
    /** Short status line, e.g. "Checking INDmoney…" or "haiku-5-5 · $0.0003". */
    val status: String? = null,
    /** "Last updated" label for connector data used by this reply. */
    val dataLabel: String? = null,
    val error: UiError? = null,
)

data class ChatState(
    val messages: List<UiMessage> = emptyList(),
    val busy: Boolean = false,
)

/** The reply currently streaming (not yet in Room). */
private data class LiveReply(val text: String = "", val status: String? = null)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val agent: AgentLoop,
    private val settings: SettingsRepository,
    private val history: HistoryStore,
    private val current: CurrentConversation,
    usageMeter: UsageMeter,
) : ViewModel() {

    private val live = MutableStateFlow<LiveReply?>(null)
    private val busy = MutableStateFlow(false)
    /** Error (with fix button) for the most recent failed reply only; older failures show a plain label. */
    private val lastError = MutableStateFlow<Pair<Long, UiError>?>(null)
    private var job: Job? = null
    private var lastUserText: String? = null

    private val persisted = current.id.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else history.messages(id) }

    val state: StateFlow<ChatState> = combine(persisted, live, busy, lastError) { rows, liveReply, isBusy, err ->
        val msgs = rows.map { it.toUi(err) }.toMutableList()
        liveReply?.let { msgs += UiMessage(LIVE_ID, fromUser = false, text = it.text, streaming = true, status = it.status) }
        ChatState(msgs, isBusy)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatState())

    val budget: StateFlow<BudgetLevel> = combine(usageMeter.entries, settings.settings) { entries, s ->
        UsageSummary.of(entries, s.monthlyBudgetUsd, Instant.now(), ZoneId.systemDefault()).budgetLevel
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BudgetLevel.NONE)

    fun send(raw: String) {
        val text = raw.trim()
        if (text.isEmpty() || busy.value) return
        lastUserText = text
        busy.value = true
        lastError.value = null
        live.value = LiveReply()
        job = viewModelScope.launch {
            var conversationId: Long? = null
            var finished = false
            try {
                val id = current.id.value ?: history.startConversation(text).also { current.open(it) }
                conversationId = id
                val context = history.contextTurns(id) // before adding the new question
                history.addUserMessage(id, text)
                agent.run(context, text, settings.current()).collect { ev ->
                    when (ev) {
                        is AgentEvent.Routed -> live.update { it?.copy(status = routeLabel(ev)) }
                        is AgentEvent.ToolActivity -> live.update { it?.copy(status = toolLabel(ev.toolName)) }
                        is AgentEvent.TextDelta -> live.update { it?.copy(text = it.text + ev.text) }
                        is AgentEvent.Finished -> {
                            val body = if (ev.truncated) ev.text + "\n\n(Cut off at the length limit. Say \"continue\" for more.)" else ev.text
                            history.addAssistantMessage(id, body, ev.model, ev.costUsd, ev.dataStamps)
                            finished = true
                            live.value = null
                        }
                        is AgentEvent.Failed -> {
                            val msgId = history.addAssistantMessage(id, ev.partialText, failed = true)
                            lastError.value = msgId to ev.error.toUi()
                            finished = true
                            live.value = null
                        }
                    }
                }
            } finally {
                // Stopped mid-stream: keep what arrived, marked as not completed (never resent as context).
                val partial = live.value?.text.orEmpty()
                val id = conversationId
                if (!finished && id != null) {
                    withContext(NonCancellable) { history.addAssistantMessage(id, partial, failed = true) }
                }
                live.value = null
                busy.value = false
            }
        }
    }

    fun retry() { lastUserText?.let(::send) }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun newChat() {
        if (busy.value) stop()
        lastError.value = null
        current.open(null)
    }

    private fun MessageEntity.toUi(err: Pair<Long, UiError>?): UiMessage {
        val fromUser = role == HistoryStore.USER
        val error = err?.takeIf { it.first == id }?.second
        val status = when {
            fromUser -> null
            failed && error == null -> "Not completed"
            failed -> null
            else -> listOfNotNull(model?.removePrefix("claude-"), costUsd?.let { "$" + "%.4f".format(it) }).joinToString(" · ").ifEmpty { null }
        }
        return UiMessage(
            id = id, fromUser = fromUser, text = text, status = status,
            dataLabel = dataLabel(DataStamp.decode(dataSources)), error = error,
        )
    }

    private fun routeLabel(ev: AgentEvent.Routed): String {
        val short = ev.model.removePrefix("claude-")
        return when {
            ev.fromCache -> "$short · using saved INDmoney data"
            ev.toolNames.isEmpty() -> short
            else -> "$short · ${ev.toolNames.size} tool${if (ev.toolNames.size > 1) "s" else ""}"
        }
    }

    private fun toolLabel(name: String): String {
        val spec = ToolRegistry.byName(name)
        return if (spec?.connectorId == ToolRegistry.INDMONEY) "Checking INDmoney…" else "Using $name…"
    }

    private companion object {
        const val LIVE_ID = -1L
    }
}

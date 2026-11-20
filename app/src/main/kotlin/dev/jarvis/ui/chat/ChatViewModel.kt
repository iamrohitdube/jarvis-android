package dev.jarvis.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jarvis.agent.AgentEvent
import dev.jarvis.agent.AgentLoop
import dev.jarvis.agent.ChatTurn
import dev.jarvis.agent.ToolRegistry
import dev.jarvis.claude.BudgetLevel
import dev.jarvis.claude.UsageMeter
import dev.jarvis.claude.UsageSummary
import dev.jarvis.data.SettingsRepository
import dev.jarvis.ui.common.UiError
import dev.jarvis.ui.common.toUi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

data class UiMessage(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
    val streaming: Boolean = false,
    /** Short status line, e.g. "Checking INDmoney…" or "sonnet · ₹ tools". */
    val status: String? = null,
    val error: UiError? = null,
)

data class ChatState(
    val messages: List<UiMessage> = emptyList(),
    val busy: Boolean = false,
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val agent: AgentLoop,
    private val settings: SettingsRepository,
    usageMeter: UsageMeter,
) : ViewModel() {

    private val ids = AtomicLong(0)
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state

    /** Completed turns resent as context (text only). Phase 2 persists these in Room. */
    private val turns = mutableListOf<ChatTurn>()
    private var job: Job? = null
    private var lastUserText: String? = null

    val budget: StateFlow<BudgetLevel> = combine(usageMeter.entries, settings.settings) { entries, s ->
        UsageSummary.of(entries, s.monthlyBudgetUsd, Instant.now(), ZoneId.systemDefault()).budgetLevel
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BudgetLevel.NONE)

    fun send(raw: String) {
        val text = raw.trim()
        if (text.isEmpty() || _state.value.busy) return
        lastUserText = text
        val userMsg = UiMessage(ids.incrementAndGet(), fromUser = true, text = text)
        val replyId = ids.incrementAndGet()
        _state.update {
            it.copy(
                messages = it.messages + userMsg + UiMessage(replyId, fromUser = false, text = "", streaming = true),
                busy = true,
            )
        }
        val history = turns.toList()
        job = viewModelScope.launch {
            val s = settings.current()
            try {
                agent.run(history, text, s).collect { ev -> onEvent(replyId, text, ev) }
            } finally {
                _state.update { st ->
                    st.copy(busy = false, messages = st.messages.map { if (it.id == replyId) it.copy(streaming = false) else it })
                }
            }
        }
    }

    fun retry() { lastUserText?.let(::send) }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun onEvent(replyId: Long, userText: String, ev: AgentEvent) {
        when (ev) {
            is AgentEvent.Routed -> updateReply(replyId) { it.copy(status = routeLabel(ev.model, ev.toolNames)) }
            is AgentEvent.ToolActivity -> updateReply(replyId) { it.copy(status = toolLabel(ev.toolName)) }
            is AgentEvent.TextDelta -> updateReply(replyId) { it.copy(text = it.text + ev.text) }
            is AgentEvent.Finished -> {
                turns += ChatTurn(ChatTurn.Role.USER, userText)
                turns += ChatTurn(ChatTurn.Role.ASSISTANT, ev.text)
                val cost = ev.costUsd?.let { " · $" + "%.4f".format(it) } ?: ""
                updateReply(replyId) {
                    it.copy(
                        text = if (ev.truncated) ev.text + "\n\n(Cut off at the length limit. Say \"continue\" for more.)" else ev.text,
                        status = "${ev.usage.inputTokens + ev.usage.cacheReadInputTokens + ev.usage.cacheCreationInputTokens} in · ${ev.usage.outputTokens} out$cost",
                        streaming = false,
                    )
                }
            }
            is AgentEvent.Failed -> updateReply(replyId) {
                it.copy(text = ev.partialText, error = ev.error.toUi(), status = null, streaming = false)
            }
        }
    }

    private fun updateReply(id: Long, f: (UiMessage) -> UiMessage) {
        _state.update { st -> st.copy(messages = st.messages.map { if (it.id == id) f(it) else it }) }
    }

    private fun routeLabel(model: String, tools: List<String>): String {
        val short = model.removePrefix("claude-")
        return if (tools.isEmpty()) short else "$short · ${tools.size} tool${if (tools.size > 1) "s" else ""}"
    }

    private fun toolLabel(name: String): String {
        val spec = ToolRegistry.byName(name)
        return if (spec?.connectorId == ToolRegistry.INDMONEY) "Checking INDmoney…" else "Using $name…"
    }
}

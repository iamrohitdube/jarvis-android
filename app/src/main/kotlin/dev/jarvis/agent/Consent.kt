package dev.jarvis.agent

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Per-feature consent to send REDACTED personal data to Claude (D29 step 6). The default for every feature is
 * "never": nothing personal leaves the phone. With consent, the first send of each feature shows the exact text.
 */
interface ConsentGate {
    /** The user allowed this feature's data to go to Claude, redacted. */
    suspend fun allowed(group: ToolGroup): Boolean

    /** First send for [group]: show [preview] (exactly what would be sent) and ask. Later sends: true at once. */
    suspend fun confirmFirstSend(group: ToolGroup, preview: String): Boolean
}

/** The user-facing name of each feature in consent settings. */
object ConsentFeatures {
    val ALL = listOf(ToolGroup.FINANCE, ToolGroup.EMAIL, ToolGroup.CALENDAR, ToolGroup.FILES, ToolGroup.CALLS, ToolGroup.MESSAGING, ToolGroup.NOTIFICATIONS)

    fun label(g: ToolGroup) = when (g) {
        ToolGroup.FINANCE -> "Finance (INDmoney)"
        ToolGroup.EMAIL -> "Email"
        ToolGroup.CALENDAR -> "Calendar"
        ToolGroup.FILES -> "Drive files"
        ToolGroup.CALLS -> "Calls and call recordings"
        ToolGroup.MESSAGING -> "Texts and chat messages"
        ToolGroup.NOTIFICATIONS -> "Other app notifications"
        else -> g.name.lowercase()
    }

    /** What stays after redaction, per feature. */
    fun keeps(g: ToolGroup) = if (g == ToolGroup.FINANCE) "Amounts and asset names are kept; account and card numbers are removed."
    else "Names, subjects and text are kept; email addresses, phone and account numbers, codes, links and amounts are removed."

    fun encode(groups: Set<ToolGroup>) = groups.joinToString(",") { it.name }
    fun decode(s: String): Set<ToolGroup> = s.split(',').mapNotNull { n -> ToolGroup.entries.firstOrNull { it.name == n.trim() } }.toSet()
}

/** What the consent dialog shows. */
data class ConsentPrompt(val id: Long, val group: ToolGroup, val preview: String)

/** Bridge between the agent and the first-send preview dialog. */
class ConsentCoordinator(private val timeoutMs: Long = 120_000) {
    private val _prompt = MutableStateFlow<ConsentPrompt?>(null)
    val prompt: StateFlow<ConsentPrompt?> = _prompt
    private val answers = Channel<Boolean>(Channel.UNLIMITED)
    private var nextId = 0L

    fun answer(allow: Boolean) { answers.trySend(allow) }

    suspend fun ask(group: ToolGroup, preview: String): Boolean {
        while (answers.tryReceive().isSuccess) Unit
        _prompt.value = ConsentPrompt(++nextId, group, preview)
        try {
            return withTimeoutOrNull(timeoutMs) { answers.receive() } ?: false
        } finally {
            _prompt.value = null
        }
    }
}

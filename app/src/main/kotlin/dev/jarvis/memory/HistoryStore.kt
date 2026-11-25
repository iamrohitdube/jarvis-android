package dev.jarvis.memory

import dev.jarvis.agent.AgentLoop
import dev.jarvis.agent.ChatTurn
import dev.jarvis.agent.DataStamp
import dev.jarvis.data.db.ConversationEntity
import dev.jarvis.data.db.ConversationSummary
import dev.jarvis.data.db.HistoryDao
import dev.jarvis.data.db.MessageEntity
import kotlinx.coroutines.flow.Flow

/**
 * Full verbatim conversation log (CLAUDE.md section 5, layer 2). Local only, searchable and
 * user-deletable. The model only ever sees the recent turns [contextTurns] returns.
 */
class HistoryStore(
    private val dao: HistoryDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun startConversation(firstUserText: String): Long {
        val now = clock()
        return dao.insertConversation(ConversationEntity(title = titleFrom(firstUserText), createdAt = now, updatedAt = now))
    }

    suspend fun addUserMessage(conversationId: Long, text: String): Long = insert(
        MessageEntity(conversationId = conversationId, role = USER, text = text, createdAt = clock())
    )

    suspend fun addAssistantMessage(
        conversationId: Long,
        text: String,
        model: String? = null,
        costUsd: Double? = null,
        dataSources: List<DataStamp> = emptyList(),
        failed: Boolean = false,
        sensitive: Boolean = false,
        screenOnly: Boolean = false,
    ): Long = insert(
        MessageEntity(
            conversationId = conversationId, role = ASSISTANT, text = text, createdAt = clock(),
            model = model, costUsd = costUsd, dataSources = DataStamp.encode(dataSources), failed = failed,
            sensitive = sensitive, screenOnly = screenOnly,
        )
    )

    fun messages(conversationId: Long): Flow<List<MessageEntity>> = dao.observeMessages(conversationId)

    /**
     * Completed exchanges to resend as context: each user message followed by a successful reply.
     * Failed replies (and the questions they answered) are left out so the model doesn't see errors.
     */
    suspend fun contextTurns(conversationId: Long): List<ChatTurn> = toTurns(dao.messages(conversationId))

    fun conversations(query: String = ""): Flow<List<ConversationSummary>> =
        if (query.isBlank()) dao.observeConversations() else dao.searchConversations(likePattern(query.trim()))

    suspend fun delete(conversationId: Long) = dao.deleteConversation(conversationId)

    suspend fun deleteAll() = dao.deleteAll()

    private suspend fun insert(m: MessageEntity): Long {
        val id = dao.insertMessage(m)
        dao.touch(m.conversationId, m.createdAt)
        return id
    }

    companion object {
        const val USER = "user"
        const val ASSISTANT = "assistant"
        private const val TITLE_MAX = 60

        fun titleFrom(text: String): String {
            val line = text.trim().lineSequence().firstOrNull().orEmpty().replace(Regex("\\s+"), " ")
            return when {
                line.isEmpty() -> "New chat"
                line.length <= TITLE_MAX -> line
                else -> line.take(TITLE_MAX - 1).trimEnd() + "…"
            }
        }

        /** LIKE pattern with %, _ and \ escaped (the queries use ESCAPE '\'). */
        fun likePattern(query: String): String =
            "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

        fun toTurns(messages: List<MessageEntity>): List<ChatTurn> {
            val out = mutableListOf<ChatTurn>()
            for (i in messages.indices) {
                val m = messages[i]
                val next = messages.getOrNull(i + 1)
                if (m.role == USER && next != null && next.role == ASSISTANT && !next.failed && next.text.isNotBlank()) {
                    // Connector data answered on the phone (or any Tier 2 reply) never goes back to Claude (D29).
                    val personal = next.sensitive || (next.model == AgentLoop.LOCAL_MODEL && next.dataSources != null)
                    out += ChatTurn(ChatTurn.Role.USER, m.text, personal)
                    out += ChatTurn(ChatTurn.Role.ASSISTANT, next.text, personal)
                }
            }
            return out
        }
    }
}

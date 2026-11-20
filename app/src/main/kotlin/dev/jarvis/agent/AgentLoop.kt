package dev.jarvis.agent

import dev.jarvis.claude.AssembledMessage
import dev.jarvis.claude.LlmClient
import dev.jarvis.claude.LlmEvent
import dev.jarvis.claude.LlmException
import dev.jarvis.claude.McpServerConfig
import dev.jarvis.claude.Pricing
import dev.jarvis.claude.StreamErrorEvent
import dev.jarvis.claude.StreamProtocolException
import dev.jarvis.claude.StreamUpdate
import dev.jarvis.claude.Usage
import dev.jarvis.claude.UsageEntry
import dev.jarvis.claude.UsageMeter
import dev.jarvis.claude.UsageSummary
import dev.jarvis.claude.str
import dev.jarvis.connectors.Connector
import dev.jarvis.data.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneId

sealed interface AgentError {
    data object MissingKey : AgentError
    data object BadKey : AgentError
    data class ConnectorSignIn(val connectorId: String, val name: String) : AgentError
    data class RateLimited(val retryAfterSeconds: Long?) : AgentError
    data object Overloaded : AgentError
    data class Network(val detail: String) : AgentError
    data class Refused(val category: String?) : AgentError
    data class BudgetReached(val budgetUsd: Double) : AgentError
    data class Other(val detail: String) : AgentError
}

sealed interface AgentEvent {
    data class Routed(val model: String, val toolNames: List<String>) : AgentEvent
    data class TextDelta(val text: String) : AgentEvent
    data class ToolActivity(val toolName: String) : AgentEvent
    data class Finished(val text: String, val usage: Usage, val costUsd: Double?, val truncated: Boolean) : AgentEvent
    data class Failed(val error: AgentError, val partialText: String) : AgentEvent
}

/**
 * Runs one user turn: route -> attach connectors -> stream -> resume pause_turn -> log usage.
 * Policy decisions (which tools, which model) are made in code, never delegated to the prompt.
 */
class AgentLoop(
    private val llm: LlmClient,
    private val connectors: Map<String, Connector>,
    private val usageMeter: UsageMeter,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    fun run(history: List<ChatTurn>, userText: String, settings: AppSettings): Flow<AgentEvent> = flow {
        // Hard stop before spending anything once the local monthly estimate reaches the budget.
        if (settings.stopAtBudget && settings.monthlyBudgetUsd > 0) {
            val summary = UsageSummary.of(usageMeter.entries.first(), settings.monthlyBudgetUsd, Instant.ofEpochMilli(clock()), zone)
            if (summary.budgetFraction >= 1.0) {
                emit(AgentEvent.Failed(AgentError.BudgetReached(settings.monthlyBudgetUsd), ""))
                return@flow
            }
        }
        val route = ToolRouter.route(userText, settings)
        emit(AgentEvent.Routed(route.model, route.tools.map { it.name }))

        val servers = mutableListOf<McpServerConfig>()
        for (cid in route.connectorIds) {
            val connector = connectors[cid] ?: continue
            val server = connector.mcpServer()
            if (server == null) {
                emit(AgentEvent.Failed(AgentError.ConnectorSignIn(cid, connector.displayName), ""))
                return@flow
            }
            servers += server
        }

        val trimmedHistory = trimHistory(history)
        val text = StringBuilder()
        var usage = Usage()
        var model = route.model
        var ttft: Long? = null
        var tools = 0
        var paused: List<JsonObject>? = null
        var continuations = 0
        var authRetried = false
        val started = clock()

        try {
            while (true) {
                val request = PromptBuilder.build(route, trimmedHistory, userText, servers, settings.serverFallback, paused)
                var message: AssembledMessage? = null
                try {
                    llm.stream(request).collect { ev ->
                        when (ev) {
                            is LlmEvent.Update -> when (val u = ev.update) {
                                is StreamUpdate.TextDelta -> { text.append(u.text); emit(AgentEvent.TextDelta(u.text)) }
                                is StreamUpdate.ToolStarted -> { tools++; emit(AgentEvent.ToolActivity(u.name)) }
                                is StreamUpdate.ToolFinished -> Unit
                            }
                            is LlmEvent.Completed -> { message = ev.message; if (ttft == null) ttft = ev.timeToFirstTokenMs }
                        }
                    }
                } catch (e: LlmException.InvalidRequest) {
                    if (!authRetried && servers.isNotEmpty() && looksLikeMcpAuthError(e.apiMessage)) {
                        authRetried = true
                        if (refreshServers(route, servers)) continue
                        val c = route.connectorIds.firstNotNullOfOrNull { connectors[it] }
                        emitFailure(AgentError.ConnectorSignIn(c?.id ?: "", c?.displayName ?: "Connector"), text)
                        return@flow
                    }
                    throw e
                }

                val msg = message ?: throw StreamProtocolException("No message received")
                usage += msg.usage
                msg.model?.let { model = it }
                if (msg.stopReason == "pause_turn" && continuations < MAX_CONTINUATIONS) {
                    continuations++
                    paused = paused.orEmpty() + msg.content
                    continue
                }
                when (msg.stopReason) {
                    "refusal" -> emitFailure(AgentError.Refused(msg.stopDetails?.str("category")), text)
                    else -> emit(
                        AgentEvent.Finished(
                            text = text.toString().ifBlank { "(No reply.)" },
                            usage = usage,
                            costUsd = Pricing.cost(model, usage),
                            truncated = msg.stopReason == "max_tokens",
                        )
                    )
                }
                break
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmException) {
            emitFailure(mapError(e), text)
        } catch (e: StreamErrorEvent) {
            emitFailure(if (e.errorType == "overloaded_error") AgentError.Overloaded else AgentError.Other(e.message ?: e.errorType), text)
        } catch (e: StreamProtocolException) {
            emitFailure(AgentError.Network(e.message ?: "Stream interrupted"), text)
        } finally {
            if (usage != Usage()) {
                val entry = UsageEntry(
                    timestampMs = started, model = model, mode = route.mode.id,
                    inputTokens = usage.inputTokens, outputTokens = usage.outputTokens,
                    cacheWriteTokens = usage.cacheCreationInputTokens, cacheReadTokens = usage.cacheReadInputTokens,
                    costUsd = Pricing.cost(model, usage), latencyMs = clock() - started,
                    timeToFirstTokenMs = ttft, toolsUsed = tools,
                )
                withContext(NonCancellable) { usageMeter.record(entry) }
            }
        }
    }

    private suspend fun FlowCollector<AgentEvent>.emitFailure(error: AgentError, text: StringBuilder) =
        emit(AgentEvent.Failed(error, text.toString()))

    private suspend fun refreshServers(route: RouteDecision, servers: MutableList<McpServerConfig>): Boolean {
        servers.clear()
        for (cid in route.connectorIds) {
            val c = connectors[cid] ?: continue
            if (!c.onAuthRejected()) return false
            servers += c.mcpServer() ?: return false
        }
        return true
    }

    companion object {
        const val MAX_CONTINUATIONS = 3
        /** Completed turns resent per request (4 exchanges; context compaction lands in Phase 7). */
        const val MAX_HISTORY_TURNS = 8
        /** Older turns are clipped to this many characters when resent; the current question never is. */
        const val MAX_TURN_CHARS = 1200

        /**
         * Keeps the most recent turns, guarantees the list starts with a user turn, and clips long
         * old turns (keeping their start) so one long answer doesn't get re-billed on every request.
         */
        fun trimHistory(history: List<ChatTurn>): List<ChatTurn> =
            history.takeLast(MAX_HISTORY_TURNS)
                .dropWhile { it.role != ChatTurn.Role.USER }
                .map { if (it.text.length > MAX_TURN_CHARS) it.copy(text = it.text.take(MAX_TURN_CHARS) + " […]") else it }

        /** Heuristic: the API reports MCP server auth failures as 400s mentioning the server/auth. */
        fun looksLikeMcpAuthError(message: String): Boolean {
            val m = message.lowercase()
            val mentionsMcp = "mcp" in m || "server" in m
            val mentionsAuth = listOf("401", "403", "unauthor", "authenticat", "authoriz", "token", "forbidden").any { it in m }
            return mentionsMcp && mentionsAuth
        }

        fun mapError(e: LlmException): AgentError = when (e) {
            is LlmException.MissingKey -> AgentError.MissingKey
            is LlmException.Unauthorized -> AgentError.BadKey
            is LlmException.RateLimited -> AgentError.RateLimited(e.retryAfterSeconds)
            is LlmException.Overloaded -> AgentError.Overloaded
            is LlmException.Network -> AgentError.Network(e.message ?: "network error")
            is LlmException.Server -> AgentError.Other("Anthropic server error (${e.code}). Try again shortly.")
            is LlmException.PermissionDenied -> AgentError.Other(e.message ?: "Permission denied")
            is LlmException.InvalidRequest -> AgentError.Other(e.apiMessage)
        }
    }
}

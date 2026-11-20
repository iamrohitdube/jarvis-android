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
import dev.jarvis.data.AuditCategory
import dev.jarvis.data.AuditEvent
import dev.jarvis.data.AuditSink
import dev.jarvis.data.SnapshotStore
import dev.jarvis.data.db.SnapshotEntity
import dev.jarvis.security.AccessController
import dev.jarvis.security.AccessResult
import dev.jarvis.security.AuthMethodParser
import dev.jarvis.security.DenyReason
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
    data class AccessDenied(val reason: DenyReason, val toolNames: List<String>) : AgentError
    data class Other(val detail: String) : AgentError
}

sealed interface AgentEvent {
    data class Routed(val model: String, val toolNames: List<String>, val fromCache: Boolean = false) : AgentEvent
    data class TextDelta(val text: String) : AgentEvent
    data class ToolActivity(val toolName: String) : AgentEvent
    data class Finished(
        val text: String,
        val usage: Usage,
        val costUsd: Double?,
        val truncated: Boolean,
        val model: String? = null,
        /** Connector data the reply used, with fetch times, for the "last updated" label. */
        val dataStamps: List<DataStamp> = emptyList(),
        /** Contains Tier 2 data: hide at rest until unlocked. */
        val sensitive: Boolean = false,
        /** Authorised by fingerprint/device credential: show, don't speak. */
        val screenOnly: Boolean = false,
    ) : AgentEvent
    data class Failed(val error: AgentError, val partialText: String) : AgentEvent
}

/**
 * Runs one user turn: budget check -> route -> reuse fresh snapshots or attach connectors -> stream
 * -> resume pause_turn -> save snapshots -> log usage and audit. Policy decisions (which tools, which
 * model, whether cached data is reused) are made in code, never delegated to the prompt.
 */
class AgentLoop(
    private val llm: LlmClient,
    private val connectors: Map<String, Connector>,
    private val usageMeter: UsageMeter,
    /** Security gate (policy + PIN/fingerprint). Required: there is no unguarded default. */
    private val access: AccessController,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val snapshots: SnapshotStore? = null,
    private val audit: AuditSink = AuditSink.NONE,
) {
    fun run(history: List<ChatTurn>, userText: String, settings: AppSettings): Flow<AgentEvent> = flow {
        // Hard stop before spending anything once the local monthly estimate reaches the budget.
        if (settings.stopAtBudget && settings.monthlyBudgetUsd > 0) {
            val summary = UsageSummary.of(usageMeter.entries.first(), settings.monthlyBudgetUsd, Instant.ofEpochMilli(clock()), zone)
            if (summary.budgetFraction >= 1.0) {
                auditRequest("budget_stop", null, null)
                emit(AgentEvent.Failed(AgentError.BudgetReached(settings.monthlyBudgetUsd), ""))
                return@flow
            }
        }
        // "net worth, fingerprint": the method word picks the auth path and is not sent to the model.
        val (question, preferredMethod) = AuthMethodParser.extract(userText)
        var route = ToolRouter.route(question, settings)

        // Security gate: policy + auth before any tool or saved tool data is used (CLAUDE.md section 6).
        var sensitive = false
        var screenOnly = false
        if (route.tools.isNotEmpty()) {
            when (val decision = access.authorize(route.tools, preferredMethod)) {
                is AccessResult.Granted -> {
                    route = route.copy(tools = decision.tools, groups = decision.tools.map { it.group }.toSet())
                    sensitive = decision.sensitive
                    screenOnly = decision.screenOnly
                }
                is AccessResult.Denied -> {
                    auditRequest("denied:" + decision.reason.name.lowercase(), route, null)
                    emit(AgentEvent.Failed(AgentError.AccessDenied(decision.reason, decision.toolNames), ""))
                    return@flow
                }
            }
        }

        // Reuse fresh snapshots instead of calling the connector (saves the MCP round-trip tokens).
        var cached: List<SnapshotEntity> = emptyList()
        if (snapshots != null && route.tools.isNotEmpty() && !ToolRouter.wantsFresh(question)) {
            snapshots.freshFor(route.tools, settings.snapshotMaxAgeMinutes * 60_000L)?.let { fresh ->
                cached = fresh
                route = route.copy(groups = emptySet(), tools = emptyList())
            }
        }
        emit(AgentEvent.Routed(route.model, route.tools.map { it.name }, fromCache = cached.isNotEmpty()))

        val servers = mutableListOf<McpServerConfig>()
        for (cid in route.connectorIds) {
            val connector = connectors[cid] ?: continue
            val server = connector.mcpServer()
            if (server == null) {
                auditRequest("connector_signin_needed", route, cid)
                emit(AgentEvent.Failed(AgentError.ConnectorSignIn(cid, connector.displayName), ""))
                return@flow
            }
            servers += server
        }

        val now = clock()
        val cachedData = cached.map { CachedData(displayName(it.connectorId), it.tool, ageLabel(now - it.fetchedAt), it.content) }
        val trimmedHistory = trimHistory(history)
        val text = StringBuilder()
        val allContent = mutableListOf<JsonObject>()
        var usage = Usage()
        var model = route.model
        var ttft: Long? = null
        var tools = 0
        var paused: List<JsonObject>? = null
        var continuations = 0
        var authRetried = false
        var outcome = "cancelled"
        val started = clock()

        try {
            while (true) {
                val request = PromptBuilder.build(route, trimmedHistory, question, servers, settings.serverFallback, paused, cachedData)
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
                        outcome = "connector_signin_needed"
                        emitFailure(AgentError.ConnectorSignIn(c?.id ?: "", c?.displayName ?: "Connector"), text)
                        return@flow
                    }
                    throw e
                }

                val msg = message ?: throw StreamProtocolException("No message received")
                usage += msg.usage
                msg.model?.let { model = it }
                allContent += msg.content
                if (msg.stopReason == "pause_turn" && continuations < MAX_CONTINUATIONS) {
                    continuations++
                    paused = paused.orEmpty() + msg.content
                    continue
                }
                when (msg.stopReason) {
                    "refusal" -> {
                        outcome = "refused"
                        emitFailure(AgentError.Refused(msg.stopDetails?.str("category")), text)
                    }
                    else -> {
                        val stamps = recordToolCalls(ToolCalls.extract(allContent)) + cached.map {
                            DataStamp(displayName(it.connectorId), it.tool, it.fetchedAt, cached = true)
                        }
                        cached.forEach {
                            audit.record(AuditEvent(AuditCategory.TOOL, "cache_hit", "ok", tool = it.tool, tier = tierOf(it.tool), detail = "cached"))
                        }
                        val truncated = msg.stopReason == "max_tokens"
                        outcome = if (truncated) "truncated" else "ok"
                        emit(
                            AgentEvent.Finished(
                                text = text.toString().ifBlank { "(No reply.)" },
                                usage = usage,
                                costUsd = Pricing.cost(model, usage),
                                truncated = truncated,
                                model = model,
                                dataStamps = stamps,
                                sensitive = sensitive,
                                screenOnly = screenOnly,
                            )
                        )
                    }
                }
                break
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmException) {
            outcome = "error:" + e.javaClass.simpleName
            emitFailure(mapError(e), text)
        } catch (e: StreamErrorEvent) {
            outcome = "error:" + e.errorType
            emitFailure(if (e.errorType == "overloaded_error") AgentError.Overloaded else AgentError.Other(e.message ?: e.errorType), text)
        } catch (e: StreamProtocolException) {
            outcome = "error:stream"
            emitFailure(AgentError.Network(e.message ?: "Stream interrupted"), text)
        } finally {
            withContext(NonCancellable) {
                if (usage != Usage()) {
                    usageMeter.record(
                        UsageEntry(
                            timestampMs = started, model = model, mode = route.mode.id,
                            inputTokens = usage.inputTokens, outputTokens = usage.outputTokens,
                            cacheWriteTokens = usage.cacheCreationInputTokens, cacheReadTokens = usage.cacheReadInputTokens,
                            costUsd = Pricing.cost(model, usage), latencyMs = clock() - started,
                            timeToFirstTokenMs = ttft, toolsUsed = tools,
                        )
                    )
                }
                auditRequest(outcome, route, null, model)
            }
        }
    }

    /** Audits each tool call, saves whole-account results as snapshots, and returns live data stamps. */
    private suspend fun recordToolCalls(calls: List<ToolCall>): List<DataStamp> {
        val stamps = LinkedHashMap<String, DataStamp>()
        val now = clock()
        for (call in calls) {
            val spec = ToolRegistry.byName(call.name)
            audit.record(
                AuditEvent(
                    AuditCategory.TOOL, "call", if (call.isError) "error" else "ok",
                    tool = call.name, tier = spec?.tier?.name, detail = "live",
                )
            )
            if (call.isError || spec == null || spec.helper) continue
            val connectorId = spec.connectorId ?: continue
            if (spec.snapshot && call.hasNoArguments && !call.resultText.isNullOrBlank()) {
                snapshots?.save(connectorId, call.name, call.resultText, now)
            }
            stamps[call.name] = DataStamp(displayName(connectorId), call.name, now, cached = false)
        }
        return stamps.values.toList()
    }

    private suspend fun auditRequest(outcome: String, route: RouteDecision?, connectorId: String?, model: String? = route?.model) {
        audit.record(
            AuditEvent(
                AuditCategory.REQUEST, "message", outcome,
                tool = connectorId, detail = listOfNotNull(model?.removePrefix("claude-"), route?.mode?.id).joinToString(" · ").ifEmpty { null },
            )
        )
    }

    private fun displayName(connectorId: String) = connectors[connectorId]?.displayName ?: connectorId

    private fun tierOf(tool: String) = ToolRegistry.byName(tool)?.tier?.name

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

        /** "just now", "4 min ago", "2 h ago". */
        fun ageLabel(ageMs: Long): String {
            val min = ageMs.coerceAtLeast(0) / 60_000
            return when {
                min < 1 -> "just now"
                min < 60 -> "$min min ago"
                else -> "${min / 60} h ago"
            }
        }

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

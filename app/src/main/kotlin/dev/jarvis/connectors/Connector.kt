package dev.jarvis.connectors

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

sealed interface ConnectorStatus {
    data object NotConnected : ConnectorStatus
    data object Connected : ConnectorStatus
    /** Token expired and could not be refreshed: the user must sign in again. */
    data object NeedsReauth : ConnectorStatus
}

sealed interface ConnectorTestResult {
    data object Ok : ConnectorTestResult
    data class Failed(val message: String, val needsReauth: Boolean) : ConnectorTestResult
}

/** One tool call made by the phone. */
sealed interface ConnectorCall {
    /** [text] is the result as text (structured JSON when the server sends it). [toolError]: the tool itself failed. */
    data class Ok(val text: String, val toolError: Boolean) : ConnectorCall
    /** Not signed in, or the token was rejected and couldn't be refreshed. */
    data object NeedsSignIn : ConnectorCall
    data class Failed(val message: String) : ConnectorCall
}

/**
 * A data source the PHONE calls itself (CLAUDE.md: Connector interface, swappable). Tokens stay inside the
 * connector and go only to its own service, never to Anthropic (D29).
 */
interface Connector {
    val id: String
    val displayName: String
    val status: Flow<ConnectorStatus>

    /** Calls one tool, refreshing the token once if the service rejects it. */
    suspend fun callTool(name: String, arguments: JsonObject): ConnectorCall

    /** Tool definitions (name, description, input schema) for [names], from the service; empty if unavailable. */
    suspend fun toolDefinitions(names: Collection<String>): List<McpToolDef>

    /** Cheap connectivity/auth check that does not call Claude. */
    suspend fun test(): ConnectorTestResult

    suspend fun signOut()
}

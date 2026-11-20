package dev.jarvis.connectors

import dev.jarvis.claude.McpServerConfig
import kotlinx.coroutines.flow.Flow

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

/** A data source the agent can use (CLAUDE.md: Connector interface, swappable). */
interface Connector {
    val id: String
    val displayName: String
    val status: Flow<ConnectorStatus>

    /** MCP server config with a currently valid token (refreshing if needed), or null if not connected. */
    suspend fun mcpServer(): McpServerConfig?

    /** Called when the API reports the MCP server rejected the token: try one refresh. */
    suspend fun onAuthRejected(): Boolean

    /** Cheap connectivity/auth check that does not call Claude. */
    suspend fun test(): ConnectorTestResult

    suspend fun signOut()
}

package dev.jarvis.claude

/**
 * A remote MCP server for the Messages API MCP connector (beta `mcp-client-2025-11-20`).
 * [authorizationToken] is a secret: it goes only into the request body sent to Anthropic over TLS
 * and is redacted by [toString].
 */
data class McpServerConfig(
    val name: String,
    val url: String,
    val authorizationToken: String?,
) {
    override fun toString() = "McpServerConfig(name=$name, url=$url, token=${if (authorizationToken != null) "<redacted>" else "none"})"

    companion object {
        const val BETA = "mcp-client-2025-11-20"
    }
}

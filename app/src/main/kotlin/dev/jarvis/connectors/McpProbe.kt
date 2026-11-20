package dev.jarvis.connectors

import dev.jarvis.claude.McpServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Checks an MCP server token directly from the phone with a JSON-RPC `initialize` (streamable HTTP),
 * so connector tests cost no Claude tokens.
 */
class McpProbe(private val http: OkHttpClient) {
    sealed interface Result {
        data object Ok : Result
        data object Unauthorized : Result
        data class Error(val message: String) : Result
    }

    suspend fun initialize(server: McpServerConfig): Result = withContext(Dispatchers.IO) {
        val body = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"$PROTOCOL_VERSION","capabilities":{},"clientInfo":{"name":"jarvis-android","version":"0.1"}}}"""
        val builder = Request.Builder().url(server.url)
            .header("Accept", "application/json, text/event-stream")
            .post(body.toRequestBody("application/json".toMediaType()))
        server.authorizationToken?.let { builder.header("Authorization", "Bearer $it") }
        try {
            http.newCall(builder.build()).execute().use { resp ->
                when {
                    resp.isSuccessful -> Result.Ok
                    resp.code == 401 || resp.code == 403 -> Result.Unauthorized
                    else -> Result.Error("${server.name} returned HTTP ${resp.code}")
                }
            }
        } catch (e: IOException) {
            Result.Error("Couldn't reach ${server.name}: ${e.message ?: "network error"}")
        }
    }

    private companion object {
        const val PROTOCOL_VERSION = "2025-06-18"
    }
}

package dev.jarvis.connectors

import dev.jarvis.claude.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * A remote MCP server the PHONE talks to directly (D29). [token] is a secret: it is only ever sent to [url]
 * (never to Anthropic) and is redacted by [toString].
 */
data class McpEndpoint(val name: String, val url: String, val token: String?) {
    override fun toString() = "McpEndpoint(name=$name, url=$url, token=${if (token != null) "<redacted>" else "none"})"
}

/** A tool as the server describes it (`tools/list`). */
data class McpToolDef(val name: String, val description: String, val inputSchema: JsonObject)

/** Result of one MCP call made by the app. */
sealed interface McpResult<out T> {
    data class Ok<T>(val value: T) : McpResult<T>
    /** HTTP 401/403: the token was rejected. */
    data object Unauthorized : McpResult<Nothing>
    data class Error(val message: String) : McpResult<Nothing>
}

/** A `tools/call` result: the text parts, the optional structured content, and the tool's own error flag. */
data class McpToolResult(val text: String, val structured: JsonElement?, val isError: Boolean) {
    /** Structured content when the server sends it (the most parseable form), else the text. */
    val bestText: String get() = structured?.toString() ?: text
}

/**
 * Minimal MCP client over Streamable HTTP (JSON-RPC 2.0 POSTs; the reply is JSON or a short SSE stream).
 * Hand-rolled on OkHttp instead of the MCP Kotlin SDK, which would add Ktor for three methods (D29).
 * Sessions: `initialize` once per endpoint+token, then `Mcp-Session-Id` and `MCP-Protocol-Version` on every call;
 * a 404 on a session means it expired, so the call re-initializes once.
 */
class McpClient(
    private val http: OkHttpClient,
    private val json: Json,
) {
    private class Session(val key: String, val id: String?, val protocolVersion: String)

    private val ids = AtomicLong(1)
    private val mutex = Mutex()
    private val sessions = mutableMapOf<String, Session>()

    /** Checks the token with `initialize` (no tool call, no Claude tokens). */
    suspend fun initialize(endpoint: McpEndpoint): McpResult<Unit> =
        when (val r = session(endpoint, forceNew = true)) {
            is McpResult.Ok -> McpResult.Ok(Unit)
            is McpResult.Unauthorized -> r
            is McpResult.Error -> r
        }

    suspend fun listTools(endpoint: McpEndpoint): McpResult<List<McpToolDef>> =
        request(endpoint, "tools/list", buildJsonObject { }) { result ->
            (result["tools"] as? JsonArray).orEmpty().mapNotNull { t ->
                val o = t as? JsonObject ?: return@mapNotNull null
                val name = o.str("name") ?: return@mapNotNull null
                McpToolDef(name, o.str("description").orEmpty(), (o["inputSchema"] as? JsonObject) ?: EMPTY_SCHEMA)
            }
        }

    suspend fun callTool(endpoint: McpEndpoint, name: String, arguments: JsonObject): McpResult<McpToolResult> =
        request(endpoint, "tools/call", buildJsonObject { put("name", name); put("arguments", arguments) }) { result ->
            val text = (result["content"] as? JsonArray).orEmpty()
                .mapNotNull { b -> (b as? JsonObject)?.takeIf { it.str("type") == "text" }?.str("text") }
                .joinToString("\n")
            McpToolResult(text, result["structuredContent"]?.takeIf { it is JsonObject || it is JsonArray }, isErrorFlag(result))
        }

    /** Forget sessions (sign-out, or a new token). */
    suspend fun reset() = mutex.withLock { sessions.clear() }

    private suspend fun <T> request(
        endpoint: McpEndpoint, method: String, params: JsonObject, parse: (JsonObject) -> T,
    ): McpResult<T> {
        var retried = false
        while (true) {
            val session = when (val s = session(endpoint, forceNew = false)) {
                is McpResult.Ok -> s.value
                is McpResult.Unauthorized -> return s
                is McpResult.Error -> return s
            }
            when (val r = post(endpoint, rpc(method, params), session)) {
                is Reply.Ok -> {
                    val error = r.body["error"] as? JsonObject
                    if (error != null) return McpResult.Error("${endpoint.name}: ${error.str("message") ?: "error"}")
                    val result = r.body["result"] as? JsonObject ?: return McpResult.Error("${endpoint.name}: empty reply")
                    return runCatching { McpResult.Ok(parse(result)) }.getOrElse { McpResult.Error("${endpoint.name}: unreadable reply") }
                }
                Reply.Unauthorized -> { forget(endpoint); return McpResult.Unauthorized }
                Reply.SessionExpired -> {
                    forget(endpoint)
                    if (retried) return McpResult.Error("${endpoint.name}: session expired")
                    retried = true
                }
                is Reply.Failed -> return McpResult.Error(r.message)
            }
        }
    }

    private suspend fun session(endpoint: McpEndpoint, forceNew: Boolean): McpResult<Session> = mutex.withLock {
        val key = keyOf(endpoint)
        if (!forceNew) sessions[endpoint.url]?.takeIf { it.key == key }?.let { return@withLock McpResult.Ok(it) }
        val init = rpc("initialize", buildJsonObject {
            put("protocolVersion", PROTOCOL_VERSION)
            putJsonObject("capabilities") { }
            putJsonObject("clientInfo") { put("name", "jarvis-android"); put("version", "0.2") }
        })
        when (val r = post(endpoint, init, null)) {
            is Reply.Ok -> {
                val result = r.body["result"] as? JsonObject
                    ?: return@withLock McpResult.Error("${endpoint.name}: ${(r.body["error"] as? JsonObject)?.str("message") ?: "initialize failed"}")
                val session = Session(key, r.sessionId, result.str("protocolVersion") ?: PROTOCOL_VERSION)
                // Required notification after initialize; servers reply 202 with no body.
                post(endpoint, buildJsonObject { put("jsonrpc", "2.0"); put("method", "notifications/initialized") }, session)
                sessions[endpoint.url] = session
                McpResult.Ok(session)
            }
            Reply.Unauthorized -> McpResult.Unauthorized
            Reply.SessionExpired -> McpResult.Error("${endpoint.name}: initialize failed (HTTP 404)")
            is Reply.Failed -> McpResult.Error(r.message)
        }
    }

    private suspend fun forget(endpoint: McpEndpoint) = mutex.withLock { sessions.remove(endpoint.url) }

    private sealed interface Reply {
        class Ok(val body: JsonObject, val sessionId: String?) : Reply
        data object Unauthorized : Reply
        data object SessionExpired : Reply
        class Failed(val message: String) : Reply
    }

    private suspend fun post(endpoint: McpEndpoint, message: JsonObject, session: Session?): Reply = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(endpoint.url)
            .header("Accept", "application/json, text/event-stream")
            .post(message.toString().toRequestBody(JSON_MEDIA))
        endpoint.token?.let { builder.header("Authorization", "Bearer $it") }
        session?.id?.let { builder.header("Mcp-Session-Id", it) }
        session?.let { builder.header("MCP-Protocol-Version", it.protocolVersion) }
        val id = (message["id"] as? JsonPrimitive)?.content
        try {
            http.newCall(builder.build()).execute().use { resp ->
                when {
                    resp.code == 401 || resp.code == 403 -> Reply.Unauthorized
                    resp.code == 404 && session?.id != null -> Reply.SessionExpired
                    !resp.isSuccessful -> Reply.Failed("${endpoint.name} returned HTTP ${resp.code}")
                    id == null -> Reply.Ok(JsonObject(emptyMap()), resp.header("Mcp-Session-Id")) // notification
                    else -> {
                        val raw = resp.body.string()
                        val isSse = resp.header("Content-Type").orEmpty().contains("text/event-stream")
                        val body = (if (isSse) fromSse(raw, id) else parse(raw))
                            ?: return@use Reply.Failed("${endpoint.name}: unreadable reply")
                        Reply.Ok(body, resp.header("Mcp-Session-Id"))
                    }
                }
            }
        } catch (e: IOException) {
            Reply.Failed("Couldn't reach ${endpoint.name}: ${e.message ?: "network error"}")
        }
    }

    /** The JSON-RPC response with our id among the SSE `data:` events (servers may send notifications first). */
    internal fun fromSse(raw: String, id: String): JsonObject? {
        val events = mutableListOf<String>()
        val current = StringBuilder()
        for (line in raw.lineSequence()) {
            when {
                line.startsWith("data:") -> { if (current.isNotEmpty()) current.append('\n'); current.append(line.removePrefix("data:").trimStart()) }
                line.isBlank() -> { if (current.isNotEmpty()) events += current.toString(); current.setLength(0) }
            }
        }
        if (current.isNotEmpty()) events += current.toString()
        return events.mapNotNull(::parse).firstOrNull { (it["id"] as? JsonPrimitive)?.content == id && ("result" in it || "error" in it) }
    }

    private fun parse(raw: String): JsonObject? = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()

    private fun rpc(method: String, params: JsonObject) = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", ids.getAndIncrement()); put("method", method); put("params", params)
    }

    private fun isErrorFlag(result: JsonObject) = (result["isError"] as? JsonPrimitive)?.content == "true"

    /** Sessions are tied to the token: a refreshed token starts a new session. Only a hash is kept. */
    private fun keyOf(endpoint: McpEndpoint) = endpoint.url + "#" + endpoint.token.hashCode()

    companion object {
        const val PROTOCOL_VERSION = "2025-11-25"
        private val JSON_MEDIA = "application/json".toMediaType()
        private val EMPTY_SCHEMA = buildJsonObject { put("type", "object") }
    }
}

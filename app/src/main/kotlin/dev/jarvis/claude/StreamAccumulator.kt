package dev.jarvis.claude

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Token usage for one Messages API response (cumulative values from the final message_delta). */
data class Usage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheCreationInputTokens: Long = 0,
    val cacheReadInputTokens: Long = 0,
) {
    operator fun plus(o: Usage) = Usage(
        inputTokens + o.inputTokens,
        outputTokens + o.outputTokens,
        cacheCreationInputTokens + o.cacheCreationInputTokens,
        cacheReadInputTokens + o.cacheReadInputTokens,
    )
}

/** High-level events the rest of the app cares about, derived from raw SSE events. */
sealed interface StreamUpdate {
    data class TextDelta(val text: String) : StreamUpdate
    /** A server-side tool (MCP / web) started; [name] is the tool name, e.g. networth_snapshot. */
    data class ToolStarted(val name: String, val serverName: String?) : StreamUpdate
    data class ToolFinished(val isError: Boolean) : StreamUpdate
}

/** Final assembled response once message_stop arrives. */
data class AssembledMessage(
    val model: String?,
    /** Full content blocks, exactly as the API sent them (needed to resume a pause_turn). */
    val content: List<JsonObject>,
    val stopReason: String?,
    val stopDetails: JsonObject?,
    val usage: Usage,
) {
    val text: String
        get() = content.filter { it.str("type") == "text" }.joinToString("") { it.str("text").orEmpty() }
}

class StreamProtocolException(message: String) : Exception(message)

/** The API sent an `error` event mid-stream (e.g. overloaded_error). */
class StreamErrorEvent(val errorType: String, message: String) : Exception(message)

/**
 * Rebuilds content blocks from streaming events (text, thinking, signature and JSON-input deltas).
 * Blocks it does not understand are kept as-is so they can be passed back unchanged.
 */
class StreamAccumulator(private val json: Json) {
    private class Builder(var block: JsonObject) {
        val text = StringBuilder()
        val thinking = StringBuilder()
        val signature = StringBuilder()
        val partialJson = StringBuilder()
        var sawText = false
        var sawThinking = false
        var sawSignature = false
        var sawJson = false
    }

    private val blocks = sortedMapOf<Int, Builder>()
    private var model: String? = null
    private var stopReason: String? = null
    private var stopDetails: JsonObject? = null
    private var usage = Usage()
    var finished = false
        private set

    /** Processes one SSE event and returns UI-level updates (possibly none). */
    fun onEvent(event: SseEvent): List<StreamUpdate> {
        val obj = runCatching { json.parseToJsonElement(event.data).jsonObject }.getOrNull()
            ?: return emptyList()
        return when (obj.str("type") ?: event.event) {
            "message_start" -> {
                val msg = obj["message"]?.jsonObject
                model = msg?.str("model")
                msg?.get("usage")?.let { usage = mergeUsage(usage, it.jsonObject) }
                emptyList()
            }
            "content_block_start" -> {
                val index = obj.int("index") ?: return emptyList()
                val block = obj["content_block"]?.jsonObject ?: return emptyList()
                blocks[index] = Builder(block)
                when (block.str("type")) {
                    "mcp_tool_use", "server_tool_use" ->
                        listOf(StreamUpdate.ToolStarted(block.str("name") ?: "tool", block.str("server_name")))
                    "mcp_tool_result" -> listOf(StreamUpdate.ToolFinished(block.bool("is_error")))
                    else -> if (block.str("type")?.endsWith("_tool_result") == true) {
                        listOf(StreamUpdate.ToolFinished(false))
                    } else emptyList()
                }
            }
            "content_block_delta" -> {
                val b = blocks[obj.int("index") ?: return emptyList()] ?: return emptyList()
                val delta = obj["delta"]?.jsonObject ?: return emptyList()
                when (delta.str("type")) {
                    "text_delta" -> {
                        val t = delta.str("text").orEmpty()
                        b.text.append(t); b.sawText = true
                        if (t.isNotEmpty()) listOf(StreamUpdate.TextDelta(t)) else emptyList()
                    }
                    "thinking_delta" -> { b.thinking.append(delta.str("thinking").orEmpty()); b.sawThinking = true; emptyList() }
                    "signature_delta" -> { b.signature.append(delta.str("signature").orEmpty()); b.sawSignature = true; emptyList() }
                    "input_json_delta" -> { b.partialJson.append(delta.str("partial_json").orEmpty()); b.sawJson = true; emptyList() }
                    else -> emptyList() // citations_delta etc.: ignored in Phase 1
                }
            }
            "content_block_stop" -> {
                val index = obj.int("index") ?: return emptyList()
                blocks[index]?.let { finalize(it) }
                emptyList()
            }
            "message_delta" -> {
                obj["delta"]?.jsonObject?.let { d ->
                    d.str("stop_reason")?.let { stopReason = it }
                    (d["stop_details"] as? JsonObject)?.let { stopDetails = it }
                }
                (obj["usage"] as? JsonObject)?.let { usage = mergeUsage(usage, it) }
                emptyList()
            }
            "message_stop" -> { finished = true; emptyList() }
            "error" -> {
                val err = obj["error"]?.jsonObject
                throw StreamErrorEvent(err?.str("type") ?: "error", err?.str("message") ?: "Stream error")
            }
            else -> emptyList() // ping and future event types
        }
    }

    fun result(): AssembledMessage {
        if (!finished) throw StreamProtocolException("Stream ended before message_stop")
        blocks.values.forEach { finalize(it) }
        return AssembledMessage(model, blocks.values.map { it.block }, stopReason, stopDetails, usage)
    }

    private fun finalize(b: Builder) {
        val base = b.block
        b.block = buildJsonObject {
            base.forEach { (k, v) -> put(k, v) }
            if (b.sawText) put("text", JsonPrimitive(base.str("text").orEmpty() + b.text))
            if (b.sawThinking) put("thinking", JsonPrimitive(base.str("thinking").orEmpty() + b.thinking))
            if (b.sawSignature) put("signature", JsonPrimitive(b.signature.toString()))
            if (b.sawJson) {
                val raw = b.partialJson.toString()
                val parsed: JsonElement = if (raw.isBlank()) JsonObject(emptyMap())
                else runCatching { json.parseToJsonElement(raw) }.getOrElse { JsonObject(emptyMap()) }
                put("input", parsed)
            }
        }
        b.text.setLength(0); b.thinking.setLength(0); b.signature.setLength(0); b.partialJson.setLength(0)
        b.sawText = false; b.sawThinking = false; b.sawSignature = false; b.sawJson = false
    }

    private fun mergeUsage(current: Usage, u: JsonObject) = Usage(
        inputTokens = u.long("input_tokens") ?: current.inputTokens,
        outputTokens = u.long("output_tokens") ?: current.outputTokens,
        cacheCreationInputTokens = u.long("cache_creation_input_tokens") ?: current.cacheCreationInputTokens,
        cacheReadInputTokens = u.long("cache_read_input_tokens") ?: current.cacheReadInputTokens,
    )
}

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

internal fun JsonObject.bool(key: String): Boolean =
    (this[key] as? JsonPrimitive)?.jsonPrimitive?.contentOrNull == "true"

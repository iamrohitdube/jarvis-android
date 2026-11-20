package dev.jarvis.agent

import dev.jarvis.claude.bool
import dev.jarvis.claude.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One MCP tool call the API ran for us, paired with its result (from the assembled content blocks). */
data class ToolCall(
    val id: String,
    val name: String,
    val serverName: String?,
    val input: JsonObject?,
    val resultText: String?,
    val isError: Boolean,
) {
    /** True when the call had no meaningful arguments, so its result is a whole-account snapshot. */
    val hasNoArguments: Boolean
        get() = input == null || input.values.all { it is JsonNull || (it is JsonPrimitive && it.isString && it.content.isBlank()) }
}

object ToolCalls {
    /** Pairs `mcp_tool_use` blocks with their `mcp_tool_result` blocks, in call order. */
    fun extract(content: List<JsonObject>): List<ToolCall> {
        val results = content.filter { it.str("type") == "mcp_tool_result" }.associateBy { it.str("tool_use_id") }
        return content.filter { it.str("type") == "mcp_tool_use" }.mapNotNull { use ->
            val id = use.str("id") ?: return@mapNotNull null
            val result = results[id]
            ToolCall(
                id = id,
                name = use.str("name") ?: "tool",
                serverName = use.str("server_name"),
                input = use["input"] as? JsonObject,
                resultText = result?.let(::resultText),
                isError = result?.bool("is_error") ?: (result == null),
            )
        }
    }

    /** Result content is a string or a list of content blocks; we keep the text parts. */
    private fun resultText(result: JsonObject): String? = when (val c = result["content"]) {
        is JsonPrimitive -> c.content
        is JsonArray -> c.mapNotNull { b -> (b as? JsonObject)?.takeIf { it.str("type") == "text" }?.str("text") }
            .joinToString("\n").ifEmpty { null }
        else -> null
    }
}

/**
 * Where a reply's connector data came from and how old it is, for the "last updated" label.
 * Stored compactly on the message row (see [encode]).
 */
data class DataStamp(val source: String, val tool: String, val updatedAt: Long, val cached: Boolean) {
    companion object {
        fun encode(stamps: List<DataStamp>): String? =
            stamps.takeIf { it.isNotEmpty() }?.joinToString(";") { "${it.source.clean()}|${it.tool.clean()}|${it.updatedAt}|${if (it.cached) "cached" else "live"}" }

        fun decode(raw: String?): List<DataStamp> = raw.orEmpty().split(";").mapNotNull { part ->
            val f = part.split("|")
            if (f.size != 4) return@mapNotNull null
            DataStamp(f[0], f[1], f[2].toLongOrNull() ?: return@mapNotNull null, f[3] == "cached")
        }

        private fun String.clean() = replace("|", "").replace(";", "")
    }
}

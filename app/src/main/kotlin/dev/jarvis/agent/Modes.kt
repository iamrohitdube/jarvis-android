package dev.jarvis.agent

object Models {
    const val SONNET = "claude-sonnet-5-5"
    const val HAIKU = "claude-haiku-5-5"
    const val OPUS = "claude-opus-5-5"
    /** Cost-first defaults (docs/decisions.md D11): Haiku 5.5 for everything; Sonnet is opt-in in Settings. */
    const val DEFAULT_CHAT = HAIKU
    const val DEFAULT_QUICK = HAIKU

    /** Models that accept the server-side refusal fallback (`fallbacks: "default"`). */
    private val FALLBACK_CAPABLE = setOf(SONNET, OPUS, "claude-fable-5-2", "claude-fable-5-1", "claude-opus-5")
    fun supportsServerFallback(model: String) = model in FALLBACK_CAPABLE

    /**
     * Models that accept `thinking: {type: "disabled"}` at our effort levels. Sonnet 5.5 rejects it
     * (wants `between_tools`) and Fable/Opus 5.5 can't turn thinking off, so only Haiku 5.5 is listed.
     */
    fun supportsThinkingOff(model: String) = model == HAIKU
}

enum class OutputStyle { NORMAL, SHORT }

/**
 * A mode is a config object (CLAUDE.md section 3). Phase 1 has Chat plus an internal Quick preset
 * used for routing simple lookups to the cheaper model. Briefing/Monitor/Riding come later.
 */
data class Mode(
    val id: String,
    val instructions: String,
    val allowedGroups: Set<ToolGroup>,
    val maxTokens: Int,
    /** output_config.effort: low for chat-style replies (fewer thinking tokens, lower latency). */
    val effort: String,
    val style: OutputStyle,
    /** Turn thinking off where the model allows it (saves output tokens on simple replies). */
    val thinkingOff: Boolean = false,
)

object Modes {
    /** The user explicitly asked for a longer answer ("tell me more", "explain"). Still capped. */
    val DETAIL = Mode(
        id = "detail",
        instructions = "Mode: detail. The user asked for more: answer fully but compactly, at most about 6 spoken sentences.",
        allowedGroups = setOf(ToolGroup.FINANCE, ToolGroup.EMAIL, ToolGroup.CALENDAR),
        maxTokens = 1500,
        effort = "low",
        style = OutputStyle.NORMAL,
    )

    val CHAT = Mode(
        id = "chat",
        instructions = "Mode: chat. Use tools when the question needs the user's own data or live market data.",
        allowedGroups = setOf(ToolGroup.FINANCE, ToolGroup.EMAIL, ToolGroup.CALENDAR),
        // Thinking and tool calls count toward this cap; the spoken answer itself is short (persona).
        maxTokens = 1200,
        effort = "low",
        style = OutputStyle.NORMAL,
    )

    /** Short tool-free questions: no tools, no thinking (on Haiku), tight cap. Selected by [ToolRouter]. */
    val QUICK = Mode(
        id = "quick",
        instructions = "Mode: chat.",
        allowedGroups = emptySet(),
        // No thinking here, so this is all answer: ~200 words, far more than 1-2 sentences need.
        maxTokens = 300,
        effort = "low",
        style = OutputStyle.SHORT,
        thinkingOff = true,
    )
}

package dev.jarvis.agent

import dev.jarvis.data.AppSettings

/** What to send for one request: which mode/model and which tools (lazy tool loading). */
data class RouteDecision(
    val mode: Mode,
    val model: String,
    val groups: Set<ToolGroup>,
    val tools: List<ToolSpec>,
) {
    val connectorIds: Set<String> get() = tools.mapNotNull { it.connectorId }.toSet()
}

/**
 * Cheap, local, deterministic routing (no LLM call): picks tool groups and tools by keyword, and
 * sends short tool-free questions to the quick model. Keeps tool definitions out of requests that
 * don't need them (CLAUDE.md section 2: load tools lazily).
 */
object ToolRouter {
    private const val SIMPLE_MAX_CHARS = 120

    /** Phrases that mean "give me a longer answer" (replies are short by default: voice-first). */
    private val DETAIL_PHRASES = listOf(
        "tell me more", "say more", "more detail", "in detail", "details", "explain", "elaborate",
        "go on", "break it down", "step by step",
    )

    fun wantsDetail(normalizedText: String): Boolean = DETAIL_PHRASES.any { normalizedText.contains(it) }

    /** Phrases that mean "don't use saved data, fetch it now". */
    private val FRESH_PHRASES = listOf("refresh", "latest", "live", "right now", "up to date", "up-to-date", "fresh", "update")

    fun wantsFresh(userText: String): Boolean = userText.lowercase().let { t -> FRESH_PHRASES.any { t.contains(it) } }

    fun route(userText: String, settings: AppSettings, base: Mode = Modes.CHAT): RouteDecision {
        val text = " " + userText.lowercase().replace(Regex("\\s+"), " ") + " "
        if (base == Modes.CHAT && wantsDetail(text)) {
            val tools = selectTools(text, Modes.DETAIL.allowedGroups)
            return RouteDecision(Modes.DETAIL, settings.chatModel, tools.map { it.group }.toSet(), tools)
        }
        val tools = selectTools(text, base.allowedGroups)
        val groups = tools.map { it.group }.toSet()
        if (tools.isEmpty() && settings.routeSimpleToQuick && userText.length <= SIMPLE_MAX_CHARS) {
            return RouteDecision(Modes.QUICK, settings.quickModel, emptySet(), emptyList())
        }
        return RouteDecision(base, settings.chatModel, groups, tools)
    }

    internal fun selectTools(text: String, allowed: Set<ToolGroup>): List<ToolSpec> {
        val candidates = ToolRegistry.all.filter { it.group in allowed }
        val matched = candidates.filter { spec -> spec.keywords.any { text.contains(it) } }
        val groupsHit = allowed.filter { g ->
            ToolRegistry.groupKeywords[g].orEmpty().any { text.contains(it) } || matched.any { it.group == g }
        }.toSet()
        if (groupsHit.isEmpty()) return emptyList()

        val selected = LinkedHashSet<ToolSpec>()
        for (g in groupsHit) {
            val inGroup = matched.filter { it.group == g }
            if (inGroup.isEmpty()) selected += candidates.filter { it.group == g && it.groupDefault }
            else selected += inGroup
        }
        // Helpers (e.g. name -> id lookup) ride along with any tool from the same connector.
        val connectors = selected.mapNotNull { it.connectorId }.toSet()
        selected += candidates.filter { it.helper && it.connectorId in connectors }
        return selected.toList()
    }
}

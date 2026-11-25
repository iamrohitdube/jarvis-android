package dev.jarvis.agent

import dev.jarvis.connectors.McpToolDef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Smart routing (D29, option B): Claude categorises every question and gets this tool for anything that needs the
 * user's own data (money, email). Calling it hands the question back to the phone, which runs the security gate and answers
 * ON THE PHONE. Claude never receives the result: the turn ends with the phone's answer.
 */
object PrivateDataTool {
    const val NAME = "private_data"

    enum class Kind(val wire: String, val tools: List<String>) {
        NET_WORTH("net_worth", listOf("networth_snapshot")),
        BANK_BALANCE("bank_balance", listOf("networth_snapshot")),
        HOLDINGS("holdings", listOf("networth_holdings")),
        SIPS("sips", listOf("mf_sips", "indian_stocks_sips")),
        WATCHLIST("watchlist", listOf("user_watchlist")),
        UNREAD_EMAIL("unread_email", listOf("gmail_unread")),
        RECENT_EMAIL("recent_email", listOf("gmail_recent")),
        EMAIL_SEARCH("email_search", listOf("gmail_search")),
        CALENDAR_TODAY("calendar_today", listOf("calendar_today")),
        CALENDAR_UPCOMING("calendar_upcoming", listOf("calendar_upcoming")),
        CALENDAR_SEARCH("calendar_search", listOf("calendar_search")),
    }

    val definition = McpToolDef(
        name = NAME,
        description = "Hand the question to the user's phone when answering needs the user's OWN data: their money, " +
            "email or calendar. The phone answers the user privately and you will NOT see the result, so call it without " +
            "writing any text first, and never guess or invent the user's numbers or emails. Kinds: net_worth (total, " +
            "invested, returns, profit or loss overall, allocation, liabilities); bank_balance (bank, savings, cash, " +
            "deposits); holdings (the stocks or funds they own, a single holding's value or P&L); sips (their SIPs, " +
            "monthly investments, next instalment); watchlist; unread_email (unread count, new emails); recent_email " +
            "(latest emails in the inbox); email_search (emails from a sender or about a topic: put the sender or " +
            "topic in item); calendar_today (today's events, \"my day\"); calendar_upcoming (next meeting, this " +
            "week, tomorrow); calendar_search (when a specific event is: put its name in item). Pass several kinds " +
            "if the question covers several. For one specific stock or fund they own, pass its name as item. Don't " +
            "use it for public market questions (prices, fund facts) or general questions.",
        inputSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("kinds") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "string")
                        putJsonArray("enum") { Kind.entries.forEach { add(it.wire) } }
                    }
                    put("description", "What the user wants to know.")
                }
                putJsonObject("item") {
                    put("type", "string")
                    put("description", "Optional: one stock or fund the user owns, or for email_search the sender or topic (a few words).")
                }
            }
            putJsonArray("required") { add("kinds") }
        },
    )

    /** Kinds from the tool input ("kinds" array, or a single "kind"); defaults to the net worth summary. */
    fun kindsOf(input: JsonObject?): Set<Kind> {
        val wires = when (val k = input?.get("kinds")) {
            is JsonArray -> k.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> listOfNotNull(k.contentOrNull)
            else -> listOfNotNull((input?.get("kind") as? JsonPrimitive)?.contentOrNull)
        }
        return wires.mapNotNull { w -> Kind.entries.firstOrNull { it.wire == w } }.toSet().ifEmpty { setOf(Kind.NET_WORTH) }
    }

    fun itemOf(input: JsonObject?): String? =
        (input?.get("item") as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.take(80)

    /** The phone-side tools for [kinds], without duplicates. */
    fun toolsFor(kinds: Set<Kind>): List<ToolSpec> =
        kinds.flatMap { it.tools }.distinct().mapNotNull { ToolRegistry.byName(it) }
}

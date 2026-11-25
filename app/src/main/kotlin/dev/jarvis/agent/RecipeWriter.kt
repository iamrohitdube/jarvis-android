package dev.jarvis.agent

import dev.jarvis.claude.LlmClient
import dev.jarvis.claude.LlmEvent
import dev.jarvis.claude.LlmRequest
import dev.jarvis.claude.Usage
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** A reply template from Claude, before the phone fills in the values. */
data class RecipeDraft(val text: String, val usage: Usage, val model: String, val latencyMs: Long)

/** Writes recipe answers (D32). Swappable so the agent can be tested without a model. */
fun interface RecipeWriter {
    /** [outline] is [DataOutline] text: structure only, never values. Null when no recipe could be written. */
    suspend fun write(question: String, item: String?, outline: String, model: String): RecipeDraft?
}

/**
 * Asks Claude for a recipe. The request holds only the user's question, the item Claude itself handed back, and
 * the data outline: no amounts, dates, names or account numbers (tests check the request body).
 */
class ClaudeRecipeWriter(
    private val llm: LlmClient,
    private val clock: () -> Long = System::currentTimeMillis,
) : RecipeWriter {
    override suspend fun write(question: String, item: String?, outline: String, model: String): RecipeDraft? {
        val started = clock()
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", MAX_TOKENS)
            put("stream", true)
            putJsonObject("cache_control") { put("type", "ephemeral") }
            putJsonArray("system") { addJsonObject { put("type", "text"); put("text", SYSTEM) } }
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", buildString {
                        append("Question: ").append(question.take(500)).append('\n')
                        item?.let { append("Item the question is about: ").append(it.take(80)).append(" (also available as \$item)\n") }
                        append("\nData outline (values hidden):\n").append(outline.take(MAX_OUTLINE))
                    })
                }
            }
            putJsonObject("output_config") { put("effort", "low") }
            if (Models.supportsThinkingOff(model)) putJsonObject("thinking") { put("type", "disabled") }
        }
        var draft: RecipeDraft? = null
        llm.stream(LlmRequest(body)).collect { e ->
            if (e is LlmEvent.Completed) {
                draft = RecipeDraft(e.message.text.trim(), e.message.usage, e.message.model ?: model, clock() - started)
            }
        }
        return draft?.takeIf { it.text.isNotBlank() }
    }

    companion object {
        private const val MAX_TOKENS = 500
        private const val MAX_OUTLINE = 6000

        val SYSTEM = """
            You write JARVIS's short spoken reply about the user's OWN data (money, cards, investments). You cannot see the values: you get the question and the data's structure. Write the reply as text with placeholders; the phone fills them in.
            Never write a number, amount, percentage, date or name yourself. Every figure must be a placeholder. A reply with a figure outside a placeholder is thrown away.

            Placeholders:
            - {{val PATH}}: one value. PATH is dotted field names. Filter list rows with [field=value]; also != > < >= <=, ~ (contains), & to combine. Use the exact labels from the outline.
            - {{sum PATH}} {{count PATH}} {{min PATH}} {{max PATH}} {{avg PATH}}
            - {{top LIST by FIELD show FIELD}} / {{bottom LIST by FIELD show FIELD}}: the label of the row with the highest / lowest FIELD.
            - {{pct PATH of PATH}}: the first as a percentage of the second.
            - {{list LIST show F1,F2 by FIELD desc limit N}}: a short list of rows ("by", "asc/desc" and "limit" optional).
            - Optional format at the end: |inr |pct |spct (signed %) |num |date. Usually not needed: money, percentages and dates are formatted automatically (lakh/crore).

            Examples (for a structure with total_networth, assets[assetclass_l2, current_value, return_percentage] and liabilities.credit_cards[card_variant, total_due, due_date]):
            - Net worth: Your net worth is {{val total_networth}}.
            - Weakest: Your weakest is {{bottom assets by return_percentage show assetclass_l2}} at {{min assets.return_percentage}}.
            - Gold share: Gold is {{pct assets[assetclass_l2=Gold].current_value of total_current_value}} of your portfolio, worth {{val assets[assetclass_l2=Gold].current_value}}.
            - Card dues: You owe {{sum liabilities.credit_cards.total_due}} on cards: {{list liabilities.credit_cards[total_due>0] show card_variant,total_due,due_date}}.

            Rules:
            - 1 to 3 short, natural sentences for voice. No markdown, no lists with bullets.
            - Answer exactly what was asked, with the most relevant fields. Common codes: SA = savings account (bank balance), MF = mutual funds, EPF = provident fund, US_STOCK = US stocks, STOCK = Indian stocks; "Liquid" is bank, savings and cash.
            - If the data can't answer it (field missing, or the notes say it isn't available), say so in one sentence without placeholders.
            - Information only, never investment advice.
            - Output only the reply.
        """.trimIndent()
    }
}

package dev.jarvis.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Strips identifiers from personal data ON THE PHONE before anything is sent to Claude with the user's consent
 * (D29 step 6): email addresses, links, phone numbers, card/account numbers (also masked ones like "XX1234" or
 * "•• 4321"), IFSC codes, PAN/Aadhaar-style IDs, one-time codes, and (unless the feature needs them) amounts.
 * Names, subjects and message text stay: they're what the user asked about.
 */
object Redactor {
    private val rules: List<Pair<Regex, String>> = listOf(
        Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}") to "[email]",
        Regex("(?i)\\b(?:https?://|www\\.)\\S+") to "[link]",
        Regex("\\b[A-Z]{4}0[A-Z0-9]{6}\\b") to "[ifsc]",
        Regex("\\b[A-Z]{5}[0-9]{4}[A-Z]\\b") to "[id]",
        // Long digit runs (10+ digits, single spaces or dashes allowed): card, account, Aadhaar, phone numbers.
        // Dates like 2026-11-30 have only 8 digits and stay.
        Regex("\\+?\\d(?:[\\s-]?\\d){9,}") to "[number]",
        // Masked account/card numbers: "XX1234", "xxxx 1234", "**** 1234", "•• 4321", "a/c ...1234", "ending 1234".
        Regex("(?i)(?:[x*•·]{2,}[\\s-]?|\\.{3}|\\b(?:a/c|acct|account|card)\\s*(?:no\\.?|number)?\\s*[:#]?\\s*|\\bending\\s+(?:in\\s+)?)\\d{3,6}\\b") to "[account]",
        // One-time codes near their keyword.
        Regex("(?i)\\b(otp|code|pin|passcode|password)\\b(\\D{0,20})\\b\\d{4,8}\\b") to "$1$2[code]",
        Regex("(?i)\\b\\d{4,8}\\b(?=\\D{0,20}\\b(?:is your|is the)\\b[^.]{0,30}\\b(?:otp|code)\\b)") to "[code]",
    )
    private val AMOUNT = Regex("(?i)(?:₹|\\brs\\.?|\\binr)\\s*[0-9][0-9,]*(?:\\.[0-9]{1,2})?")

    fun text(s: String, keepAmounts: Boolean): String {
        var out = if (keepAmounts) s else AMOUNT.replace(s, "[amount]")
        for ((re, replacement) in rules) out = re.replace(out, replacement)
        return out
    }

    /**
     * Redacts every string in [e]. With [keepAmounts] false, plain numbers in money-like fields go too.
     * Field names are kept so Claude can make sense of the data.
     */
    fun json(e: JsonElement, keepAmounts: Boolean, key: String = ""): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.mapValues { (k, v) -> json(v, keepAmounts, k) })
        is JsonArray -> JsonArray(e.map { json(it, keepAmounts, key) })
        is JsonNull -> e
        is JsonPrimitive -> when {
            e.isString -> JsonPrimitive(text(e.content, keepAmounts))
            !keepAmounts && MONEY_KEY.containsMatchIn(key.lowercase()) -> JsonPrimitive("[amount]")
            else -> e
        }
    }

    private val MONEY_KEY = Regex("value|invested|networth|balance|amount|due|price|return|total|salary|limit")
}
